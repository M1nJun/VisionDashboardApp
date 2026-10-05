package com.visiondash.server.ai;

import com.visiondash.server.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What each inspector normally does, measured from its own history.
 *
 * Two numbers, on two different clocks, because they answer two different questions:
 *
 *   level  (last 24 hours) - how many defects this much production normally yields.
 *                            The centre of the band. Re-read every report.
 *   spread (last 7 days)   - how far that count normally wanders, as a multiple of the
 *                            chance-only wobble. The width of the band. Recomputed a few
 *                            times a day, because it barely moves and costs a week of
 *                            windows to read.
 *
 * Both were chosen against five days of real production rather than picked. Horizons from
 * 6 hours to 3 days predict the next window equally well, so level takes the one that
 * covers a whole lot cycle and still has enough cells at 3am. Spread measured over 12
 * hours jumps ±34% between readings and comes out 30% too small - which makes the band too
 * narrow and the alerts too many - while 7 days is stable to within a few percent.
 *
 * Both measurements skip windows belonging to an alert. A broken inspector must not teach
 * the system that broken is normal: with its own four-day failure left in, C-1's C-NG
 * measured a spread of 50, which would have left it nearly impossible to flag again.
 */
@Service
public class NormalModel {
    private static final Logger log = LoggerFactory.getLogger(NormalModel.class);

    /** Fewest windows a slot's own spread can be measured from before it is guesswork. */
    private static final int MIN_SPREAD_WINDOWS = 12;

    private final JdbcTemplate jdbc;
    private final SettingsService settings;

    /** slot+judgement -> measured spread, and the vision type's own figure as the fallback. */
    private volatile Map<String, Double> slotSpread = Map.of();
    private volatile Map<String, Double> typeSpread = Map.of();
    private volatile LocalDateTime measuredAt;

    public NormalModel(JdbcTemplate jdbc, SettingsService settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    /** One inspector's window: how much it checked, and when. */
    record Window(LocalDateTime start, long inspected, int activeMinutes) {
    }

    /** Production and defects per window, for every inspector, over one stretch of time. */
    record Series(Map<String, List<Window>> byAgent, Map<String, Map<LocalDateTime, Long>> counts) {

        long defects(String agentId, String judgement, LocalDateTime window) {
            Map<LocalDateTime, Long> series = counts.get(agentId + "|" + judgement);
            return series == null ? 0 : series.getOrDefault(window, 0L);
        }
    }

    /**
     * The centre of the band: what this slot's rate has been lately.
     *
     * @return null when too little production has gone through to compare against, which
     *         is the honest answer at the start of a shift or after a long stop.
     */
    record Level(double rate, long cells, int windows) {
    }

    // ---- level -------------------------------------------------------------

    /**
     * Loads every inspector's windows over a stretch of time, bucketed to the report
     * interval. One query for production and one for judgements, whatever the fleet size.
     */
    Series series(LocalDateTime from, LocalDateTime to, int intervalMinutes) {
        long bucketSeconds = intervalMinutes * 60L;
        Map<String, List<Window>> byAgent = new LinkedHashMap<>();
        jdbc.query("""
                SELECT FROM_UNIXTIME(FLOOR(UNIX_TIMESTAMP(bucket_start) / ?) * ?) AS w,
                       agent_id, SUM(inspected_count), COUNT(DISTINCT bucket_start)
                FROM vision_rollup
                WHERE bucket_start >= ? AND bucket_start < ?
                GROUP BY w, agent_id
                """, rs -> {
            byAgent.computeIfAbsent(rs.getString(2), k -> new ArrayList<>())
                    .add(new Window(rs.getTimestamp(1).toLocalDateTime(), rs.getLong(3), rs.getInt(4)));
        }, bucketSeconds, bucketSeconds, Timestamp.valueOf(from), Timestamp.valueOf(to));
        byAgent.values().forEach(list -> list.sort(Comparator.comparing(Window::start)));

        Map<String, Map<LocalDateTime, Long>> counts = new HashMap<>();
        jdbc.query("""
                SELECT FROM_UNIXTIME(FLOOR(UNIX_TIMESTAMP(bucket_start) / ?) * ?) AS w,
                       agent_id, judgement, SUM(unit_count)
                FROM vision_rollup_judgement
                WHERE bucket_start >= ? AND bucket_start < ?
                GROUP BY w, agent_id, judgement
                """, rs -> {
            counts.computeIfAbsent(rs.getString(2) + "|" + rs.getString(3), k -> new HashMap<>())
                    .put(rs.getTimestamp(1).toLocalDateTime(), rs.getLong(4));
        }, bucketSeconds, bucketSeconds, Timestamp.valueOf(from), Timestamp.valueOf(to));

        return new Series(byAgent, counts);
    }

    /**
     * This slot's recent rate, ignoring thin windows and anything inside an alert.
     *
     * Thin windows are left out of the measurement as well as out of judgement: a window
     * where the line ran two minutes carries almost no information about the rate and a
     * lot of noise.
     */
    Level level(Series series, String agentId, String judgement, LocalDateTime before,
                List<EpisodeStore.Range> excluded) {
        long minCells = settings.getInt("ai_report_min_inspected", 200);
        long cells = 0;
        long defects = 0;
        int windows = 0;
        for (Window w : series.byAgent().getOrDefault(agentId, List.of())) {
            if (!w.start().isBefore(before) || w.inspected() < minCells
                    || EpisodeStore.excluded(excluded, w.start())) {
                continue;
            }
            cells += w.inspected();
            defects += series.defects(agentId, judgement, w.start());
            windows++;
        }
        long floor = settings.getInt("ai_report_baseline_min_cells", 1500);
        return cells < floor ? null : new Level(Stats.level(defects, cells), cells, windows);
    }

    // ---- spread ------------------------------------------------------------

    /**
     * How far this slot normally wanders. Falls back to the vision type's own figure when
     * the slot has too little clean history of its own - a newly deployed inspector is
     * judged like its siblings rather than like a coin.
     */
    public double spread(String slotKey, String visionKey, String judgement) {
        Double own = slotSpread.get(slotKey);
        if (own != null) {
            return own;
        }
        Double type = typeSpread.get(visionKey + "|" + judgement);
        return type != null ? type : 1.0;
    }

    public LocalDateTime measuredAt() {
        return measuredAt;
    }

    /** True when the spread has not been measured for longer than its refresh interval. */
    public boolean stale(LocalDateTime now) {
        int hours = settings.getInt("ai_report_spread_refresh_hours", 6);
        return measuredAt == null || Duration.between(measuredAt, now).toHours() >= hours;
    }

    /**
     * Re-measures every slot's spread from the last seven days.
     *
     * Each window is scored against a rolling 24-hour level built from the windows before
     * it - the same comparison the live report makes - so the spread describes the error
     * the report will actually make, not the variance of the raw rate.
     */
    public synchronized void measure(LocalDateTime now, int intervalMinutes,
                                     Map<String, String> visionKeyByAgent,
                                     Map<String, List<EpisodeStore.Range>> exclusions) {
        int days = settings.getInt("ai_report_spread_days", 7);
        int minDays = settings.getInt("ai_report_spread_min_days", 3);
        double cap = settings.getDouble("ai_report_spread_max", 6.0);
        long minCells = settings.getInt("ai_report_min_inspected", 200);
        long baselineFloor = settings.getInt("ai_report_baseline_min_cells", 1500);
        int baselineHours = settings.getInt("ai_report_baseline_hours", 24);

        LocalDateTime from = now.minusDays(days);
        Series series = series(from, now, intervalMinutes);

        Map<String, List<Double>> residualsBySlot = new HashMap<>();
        Map<String, List<Double>> residualsByType = new HashMap<>();
        Map<String, LocalDateTime> firstSeen = new HashMap<>();
        Map<String, LocalDateTime> lastSeen = new HashMap<>();

        for (Map.Entry<String, List<Window>> entry : series.byAgent().entrySet()) {
            String agentId = entry.getKey();
            String visionKey = visionKeyByAgent.get(agentId);
            if (visionKey == null) {
                continue;
            }
            String line = agentId.substring(0, agentId.length() - visionKey.length() - 1);
            List<Window> windows = entry.getValue();

            for (String judgement : series.counts().keySet().stream()
                    .filter(k -> k.startsWith(agentId + "|"))
                    .map(k -> k.substring(agentId.length() + 1)).toList()) {
                String slotKey = AiFactBuilder.slotKey(line, visionKey, judgement);
                List<EpisodeStore.Range> excluded = exclusions.get(slotKey);

                for (int i = 0; i < windows.size(); i++) {
                    Window target = windows.get(i);
                    if (target.inspected() < minCells || EpisodeStore.excluded(excluded, target.start())) {
                        continue;
                    }
                    long cells = 0;
                    long defects = 0;
                    LocalDateTime cutoff = target.start().minusHours(baselineHours);
                    for (int j = i - 1; j >= 0; j--) {
                        Window past = windows.get(j);
                        if (past.start().isBefore(cutoff)) {
                            break;
                        }
                        if (past.inspected() < minCells || EpisodeStore.excluded(excluded, past.start())) {
                            continue;
                        }
                        cells += past.inspected();
                        defects += series.defects(agentId, judgement, past.start());
                    }
                    if (cells < baselineFloor) {
                        continue;
                    }
                    double expected = Stats.level(defects, cells) * target.inspected();
                    if (expected <= 0) {
                        continue;
                    }
                    double residual = Stats.residual(series.defects(agentId, judgement, target.start()), expected);
                    residualsBySlot.computeIfAbsent(slotKey, k -> new ArrayList<>()).add(residual);
                    residualsByType.computeIfAbsent(visionKey + "|" + judgement, k -> new ArrayList<>()).add(residual);
                    firstSeen.merge(slotKey, target.start(), (a, b) -> a.isBefore(b) ? a : b);
                    lastSeen.merge(slotKey, target.start(), (a, b) -> a.isAfter(b) ? a : b);
                }
            }
        }

        Map<String, Double> slots = new HashMap<>();
        residualsBySlot.forEach((key, residuals) -> {
            LocalDateTime first = firstSeen.get(key);
            LocalDateTime last = lastSeen.get(key);
            if (first == null || Duration.between(first, last).toDays() < minDays) {
                return;                     // too short a history to trust; use the type's
            }
            Double measured = Stats.spread(residuals, MIN_SPREAD_WINDOWS);
            if (measured != null) {
                slots.put(key, clamp(measured, cap));
            }
        });
        Map<String, Double> types = new HashMap<>();
        residualsByType.forEach((key, residuals) -> {
            Double measured = Stats.spread(residuals, MIN_SPREAD_WINDOWS);
            if (measured != null) {
                types.put(key, clamp(measured, cap));
            }
        });

        slotSpread = slots;
        typeSpread = types;
        measuredAt = now;
        log.info("normal spread measured over {} days: {} slots, {} vision types", days,
                slots.size(), types.size());
    }

    /**
     * Never below 1 - an inspector cannot wander less than chance, and a measurement
     * saying so is a short sample, not a steadier machine. Capped because a slot whose
     * measurement is enormous is describing a fault rather than a habit: the healthiest
     * Example B DLNG measures around 5, so anything far past that would only make the
     * inspector undetectable.
     */
    private static double clamp(double spread, double cap) {
        return Math.max(1.0, Math.min(cap, spread));
    }
}
