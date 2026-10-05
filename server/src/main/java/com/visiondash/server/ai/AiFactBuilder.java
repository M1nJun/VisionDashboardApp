package com.visiondash.server.ai;

import com.visiondash.server.catalog.VisionCatalog;
import com.visiondash.server.catalog.VisionType;
import com.visiondash.server.settings.SettingsService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns one window of database into the finished evidence a report is written from.
 *
 * The judgement it makes, for every inspector and every judgement code, is one sentence:
 * "this much production normally yields between A and B defects; this window had K". A
 * count past the top of that band, by enough and for long enough, is an alert. Everything
 * else here - the queries, the episode bookkeeping - exists to make that sentence true.
 *
 * Nothing here writes to the ingest path's tables. The only rows it writes are its own
 * alert episodes.
 */
@Service
public class AiFactBuilder {

    /** Defect names carried per finding. Enough to name the problem, not to list the lot. */
    private static final int TOP_ITEMS_PER_FINDING = 3;

    private final JdbcTemplate jdbc;
    private final VisionCatalog catalog;
    private final SettingsService settings;
    private final NormalModel normal;
    private final EpisodeStore episodes;

    public AiFactBuilder(JdbcTemplate jdbc, VisionCatalog catalog, SettingsService settings,
                         NormalModel normal, EpisodeStore episodes) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.settings = settings;
        this.normal = normal;
        this.episodes = episodes;
    }

    public AiFacts build(LocalDateTime windowStart, LocalDateTime windowEnd) {
        int windowMinutes = (int) Duration.between(windowStart, windowEnd).toMinutes();
        int baselineHours = settings.getInt("ai_report_baseline_hours", 24);
        int spreadDays = settings.getInt("ai_report_spread_days", 7);

        Map<String, String> visionKeyByAgent = new LinkedHashMap<>();
        for (String line : catalog.lines()) {
            for (VisionType type : catalog.gridOrdered()) {
                visionKeyByAgent.put(VisionCatalog.agentId(line, type.key()), type.key());
            }
        }
        Map<String, List<EpisodeStore.Range>> exclusions =
                episodes.exclusions(windowStart.minusDays(spreadDays + 1));

        // The spread barely moves and costs a week of windows to read, so it is measured a
        // few times a day rather than every report.
        if (normal.stale(windowStart)) {
            normal.measure(windowStart, windowMinutes, visionKeyByAgent, exclusions);
        }

        // One load covers both the window being reported on and the history it is judged
        // against, so the level and the count can never come from different readings.
        NormalModel.Series series = normal.series(windowStart.minusHours(baselineHours), windowEnd,
                windowMinutes);
        Map<String, EpisodeStore.Episode> open = episodes.open();

        AiFacts.Rule rule = new AiFacts.Rule(
                settings.getInt("ai_report_min_inspected", 200),
                settings.getDouble("ai_report_alert_sigma", 3.0),
                settings.getDouble("ai_report_alert_excess", 3.0),
                settings.getDouble("ai_report_alert_ratio", 2.0),
                settings.getInt("ai_report_confirm_windows", 2),
                settings.getInt("ai_report_clear_windows", 2),
                settings.getDouble("ai_report_fast_ratio", 5.0),
                settings.getDouble("ai_report_fast_excess", 10.0));

        List<AiFacts.Finding> findings = new ArrayList<>();
        List<AiFacts.ThresholdMark> thresholds = new ArrayList<>();
        Map<String, Map<String, List<AiFacts.ItemCount>>> items = topItems(windowStart, windowEnd);

        for (String line : catalog.lines()) {
            for (VisionType type : catalog.gridOrdered()) {
                String agentId = VisionCatalog.agentId(line, type.key());
                NormalModel.Window window = series.byAgent().getOrDefault(agentId, List.of()).stream()
                        .filter(w -> w.start().equals(windowStart)).findFirst().orElse(null);
                if (window == null || window.inspected() == 0) {
                    continue;               // nothing ran here; silence is the right report
                }
                for (VisionType.Judgements.Defect defect : type.judgements().defect()) {
                    String judgement = defect.code();
                    long count = series.defects(agentId, judgement, windowStart);

                    Double critical = criticalThreshold(type, defect);
                    double ratePct = 100.0 * count / window.inspected();
                    if (critical != null && count > 0 && ratePct >= critical) {
                        thresholds.add(new AiFacts.ThresholdMark(line, type.key(), judgement,
                                ratePct, critical, count, window.inspected()));
                    }

                    AiFacts.Finding finding = judge(line, type, judgement, agentId, window, count,
                            series, windowStart, exclusions, open, rule,
                            items.getOrDefault(agentId, Map.of()));
                    if (finding != null) {
                        findings.add(finding);
                    }
                }
            }
        }
        findings.sort(Comparator.comparingDouble(AiFactBuilder::rank).reversed());
        int max = settings.getInt("ai_report_max_findings", 12);
        if (findings.size() > max) {
            findings = new ArrayList<>(findings.subList(0, max));
        }

        List<AiFacts.LineFact> lines = lineFacts(series, windowStart, windowMinutes, windowEnd);
        return new AiFacts(
                windowStart.atZone(ZoneId.systemDefault()).toInstant(),
                windowEnd.atZone(ZoneId.systemDefault()).toInstant(),
                windowMinutes, baselineHours, spreadDays,
                fleet(lines, series, windowStart, windowMinutes),
                lines, findings, thresholds,
                alarms(windowStart, windowEnd), statuses(), rule);
    }

    // ---- the judgement -----------------------------------------------------

    /**
     * One inspector, one judgement: is this window outside what this slot normally does?
     *
     * The order matters. Whether the window is thick enough to carry a rate is settled
     * before any rate is looked at, because a rate off a hundred cells is not a weaker
     * truth - it is a different question, and the only honest answers to it are counts.
     *
     * An open alert is judged against the inspector as it was BEFORE the fault, not
     * against its recent history. Without that, a fault that lasts a day becomes the
     * inspector's new normal and switches its own alert off: C-1's C-NG expected 0.2
     * defects per window before it failed, and 121 twenty-four hours later.
     */
    private AiFacts.Finding judge(String line, VisionType type, String judgement, String agentId,
                                  NormalModel.Window window, long count, NormalModel.Series series,
                                  LocalDateTime windowStart,
                                  Map<String, List<EpisodeStore.Range>> exclusions,
                                  Map<String, EpisodeStore.Episode> open, AiFacts.Rule rule,
                                  Map<String, List<AiFacts.ItemCount>> itemsByJudgement) {
        String key = slotKey(line, type.key(), judgement);
        EpisodeStore.Episode episode = open.get(key);
        List<AiFacts.ItemCount> items = itemsByJudgement.getOrDefault(judgement, List.of());
        if (items.size() > TOP_ITEMS_PER_FINDING) {
            items = items.subList(0, TOP_ITEMS_PER_FINDING);
        }

        // --- 1. thin windows carry counts, never a rate ---
        if (window.inspected() < rule.minInspected()) {
            if (episode != null && !episode.confirmed()) {
                episodes.discard(episode.id());
            }
            Double critical = criticalThreshold(type, defectOf(type, judgement));
            boolean red = critical != null && 100.0 * count / window.inspected() >= critical;
            return count > 0 && red
                    ? new AiFacts.Finding(line, type.key(), type.displayName(), judgement,
                        AiFacts.Verdict.INSUFFICIENT, window.inspected(), count,
                        null, null, null, null, null, null, window.activeMinutes(),
                        null, false, false, items)
                    : null;
        }

        // --- 2. what does this slot normally do ---
        double rate;
        double spread;
        boolean frozen = episode != null && episode.confirmed();
        if (frozen) {
            rate = episode.frozenRate();
            spread = episode.frozenSpread();
        } else {
            NormalModel.Level level = normal.level(series, agentId, judgement, windowStart,
                    exclusions.get(key));
            if (level == null) {
                return null;                // nothing to compare against yet
            }
            rate = level.rate();
            spread = normal.spread(key, type.key(), judgement);
        }
        double expected = rate * window.inspected();
        double high = Stats.bandHigh(expected, spread, rule.sigma());
        double low = Stats.bandLow(expected, spread, rule.sigma());

        boolean above = count > high;
        boolean bigEnough = (count - expected) >= rule.minExcess() && count >= rule.minRatio() * expected;
        boolean flagged = above && bigEnough;
        // Far enough past the band that a second opinion costs half an hour and settles
        // nothing: 103 defects where 0.9 were normal is not waiting for confirmation.
        boolean immediate = flagged && count >= rule.fastRatio() * expected
                && (count - expected) >= rule.fastExcess();

        // --- 3. the episode ---
        if (flagged) {
            boolean confirmed;
            LocalDateTime startedAt;
            if (episode == null) {
                confirmed = immediate || rule.confirmWindows() <= 1;
                episodes.start(line, type.key(), judgement, windowStart, confirmed,
                        rate, spread, count, expected);
                startedAt = windowStart;
            } else {
                confirmed = episode.confirmed() || immediate
                        || episode.flagWindows() + 1 >= rule.confirmWindows();
                episodes.extend(episode.id(), windowStart, count, expected, confirmed);
                startedAt = episode.startedAt();
            }
            if (!confirmed) {
                // An odd window still waiting for the next one to agree with it: recorded,
                // but not an alert, and it does not colour the report.
                return finding(line, type, judgement, AiFacts.Verdict.ELEVATED, window, count,
                        expected, low, high, spread, rate, null, false, false, items);
            }
            // New in the window it was confirmed in, whether that took one window or three.
            boolean isNew = episode == null || !episode.confirmed();
            return finding(line, type, judgement, AiFacts.Verdict.SPIKE, window, count,
                    expected, low, high, spread, rate,
                    startedAt.atZone(ZoneId.systemDefault()).toInstant(),
                    isNew, frozen, items);
        }

        if (episode != null) {
            if (!episode.confirmed()) {
                episodes.discard(episode.id());          // the blip the next window disagreed with
            } else if (episode.clearWindows() + 1 >= rule.clearWindows()) {
                episodes.resolve(episode.id(), windowStart);
                return null;                              // released: back to normal for good
            } else {
                episodes.clearOne(episode.id());
                // Still open. One quiet window does not end a fault, and the alert stays up
                // so nobody reads the gap as "fixed".
                return finding(line, type, judgement, AiFacts.Verdict.SPIKE, window, count,
                        expected, low, high, spread, rate,
                        episode.startedAt().atZone(ZoneId.systemDefault()).toInstant(),
                        false, true, items);
            }
        }

        // Outside the band but too small to raise - worth a look, not a call.
        return above ? finding(line, type, judgement, AiFacts.Verdict.ELEVATED, window, count,
                expected, low, high, spread, rate, null, false, false, items) : null;
    }

    private AiFacts.Finding finding(String line, VisionType type, String judgement, String verdict,
                                    NormalModel.Window window, long count, double expected,
                                    double low, double high, double spread, double rate,
                                    Instant episodeStart, boolean isNew, boolean frozen,
                                    List<AiFacts.ItemCount> items) {
        return new AiFacts.Finding(line, type.key(), type.displayName(), judgement, verdict,
                window.inspected(), count, expected, low, high, spread,
                100.0 * count / window.inspected(), 100.0 * rate, window.activeMinutes(),
                episodeStart, isNew, frozen, items);
    }

    private static VisionType.Judgements.Defect defectOf(VisionType type, String judgement) {
        return type.defectFor(judgement).orElse(null);
    }

    /**
     * The line above which a cell is RED on the wall, or null when this judgement has no
     * such line.
     *
     * Only the colour-driving judgement has one: the warnPct a catalog entry carries for
     * DLNG or C-NG is a reference line on that judgement's own trend chart, not an alarm.
     * These no longer raise anything by themselves - a single defect on a four-hundred
     * cell window crosses a 0.20% line and 247 of 288 of them were exactly that - but the
     * map still marks them, so the report and the wall never disagree about which cells
     * are red.
     */
    private Double criticalThreshold(VisionType type, VisionType.Judgements.Defect defect) {
        if (defect == null || !defect.drivesColor()) {
            return null;
        }
        String profile = type.thresholdProfile();
        return settings.getDouble("defect_rate_critical_pct_" + profile,
                catalog.thresholds(profile).critPct());
    }

    /** Open alerts first, then how far past normal they are. */
    private static double rank(AiFacts.Finding f) {
        double base = switch (f.verdict()) {
            case AiFacts.Verdict.SPIKE -> f.isNew() ? 5000 : 4000;
            case AiFacts.Verdict.ELEVATED -> 2000;
            default -> 1000;
        };
        double excess = f.expected() == null ? f.count() : Math.max(0, f.count() - f.expected());
        return base + Math.min(900, excess * 10);
    }

    /** One inspector and one judgement, as one string: what an episode is opened against. */
    static String slotKey(String line, String visionKey, String judgement) {
        return line + "/" + visionKey + "/" + judgement;
    }

    // ---- lines, fleet, alarms, status --------------------------------------

    private List<AiFacts.LineFact> lineFacts(NormalModel.Series series, LocalDateTime windowStart,
                                             int windowMinutes, LocalDateTime windowEnd) {
        Map<String, Boolean> lotChanges = lotChanges(windowStart, windowEnd);
        List<AiFacts.LineFact> facts = new ArrayList<>();
        for (String line : catalog.lines()) {
            Long produced = null;
            String referenceKey = null;
            for (VisionType candidate : catalog.productionRefOrder()) {
                NormalModel.Window w = windowOf(series, VisionCatalog.agentId(line, candidate.key()), windowStart);
                if (w != null && w.inspected() > 0) {
                    produced = w.inspected();
                    referenceKey = candidate.key();
                    break;
                }
            }
            // Active minutes come from EVERY inspector on the line, whichever ran longest -
            // a line whose reference is offline while five stations inspect is not stopped.
            int active = 0;
            for (VisionType type : catalog.gridOrdered()) {
                NormalModel.Window w = windowOf(series, VisionCatalog.agentId(line, type.key()), windowStart);
                if (w != null) {
                    active = Math.max(active, w.activeMinutes());
                }
            }
            active = Math.min(active, windowMinutes);
            facts.add(new AiFacts.LineFact(line, produced, referenceKey, active,
                    Math.max(0, windowMinutes - active), lotChanges.getOrDefault(line, false)));
        }
        return facts;
    }

    private static NormalModel.Window windowOf(NormalModel.Series series, String agentId, LocalDateTime at) {
        for (NormalModel.Window w : series.byAgent().getOrDefault(agentId, List.of())) {
            if (w.start().equals(at)) {
                return w;
            }
        }
        return null;
    }

    private AiFacts.Fleet fleet(List<AiFacts.LineFact> lines, NormalModel.Series series,
                                LocalDateTime windowStart, int windowMinutes) {
        long produced = lines.stream().filter(l -> l.produced() != null)
                .mapToLong(AiFacts.LineFact::produced).sum();
        long inspected = 0;
        for (List<NormalModel.Window> windows : series.byAgent().values()) {
            for (NormalModel.Window w : windows) {
                if (w.start().equals(windowStart)) {
                    inspected += w.inspected();
                }
            }
        }
        List<AiFacts.Rate> rates = new ArrayList<>();
        for (VisionCatalog.FleetMetric metric : catalog.fleetMetrics()) {
            long units = 0;
            for (VisionCatalog.FleetMetric.Source source : metric.sources()) {
                for (String line : catalog.lines()) {
                    units += series.defects(VisionCatalog.agentId(line, source.visionKey()),
                            source.judgement(), windowStart);
                }
            }
            rates.add(new AiFacts.Rate(metric.key(), metric.label(), units,
                    produced > 0 ? 100.0 * units / produced : null));
        }
        int active = lines.stream().mapToInt(AiFacts.LineFact::activeMinutes).max().orElse(0);
        return new AiFacts.Fleet(produced, Math.min(active, windowMinutes), inspected, 0, rates);
    }

    /**
     * Inspectors that were not reporting, so a silent slot is never mistaken for a clean
     * one. Read from the live agent rows: "produced nothing" and "was not there" look
     * identical in a rollup and mean opposite things.
     */
    private List<AiFacts.StatusFact> statuses() {
        int offlineAfter = settings.getInt("agent_offline_threshold_seconds", 10);
        int idleAfter = settings.getInt("agent_idle_threshold_seconds", 300);
        Instant now = Instant.now();
        List<AiFacts.StatusFact> out = new ArrayList<>();
        jdbc.query("SELECT agent_id, line, vision_key, last_event_at, last_heartbeat_at FROM agents", rs -> {
            Timestamp event = rs.getTimestamp(4);
            Timestamp heartbeat = rs.getTimestamp(5);
            String status;
            if (heartbeat == null || secondsSince(heartbeat, now) > offlineAfter) {
                status = "OFFLINE";
            } else if (event == null || secondsSince(event, now) > idleAfter) {
                status = "IDLE";
            } else {
                return;                     // running; only trouble is worth the space
            }
            String visionKey = rs.getString(3);
            out.add(new AiFacts.StatusFact(rs.getString(2), visionKey, label(visionKey), status,
                    event == null ? null : secondsSince(event, now) / 60));
        });
        out.sort(Comparator.comparing(AiFacts.StatusFact::line).thenComparing(AiFacts.StatusFact::visionKey));
        return out;
    }

    // ---- loaders -----------------------------------------------------------

    /**
     * Which defects, by name, per agent and judgement.
     *
     * Grouped from defect_items rather than from the occurrence's summary string: a unit
     * that failed three checks has one summary no two units share, so grouping by it would
     * answer "how many units failed this exact combination" when the question is "which
     * check is failing". Side is kept because on Example B it is half the diagnosis.
     */
    private Map<String, Map<String, List<AiFacts.ItemCount>>> topItems(LocalDateTime from, LocalDateTime to) {
        Map<String, Map<String, List<AiFacts.ItemCount>>> byAgent = new LinkedHashMap<>();
        jdbc.query("""
                SELECT o.agent_id, o.judgement, i.item_name, i.side, COUNT(*) AS c
                FROM defect_occurrences o
                JOIN defect_items i ON i.occurrence_id = o.id
                WHERE o.occurred_at >= ? AND o.occurred_at < ?
                GROUP BY o.agent_id, o.judgement, i.item_name, i.side
                ORDER BY o.agent_id, o.judgement, c DESC
                """, rs -> {
            String side = rs.getString(4);
            byAgent.computeIfAbsent(rs.getString(1), a -> new LinkedHashMap<>())
                    .computeIfAbsent(rs.getString(2), j -> new ArrayList<>())
                    .add(new AiFacts.ItemCount(rs.getString(3),
                            side == null || side.isEmpty() ? null : side, rs.getLong(5)));
        }, Timestamp.valueOf(from), Timestamp.valueOf(to));
        return byAgent;
    }

    private List<AiFacts.AlarmFact> alarms(LocalDateTime from, LocalDateTime to) {
        List<AiFacts.AlarmFact> out = new ArrayList<>();
        jdbc.query("""
                SELECT a.line, a.vision_key, a.alarm_code, a.alarm_name,
                       COUNT(*) AS c, MAX(a.alarm_at)
                FROM alarms a
                WHERE a.alarm_at >= ? AND a.alarm_at < ?
                GROUP BY a.line, a.vision_key, a.alarm_code, a.alarm_name
                ORDER BY c DESC
                """, rs -> {
            String visionKey = rs.getString(2);
            out.add(new AiFacts.AlarmFact(rs.getString(1), visionKey, label(visionKey),
                    rs.getString(3), rs.getString(4), rs.getLong(5), instant(rs.getTimestamp(6))));
        }, Timestamp.valueOf(from), Timestamp.valueOf(to));
        return out;
    }

    /**
     * Lines whose material changed mid-window - figures either side of the boundary
     * describe different material. Taken from the rollups because they are keyed by lot,
     * so more than one lot on one agent is a changeover whether or not the LOT_CHANGED
     * event has landed yet.
     */
    private Map<String, Boolean> lotChanges(LocalDateTime from, LocalDateTime to) {
        Map<String, Boolean> byLine = new HashMap<>();
        jdbc.query("""
                SELECT a.line, COUNT(DISTINCT r.lot_id) AS lots
                FROM vision_rollup r
                JOIN agents a ON a.agent_id = r.agent_id
                WHERE r.bucket_start >= ? AND r.bucket_start < ?
                GROUP BY a.line
                """, rs -> {
            byLine.put(rs.getString(1), rs.getInt(2) > 1);
        }, Timestamp.valueOf(from), Timestamp.valueOf(to));
        return byLine;
    }

    /** The inspector's display name, falling back to its key if it is not in the catalog. */
    private String label(String visionKey) {
        return catalog.find(visionKey).map(VisionType::displayName).orElse(visionKey);
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static long secondsSince(Timestamp then, Instant now) {
        return Duration.between(then.toInstant(), now).getSeconds();
    }
}
