package com.visiondash.server.grid;

import com.visiondash.server.catalog.VisionCatalog;
import com.visiondash.server.catalog.VisionType;
import com.visiondash.server.settings.SettingsService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the whole grid in three queries, no matter how many inspectors there are.
 *
 * Everything read here comes from lot_counters, which the ingest path maintains at
 * one-row-per-physical-unit granularity. Nothing recounts raw events, so this can never
 * drift from what the trend chart shows.
 */
@Service
public class GridService {

    private final JdbcTemplate jdbc;
    private final VisionCatalog catalog;
    private final SettingsService settings;

    public GridService(JdbcTemplate jdbc, VisionCatalog catalog, SettingsService settings) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.settings = settings;
    }

    public GridDto build() {
        return build(null);
    }

    /**
     * @param windowMinutes null (or non-positive) for the whole lot. Otherwise the cells
     *                      report only what happened in that window, read from the
     *                      one-minute rollups rather than the lot counters.
     *
     * Line output and the totals stay on the lot in either case. They answer "how is this
     * lot going", which a five-minute slice cannot, and the window control says so.
     */
    public GridDto build(Integer windowMinutes) {
        Map<String, AgentRow> byAgent = loadAgents();
        Map<String, Map<String, Long>> judgements = loadJudgementCounts();
        Map<String, Integer> alarms = loadAlarmCounts();

        Integer window = windowMinutes != null && windowMinutes > 0 ? windowMinutes : null;
        Map<String, long[]> windowTotals = window == null ? Map.of() : loadWindowTotals(window);
        Map<String, Map<String, Long>> windowJudgements =
                window == null ? Map.of() : loadWindowJudgements(window);

        int offlineAfter = settings.getInt("agent_offline_threshold_seconds", 10);
        int idleAfter = settings.getInt("agent_idle_threshold_seconds", 300);
        Instant now = Instant.now();

        Map<String, GridDto.Cell> bySlot = new LinkedHashMap<>();
        List<GridDto.Cell> cells = new ArrayList<>();
        for (String line : catalog.lines()) {
            for (VisionType type : catalog.gridOrdered()) {
                String agentId = VisionCatalog.agentId(line, type.key());
                GridDto.Cell cell = buildCell(line, type, byAgent.get(agentId),
                        window == null ? judgements.getOrDefault(agentId, Map.of())
                                       : windowJudgements.getOrDefault(agentId, Map.of()),
                        alarms.get(agentId), now, offlineAfter, idleAfter,
                        window == null ? null : windowTotals.getOrDefault(agentId, new long[]{0, 0}));
                cells.add(cell);
                bySlot.put(line + "/" + type.key(), cell);
            }
        }

        long perLineTarget = settings.getInt("daily_target_cells_per_line", 14286);
        List<GridDto.LineProduction> lines = catalog.lines().stream()
                .map(line -> lineProduction(line, bySlot, byAgent, perLineTarget))
                .toList();

        return new GridDto(cells, lines, totals(cells, lines, judgements, byAgent), window);
    }

    public GridDto.Cell cell(String line, String visionKey) {
        return build(null).cells().stream()
                .filter(c -> c.line().equals(line) && c.visionKey().equals(visionKey))
                .findFirst()
                .orElse(null);
    }

    private GridDto.Cell buildCell(String line, VisionType type, AgentRow row,
                                   Map<String, Long> judgementCounts, Integer alarmCount,
                                   Instant now, int offlineAfter, int idleAfter,
                                   long[] windowCounts) {
        boolean isProductionRef = catalog.productionRefOrder().stream()
                .findFirst().map(v -> v.key().equals(type.key())).orElse(false);

        if (row == null) {
            return new GridDto.Cell(line, type.key(), type.displayName(), type.shortName(),
                    "NOT_DEPLOYED", "GREY",
                    null, null, null, null, null, null, List.of(), false,
                    null, null, null, isProductionRef);
        }

        String status;
        if (row.lastHeartbeatAt == null || secondsSince(row.lastHeartbeatAt, now) > offlineAfter) {
            status = "OFFLINE";
        } else if (row.lastEventAt == null || secondsSince(row.lastEventAt, now) > idleAfter) {
            status = "IDLE";
        } else {
            status = "RUNNING";
        }

        long inspected;
        long defects;
        if (windowCounts == null) {
            inspected = row.inspected == null ? 0 : row.inspected;
            defects = row.defectUnits == null ? 0 : row.defectUnits;
        } else {
            inspected = windowCounts[0];
            defects = windowCounts[1];
        }
        boolean windowEmpty = windowCounts != null && inspected == 0;
        Double defectRate = inspected > 0 ? 100.0 * defects / inspected : null;

        List<GridDto.JudgementCount> judgements = new ArrayList<>();
        for (VisionType.Judgements.Defect defect : type.judgements().defect()) {
            long count = judgementCounts.getOrDefault(defect.code(), 0L);
            Double rate = inspected > 0 ? 100.0 * count / inspected : null;
            judgements.add(new GridDto.JudgementCount(defect.code(), count, rate, defect.drivesColor()));
        }

        // One colour signal per cell. Reading DLNG/C-NG into it as well used to put several
        // meanings on one card and made the grid unreadable from across the floor.
        //
        // findFirst() before map(), not after: an inspector that has registered but not yet
        // produced anything has a null rate, and Stream.findFirst() throws on a null element.
        // A single freshly deployed agent was enough to fail the whole grid with a 500.
        Double colourRate = judgements.stream()
                .filter(GridDto.JudgementCount::drivesColor)
                .findFirst()
                .map(GridDto.JudgementCount::ratePct)
                .orElse(null);
        // Nothing produced in the window is grey, not green: there is no rate to colour by.
        String colour = status.equals("OFFLINE") || windowEmpty
                ? "GREY" : level(colourRate, type.thresholdProfile());

        return new GridDto.Cell(line, type.key(), type.displayName(), type.shortName(),
                status, colour, row.agentId,
                row.lotId, row.modelId, inspected, defects, defectRate, judgements, windowEmpty,
                alarmCount == null ? 0 : alarmCount, row.lastEventAt, row.lastHeartbeatAt, isProductionRef);
    }

    /** Settings hold the live thresholds; the catalog seeded them and remains the default. */
    private String level(Double ratePct, String profile) {
        if (ratePct == null) return "GREEN";
        VisionCatalog.Thresholds fallback = catalog.thresholds(profile);
        double warn = settings.getDouble("defect_rate_warning_pct_" + profile, fallback.warnPct());
        double crit = settings.getDouble("defect_rate_critical_pct_" + profile, fallback.critPct());
        if (ratePct >= crit) return "RED";
        if (ratePct >= warn) return "YELLOW";
        return "GREEN";
    }

    private GridDto.LineProduction lineProduction(String line, Map<String, GridDto.Cell> bySlot,
                                                  Map<String, AgentRow> byAgent, long perLineTarget) {
        boolean preferred = true;
        for (VisionType candidate : catalog.productionRefOrder()) {
            GridDto.Cell cell = bySlot.get(line + "/" + candidate.key());
            // An offline or undeployed reference has no current reading to stand on, so the
            // next candidate takes over - flagged as an estimate, since an upstream station
            // sees every unit the reference would later reject.
            if (cell != null && cell.inspectedCount() != null
                    && !cell.status().equals("OFFLINE") && !cell.status().equals("NOT_DEPLOYED")) {
                // Read the lot counter directly, never the cell: the cell may be showing a
                // window, and a line's output is a lot figure. Taking it from the cell made
                // narrowing the grid to five minutes silently rewrite line output and the
                // target progress bar underneath it.
                AgentRow row = byAgent.get(VisionCatalog.agentId(line, candidate.key()));
                Long produced = row == null ? null : row.inspected;
                return new GridDto.LineProduction(line, produced == null ? 0 : produced,
                        candidate.key(), !preferred, perLineTarget);
            }
            preferred = false;
        }
        return new GridDto.LineProduction(line, null, null, false, perLineTarget);
    }

    /**
     * Always the whole lot, even when the cells above are showing a window.
     *
     * The defect figures are read from the lot maps rather than summed off the cells for
     * exactly that reason - taking them from the cells would silently retitle the headline
     * rates as "in the last five minutes" whenever somebody touched the window control.
     */
    private GridDto.Totals totals(List<GridDto.Cell> cells, List<GridDto.LineProduction> lines,
                                 Map<String, Map<String, Long>> judgementCounts,
                                 Map<String, AgentRow> byAgent) {
        long production = lines.stream()
                .filter(l -> l.production() != null)
                .mapToLong(GridDto.LineProduction::production)
                .sum();
        // Numerator spans every inspector, denominator is what the line actually produced:
        // "defect verdicts per hundred cells made", which is the number the floor cares about.
        long defects = byAgent.values().stream()
                .filter(r -> r.defectUnits != null)
                .mapToLong(r -> r.defectUnits)
                .sum();

        // The headline rates are narrower than that total on purpose: the floor is judged
        // on NG from the Example B and Example C inspectors, not on every verdict every station
        // can raise. Which ones count is declared in the catalog, not decided here.
        List<GridDto.FleetRate> rates = new ArrayList<>();
        for (VisionCatalog.FleetMetric metric : catalog.fleetMetrics()) {
            long units = 0;
            for (VisionCatalog.FleetMetric.Source source : metric.sources()) {
                for (String line : catalog.lines()) {
                    units += judgementCounts
                            .getOrDefault(VisionCatalog.agentId(line, source.visionKey()), Map.of())
                            .getOrDefault(source.judgement(), 0L);
                }
            }
            rates.add(new GridDto.FleetRate(metric.key(), metric.label(), units,
                    production > 0 ? 100.0 * units / production : null));
        }

        int running = 0, idle = 0, offline = 0, notDeployed = 0;
        for (GridDto.Cell cell : cells) {
            switch (cell.status()) {
                case "RUNNING" -> running++;
                case "IDLE" -> idle++;
                case "OFFLINE" -> offline++;
                default -> notDeployed++;
            }
        }

        return new GridDto.Totals(production, defects,
                production > 0 ? 100.0 * defects / production : null,
                rates, settings.getInt("daily_target_cells", 100000),
                running, idle, offline, notDeployed);
    }

    // ---- loaders -----------------------------------------------------------

    private Map<String, AgentRow> loadAgents() {
        Map<String, AgentRow> byAgent = new HashMap<>();
        jdbc.query("""
                SELECT a.agent_id, a.line, a.vision_key, a.current_model_id,
                       a.last_event_at, a.last_heartbeat_at,
                       c.lot_id, c.inspected_count, c.defect_unit_count
                FROM agents a
                LEFT JOIN lot_counters c ON c.agent_id = a.agent_id
                """, rs -> {
            AgentRow row = new AgentRow();
            row.agentId = rs.getString("agent_id");
            row.modelId = rs.getString("current_model_id");
            row.lotId = rs.getString("lot_id");
            row.lastEventAt = instant(rs.getTimestamp("last_event_at"));
            row.lastHeartbeatAt = instant(rs.getTimestamp("last_heartbeat_at"));
            row.inspected = nullable(rs.getLong("inspected_count"), rs.wasNull());
            row.defectUnits = nullable(rs.getLong("defect_unit_count"), rs.wasNull());
            byAgent.put(row.agentId, row);
        });
        return byAgent;
    }

    private Map<String, Map<String, Long>> loadJudgementCounts() {
        Map<String, Map<String, Long>> byAgent = new HashMap<>();
        jdbc.query("SELECT agent_id, judgement, unit_count FROM lot_counter_judgement", rs -> {
            byAgent.computeIfAbsent(rs.getString(1), k -> new HashMap<>())
                    .put(rs.getString(2), rs.getLong(3));
        });
        return byAgent;
    }

    /**
     * What each agent counted inside the window, from the one-minute rollups.
     *
     * Scoped to the agent's current lot as well as the time: a window that reaches back
     * past a lot change must not fold the previous lot's units into the one on screen.
     */
    private Map<String, long[]> loadWindowTotals(int windowMinutes) {
        Map<String, long[]> byAgent = new HashMap<>();
        jdbc.query("""
                SELECT r.agent_id, SUM(r.inspected_count), SUM(r.defect_unit_count)
                FROM vision_rollup r
                JOIN lot_counters c ON c.agent_id = r.agent_id AND c.lot_id = r.lot_id
                WHERE r.bucket_start >= ?
                GROUP BY r.agent_id
                """, rs -> {
            byAgent.put(rs.getString(1), new long[]{rs.getLong(2), rs.getLong(3)});
        }, Timestamp.valueOf(windowStart(windowMinutes)));
        return byAgent;
    }

    private Map<String, Map<String, Long>> loadWindowJudgements(int windowMinutes) {
        Map<String, Map<String, Long>> byAgent = new HashMap<>();
        jdbc.query("""
                SELECT j.agent_id, j.judgement, SUM(j.unit_count)
                FROM vision_rollup_judgement j
                JOIN lot_counters c ON c.agent_id = j.agent_id AND c.lot_id = j.lot_id
                WHERE j.bucket_start >= ?
                GROUP BY j.agent_id, j.judgement
                """, rs -> {
            byAgent.computeIfAbsent(rs.getString(1), k -> new HashMap<>())
                    .put(rs.getString(2), rs.getLong(3));
        }, Timestamp.valueOf(windowStart(windowMinutes)));
        return byAgent;
    }

    /** Rounded down to the minute, matching how the rollups are bucketed - asking for a
     *  boundary the data cannot land on would drop the newest bucket at random. */
    private static LocalDateTime windowStart(int windowMinutes) {
        return LocalDateTime.now().minusMinutes(windowMinutes).withSecond(0).withNano(0);
    }

    /** Alarms since the current lot started, so the cell's badge and the detail page's
     *  alarm list answer the same question. */
    private Map<String, Integer> loadAlarmCounts() {
        Map<String, Integer> byAgent = new HashMap<>();
        jdbc.query("""
                SELECT al.agent_id, COUNT(*) AS n
                FROM alarms al
                JOIN lot_counters c ON c.agent_id = al.agent_id
                WHERE c.lot_started_at IS NOT NULL AND al.alarm_at >= c.lot_started_at
                GROUP BY al.agent_id
                """, rs -> {
            byAgent.put(rs.getString(1), rs.getInt(2));
        });
        return byAgent;
    }

    private static Long nullable(long value, boolean wasNull) {
        return wasNull ? null : value;
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static long secondsSince(Instant then, Instant now) {
        return Duration.between(then, now).getSeconds();
    }

    private static final class AgentRow {
        String agentId;
        String modelId;
        String lotId;
        Instant lastEventAt;
        Instant lastHeartbeatAt;
        Long inspected;
        Long defectUnits;
    }
}
