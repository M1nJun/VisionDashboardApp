package com.visiondash.server.ai;

import tools.jackson.databind.ObjectMapper;
import com.visiondash.server.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds, stores and serves the ten-minute reports.
 *
 * The ordering in {@link #run} is the feature's central promise made structural: the
 * facts are written to the database BEFORE the model is asked for anything. A model
 * that is unreachable, mid-restart, or simply slower than its timeout costs the window
 * its sentences and nothing else - the analysis, which is the part carrying every
 * number, is already durable by then. Calling the model first and storing the pair
 * afterwards would have made an Ollama outage silently delete the analysis too, and an
 * outage is the moment somebody is most likely to be looking at the screen.
 */
@Service
public class AiReportService {
    private static final Logger log = LoggerFactory.getLogger(AiReportService.class);

    /** Longest run of past windows a restart will try to catch up on - see {@link #dueWindow}. */
    private static final int MAX_BACKFILL_WINDOWS = 1;

    private final JdbcTemplate jdbc;
    private final AiFactBuilder factBuilder;
    private final AiPrompt prompt;
    private final OllamaClient ollama;
    private final SettingsService settings;
    private final EpisodeStore episodes;
    private final ObjectMapper mapper;

    /** Cached: the answer only changes when somebody runs a migration, i.e. never at runtime. */
    private volatile Boolean schemaReady;

    public AiReportService(JdbcTemplate jdbc, AiFactBuilder factBuilder, AiPrompt prompt,
                           OllamaClient ollama, SettingsService settings, EpisodeStore episodes,
                           ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.factBuilder = factBuilder;
        this.prompt = prompt;
        this.ollama = ollama;
        this.settings = settings;
        this.episodes = episodes;
        this.mapper = mapper;
    }

    // ---- availability ------------------------------------------------------

    /**
     * Whether db/migrate-ai-reports.sql has been applied.
     *
     * Checked rather than assumed because these two tables are deliberately absent from
     * SchemaCheck's required list: a central PC given a new jar but not the migration
     * must keep serving the grid. Everything in this class returns empty or refuses
     * politely until an operator runs it.
     *
     * Only a TRUE answer is cached. Tables cannot disappear under a running server, so
     * "ready" is permanent and worth keeping; "not ready" is a state an operator is
     * actively in the middle of fixing, and caching it meant that running the migration
     * on a server that was already up left the tab dark until somebody thought to
     * restart - with the log still insisting the migration had not been run. The cost of
     * re-asking is one information_schema count, on a screen that polls every 30s.
     */
    public boolean schemaReady() {
        if (Boolean.TRUE.equals(schemaReady)) {
            return true;
        }
        Integer tables = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name IN ('ai_reports', 'ai_report_findings', 'ai_episodes')
                """, Integer.class);
        boolean ready = tables != null && tables == 3;
        if (ready) {
            schemaReady = true;
            log.info("AI reports are enabled");
        } else if (schemaReady == null) {
            // Warned once, not on every poll - this runs from the scheduler every 30s.
            schemaReady = false;
            log.warn("AI reports are unavailable: run the db migrations "
                    + "(migrate-ai-reports.sql, migrate-ai-episodes.sql) to enable them");
        }
        return ready;
    }

    public boolean enabled() {
        return settings.getInt("ai_report_enabled", 1) == 1;
    }

    public int intervalMinutes() {
        // Floored at one: a zero or negative interval would make every window empty and
        // the scheduler spin on the same instant forever.
        return Math.max(1, settings.getInt("ai_report_interval_minutes", 10));
    }

    public record Status(
            boolean enabled,
            boolean schemaReady,
            boolean ollamaAvailable,
            String model,
            int intervalMinutes,
            Instant lastReportAt,
            Instant nextRunAt,
            /** Whether a new alert should interrupt whoever is looking at the dashboard. */
            boolean popupEnabled
    ) {
    }

    /** What the tab shows when there is nothing to show, so a dark screen explains itself. */
    public Status status() {
        boolean ready = schemaReady();
        Instant last = null;
        if (ready) {
            Timestamp ts = jdbc.queryForObject(
                    "SELECT MAX(generated_at) FROM ai_reports", Timestamp.class);
            last = ts == null ? null : ts.toInstant();
        }
        LocalDateTime nextEnd = alignedWindowEnd(LocalDateTime.now()).plusMinutes(intervalMinutes());
        return new Status(enabled(), ready, ollama.available(), ollama.model(), intervalMinutes(),
                last, nextEnd.plusSeconds(settings.getInt("ai_report_lag_seconds", 45))
                        .atZone(ZoneId.systemDefault()).toInstant(),
                settings.getInt("ai_popup_enabled", 1) == 1);
    }

    // ---- scheduling --------------------------------------------------------

    /**
     * The most recent complete window that has not been reported on yet, or null.
     *
     * Windows are aligned to the wall clock (10:00, 10:10, 10:20) rather than measured
     * from whenever the server happened to start, so a restart does not shift every
     * subsequent report by a few minutes and a human comparing two reports is comparing
     * comparable slices.
     *
     * Only ever offers ONE past window. A server that was down overnight would otherwise
     * come back and generate seventy reports nobody will read, at a minute of GPU each,
     * before it got round to the current one - and the current one is the only one
     * anybody wants. The gap is visible in the history either way.
     */
    LocalDateTime dueWindow() {
        int interval = intervalMinutes();
        int lag = settings.getInt("ai_report_lag_seconds", 45);
        LocalDateTime now = LocalDateTime.now();

        LocalDateTime end = alignedWindowEnd(now);
        // The window has closed but the agents that were mid-batch when it did have not
        // necessarily landed yet, so it is not readable for another few seconds.
        if (Duration.between(end, now).getSeconds() < lag) {
            end = end.minusMinutes(interval);
        }
        for (int i = 0; i < MAX_BACKFILL_WINDOWS; i++) {
            LocalDateTime start = end.minusMinutes(interval);
            if (!exists(start)) {
                return start;
            }
            end = start;
        }
        return null;
    }

    /**
     * The last window to have closed - what a manual run analyses.
     *
     * Here rather than in the controller, which was working it out a second time. Window
     * alignment is the kind of arithmetic that drifts apart silently once there are two
     * copies of it, and a manual run landing on a different window than the scheduler
     * would is a bug nobody would think to look for.
     */
    public LocalDateTime lastClosedWindow() {
        return alignedWindowEnd(LocalDateTime.now()).minusMinutes(intervalMinutes());
    }

    /** Latest interval boundary at or before {@code at}. */
    private LocalDateTime alignedWindowEnd(LocalDateTime at) {
        int interval = intervalMinutes();
        LocalDateTime hour = at.withMinute(0).withSecond(0).withNano(0);
        long minutesIn = Duration.between(hour, at).toMinutes();
        return hour.plusMinutes(minutesIn / interval * interval);
    }

    private boolean exists(LocalDateTime windowStart) {
        Integer found = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_reports WHERE window_start = ?",
                Integer.class, Timestamp.valueOf(windowStart));
        return found != null && found > 0;
    }

    // ---- the run itself ----------------------------------------------------

    /**
     * Analyses one window and, if it can, has it written up.
     *
     * @param replace true to redo a window that already has a report - what the manual
     *                run on the tab does, so an operator can prove a fresh Ollama install
     *                works without waiting ten minutes to find out.
     * @return the id of the stored report, or null when there was nothing to report on.
     */
    public Long run(LocalDateTime windowStart, boolean replace) {
        LocalDateTime windowEnd = windowStart.plusMinutes(intervalMinutes());
        if (exists(windowStart)) {
            if (!replace) {
                return null;
            }
            jdbc.update("DELETE FROM ai_reports WHERE window_start = ?", Timestamp.valueOf(windowStart));
        }

        AiFacts facts = factBuilder.build(windowStart, windowEnd);

        // A window in which the whole floor produced nothing is stored rather than
        // skipped. The gap is itself the report - "nothing ran between 03:10 and 03:20"
        // is a fact somebody may need later - and storing it also stops the scheduler
        // from offering the same dead window again on every tick.
        boolean quiet = facts.fleet().produced() == 0 && facts.fleet().inspectedAllStations() == 0;

        String severity = severity(facts);
        // PENDING until the model answers. On this hardware that gap is tens of seconds -
        // long enough for somebody to open the tab inside it - and a row that said OK
        // with no summary yet was indistinguishable from one whose model had failed, so
        // the tab announced a failure while the sentences were still being written.
        long id = insert(windowStart, windowEnd, facts, quiet ? "SKIPPED" : "PENDING", severity);
        insertFindings(id, facts.findings());

        if (quiet) {
            update(id, "SKIPPED", "No production - nothing was inspected in this window.",
                    null, null, null, null);
            return id;
        }

        try {
            OllamaClient.Answer answer = ollama.generate(prompt.system(), prompt.render(facts));
            String[] split = splitHeadline(answer.text());
            update(id, "OK", split[0], split[1], answer.model(), (int) answer.latencyMs(), null);
        } catch (RuntimeException e) {
            // The facts are already stored, so this costs the prose and nothing else. The
            // tab renders the findings directly when summary_text is null.
            log.warn("AI report {} has its figures but no summary: {}", windowStart, e.getMessage());
            update(id, "LLM_FAILED", fallbackHeadline(facts), null, ollama.model(), null,
                    abbreviate(e.getMessage(), 500));
        }
        return id;
    }

    /**
     * Where the tab's colour comes from - decided here, never by the model.
     *
     * An alert stays an alert for as long as the fault is open, including the quiet
     * windows inside it: one calm window is not a repair, and a screen that flickers back
     * to green in the middle of a fault teaches people to ignore it. What changes as a
     * fault runs on is the header's "N new, M continuing", not the colour.
     */
    private String severity(AiFacts facts) {
        boolean watch = !facts.alarms().isEmpty() || !facts.statuses().isEmpty();
        for (AiFacts.Finding f : facts.findings()) {
            if (AiFacts.Verdict.SPIKE.equals(f.verdict())) {
                return "ALERT";
            }
            watch = true;
        }
        for (AiFacts.LineFact line : facts.lines()) {
            if (line.stoppedMinutes() >= facts.windowMinutes()) {
                watch = true;
            }
        }
        return watch ? "WATCH" : "NORMAL";
    }

    /**
     * A headline for when the model could not supply one.
     *
     * Deliberately plain. Its job is to let somebody glance at the tab and know whether
     * to open it, on the day the GPU is busy or Ollama has not been started - which is
     * exactly the day the fallback needs to be doing its job.
     */
    private String fallbackHeadline(AiFacts facts) {
        long spikes = facts.findings().stream()
                .filter(f -> AiFacts.Verdict.SPIKE.equals(f.verdict())).count();
        if (spikes > 0) {
            AiFacts.Finding worst = facts.findings().get(0);
            int others = facts.findings().size() - 1;
            return String.format("%s %s %s: %,d where %.0f is usual%s (no summary written - see the numbers below)",
                    worst.line(), worst.displayName(), worst.judgement(), worst.count(),
                    worst.expected() == null ? 0 : worst.expected(),
                    others > 0 ? ", and " + others + " more" : "");
        }
        if (!facts.findings().isEmpty()) {
            return String.format("%d item(s) worth a look (no summary written - see the numbers below)",
                    facts.findings().size());
        }
        return "Nothing out of the ordinary (no summary written)";
    }

    /**
     * Splits the model's answer into the one-line headline and the rest.
     *
     * The prompt asks for the headline on its own first line. When it does not comply -
     * a leading blank line, a stray bullet - the whole answer becomes the body and the
     * headline is left empty rather than guessed at, because a headline sliced out of
     * mid-sentence is worse on a wall than none.
     */
    private static String[] splitHeadline(String text) {
        String[] lines = text.split("\\R", 2);
        String first = lines[0].trim();
        String rest = lines.length > 1 ? lines[1].trim() : "";
        if (first.isEmpty() || first.startsWith("-") || first.startsWith("•") || first.length() > 200) {
            return new String[]{null, text.trim()};
        }
        return new String[]{first, rest};
    }

    // ---- persistence -------------------------------------------------------

    private long insert(LocalDateTime start, LocalDateTime end, AiFacts facts,
                        String status, String severity) {
        String json;
        try {
            json = mapper.writeValueAsString(facts);
        } catch (Exception e) {
            throw new IllegalStateException("could not serialise the report facts", e);
        }
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO ai_reports
                      (window_start, window_end, status, severity, facts_json)
                    VALUES (?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setTimestamp(1, Timestamp.valueOf(start));
            ps.setTimestamp(2, Timestamp.valueOf(end));
            ps.setString(3, status);
            ps.setString(4, severity);
            ps.setString(5, json);
            return ps;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("ai_reports insert returned no id");
        }
        return key.longValue();
    }

    private void insertFindings(long reportId, List<AiFacts.Finding> findings) {
        if (findings.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO ai_report_findings
                  (report_id, line, vision_key, judgement, verdict, inspected, defect_count,
                   rate_pct, baseline_pct, expected_count, normal_low, normal_high, spread,
                   episode_started, is_new, top_items, active_minutes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, findings.stream().map(f -> new Object[]{
                reportId, f.line(), f.visionKey(), f.judgement(), f.verdict(),
                f.inspected(), f.count(), f.ratePct(), f.baselinePct(), f.expected(),
                f.normalLow(), f.normalHigh(), f.spread(),
                f.episodeStart() == null ? null : Timestamp.from(f.episodeStart()),
                f.isNew() ? 1 : 0, topItems(f), f.activeMinutes()
        }).toList());
    }

    private static String topItems(AiFacts.Finding f) {
        if (f.topItems().isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (AiFacts.ItemCount item : f.topItems()) {
            if (sb.length() > 0) {
                sb.append(" + ");
            }
            sb.append(item.name());
            if (item.side() != null) {
                sb.append("(").append(item.side()).append(")");
            }
            sb.append(" x").append(item.count());
        }
        return abbreviate(sb.toString(), 400);
    }

    private void update(long id, String status, String headline, String summary,
                        String model, Integer latencyMs, String error) {
        jdbc.update("""
                UPDATE ai_reports
                SET status = ?, headline = ?, summary_text = ?, model = ?, latency_ms = ?, error = ?
                WHERE id = ?
                """, status, abbreviate(headline, 400), summary, model, latencyMs, error, id);
    }

    // ---- reads -------------------------------------------------------------

    /** Newest first. The tab opens on the first of these and lists the rest as history. */
    public List<AiReportDto> recent(int limit) {
        if (!schemaReady()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT id, window_start, window_end, generated_at, status, severity,
                       headline, summary_text, model, latency_ms, error, facts_json
                FROM ai_reports
                ORDER BY window_start DESC
                LIMIT ?
                """, this::mapReport, Math.max(1, Math.min(limit, 200)));
    }

    public AiReportDto byId(long id) {
        if (!schemaReady()) {
            return null;
        }
        List<AiReportDto> found = jdbc.query("""
                SELECT id, window_start, window_end, generated_at, status, severity,
                       headline, summary_text, model, latency_ms, error, facts_json
                FROM ai_reports WHERE id = ?
                """, this::mapReport, id);
        return found.isEmpty() ? null : found.get(0);
    }

    private AiReportDto mapReport(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        AiFacts facts;
        try {
            facts = mapper.readValue(rs.getString("facts_json"), AiFacts.class);
        } catch (Exception e) {
            // A row written by an older build whose fact shape has since changed. The
            // prose and the verdict are still perfectly readable without it, so the
            // report is served with the detail panel empty rather than not served.
            log.warn("report {} has facts this build cannot read: {}", rs.getLong("id"), e.getMessage());
            facts = null;
        }
        Timestamp generated = rs.getTimestamp("generated_at");
        // Read and tested together. wasNull() answers for whichever column was read last,
        // so asking it down in the argument list - after model - reported on model
        // instead: a null model silently discarded the latency, and a null latency came
        // back as 0 and rendered on the card as "0.0s".
        int latency = rs.getInt("latency_ms");
        Integer latencyMs = rs.wasNull() ? null : latency;
        return new AiReportDto(
                rs.getLong("id"),
                rs.getTimestamp("window_start").toInstant(),
                rs.getTimestamp("window_end").toInstant(),
                generated == null ? null : generated.toInstant(),
                rs.getString("status"),
                rs.getString("severity"),
                rs.getString("headline"),
                rs.getString("summary_text"),
                rs.getString("model"),
                latencyMs,
                rs.getString("error"),
                facts);
    }

    /**
     * Drops reports past their retention.
     *
     * Findings go with them through the cascade on the foreign key, so this is one
     * statement rather than a pair that could be interrupted between halves.
     */
    public int purge() {
        if (!schemaReady()) {
            return 0;
        }
        int days = settings.getInt("ai_report_retention_days", 30);
        LocalDateTime before = LocalDateTime.now().minusDays(days);
        // Resolved episodes go with them; open ones are kept however old, because an open
        // one is a fault nobody has dealt with yet.
        episodes.purge(before);
        return jdbc.update("DELETE FROM ai_reports WHERE window_start < ?", Timestamp.valueOf(before));
    }

    private static String abbreviate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
