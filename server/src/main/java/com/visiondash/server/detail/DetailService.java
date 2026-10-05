package com.visiondash.server.detail;

import com.visiondash.server.grid.GridDto;
import com.visiondash.server.grid.GridService;
import com.visiondash.server.settings.SettingsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DetailService {
    private static final int TOP_DEFECT_LIMIT = 100;
    /** Newest first across every judgement. The table filters by judgement, defect name
     *  and time on the client, so the shorter windows are always complete and only a very
     *  busy whole-lot view can reach this - which the row count then says out loud. */
    private static final int EVENT_LIMIT = 500;
    private static final int ALARM_LIMIT = 50;

    private final JdbcTemplate jdbc;
    private final GridService grid;
    private final SettingsService settings;
    private final String contextPath;

    public DetailService(JdbcTemplate jdbc, GridService grid, SettingsService settings,
                         @Value("${server.servlet.context-path:}") String contextPath) {
        this.jdbc = jdbc;
        this.grid = grid;
        this.settings = settings;
        this.contextPath = contextPath;
    }

    public DetailDto build(String line, String visionKey) {
        GridDto.Cell cell = grid.cell(line, visionKey);
        if (cell == null) {
            throw new UnknownSlotException(line, visionKey);
        }
        if (cell.agentId() == null || cell.lotId() == null) {
            // Nothing has reported in, or nothing has run since deployment - the cell alone
            // tells the whole story.
            return new DetailDto(cell, List.of(), List.of(), 0, List.of(), List.of());
        }

        String agentId = cell.agentId();
        String lotId = cell.lotId();
        return new DetailDto(cell,
                topDefects(agentId, lotId),
                events(agentId, lotId),
                eventTotal(agentId, lotId),
                alarms(agentId),
                trend(agentId, lotId));
    }

    /** One NG in full, for the viewer. Null when the id does not belong to this slot. */
    public DetailDto.Occurrence occurrence(String line, String visionKey, long occurrenceId) {
        GridDto.Cell cell = grid.cell(line, visionKey);
        if (cell == null) {
            throw new UnknownSlotException(line, visionKey);
        }
        if (cell.agentId() == null) {
            return null;
        }

        List<DetailDto.Occurrence> found = jdbc.query("""
                SELECT id, judgement, item_summary, cell_id, unit_seq, occurred_at
                FROM defect_occurrences
                WHERE id = ? AND agent_id = ?
                """,
                (rs, i) -> new DetailDto.Occurrence(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getLong(5), instant(rs.getTimestamp(6)),
                        List.of(), List.of()),
                occurrenceId, cell.agentId());
        if (found.isEmpty()) {
            return null;
        }

        DetailDto.Occurrence bare = found.get(0);
        List<Long> ids = List.of(bare.id());
        return new DetailDto.Occurrence(bare.id(), bare.judgement(), bare.label(), bare.cellId(),
                bare.unitSeq(), bare.occurredAt(),
                loadItems(ids).getOrDefault(bare.id(), List.of()),
                loadImages(ids).getOrDefault(bare.id(), List.of()));
    }

    /**
     * One row per physical unit means grouping is a plain GROUP BY on a column that was
     * written once at ingest. The previous schema stored one row per failed item and had
     * to re-merge them with GROUP_CONCAT on every poll, which both cost a join and
     * silently truncated at group_concat_max_len.
     */
    private List<DetailDto.DefectGroup> topDefects(String agentId, String lotId) {
        return jdbc.query("""
                SELECT item_summary, judgement, COUNT(*) AS c
                FROM defect_occurrences
                WHERE agent_id = ? AND lot_id = ?
                GROUP BY item_summary, judgement
                ORDER BY c DESC
                LIMIT ?
                """,
                (rs, i) -> new DetailDto.DefectGroup(rs.getString(1), rs.getString(2), rs.getLong(3)),
                agentId, lotId, TOP_DEFECT_LIMIT);
    }

    /**
     * Every NG in the lot, newest first - one row per physical unit.
     *
     * Deliberately not partitioned per judgement any more. The table is the operator's
     * way through the lot and it filters for itself, so handing it a pre-thinned slice
     * would mean a filter could show fewer rows than the lot actually contains.
     */
    private List<DetailDto.Event> events(String agentId, String lotId) {
        return jdbc.query("""
                SELECT id, occurred_at, cell_id, unit_seq, judgement, item_summary
                FROM defect_occurrences
                WHERE agent_id = ? AND lot_id = ?
                ORDER BY occurred_at DESC, id DESC
                LIMIT ?
                """,
                (rs, i) -> new DetailDto.Event(rs.getLong(1), instant(rs.getTimestamp(2)),
                        rs.getString(3), rs.getLong(4), rs.getString(5), rs.getString(6)),
                agentId, lotId, EVENT_LIMIT);
    }

    private long eventTotal(String agentId, String lotId) {
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM defect_occurrences WHERE agent_id = ? AND lot_id = ?",
                Long.class, agentId, lotId);
        return total == null ? 0 : total;
    }

    private Map<Long, List<DetailDto.Item>> loadItems(List<Long> occurrenceIds) {
        Map<Long, List<DetailDto.Item>> byOccurrence = new HashMap<>();
        jdbc.query("SELECT occurrence_id, item_name, raw_value, value_num, side FROM defect_items "
                        + "WHERE occurrence_id IN (" + placeholders(occurrenceIds) + ") ORDER BY seq",
                rs -> {
                    double value = rs.getDouble(4);
                    byOccurrence.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
                            .add(new DetailDto.Item(rs.getString(2), rs.getString(3),
                                    rs.wasNull() ? null : value, emptyToNull(rs.getString(5))));
                }, occurrenceIds.toArray());
        return byOccurrence;
    }

    private Map<Long, List<DetailDto.Image>> loadImages(List<Long> occurrenceIds) {
        Map<Long, List<DetailDto.Image>> byOccurrence = new HashMap<>();
        jdbc.query("SELECT id, occurrence_id, set_label, kind, state FROM defect_images "
                        + "WHERE occurrence_id IN (" + placeholders(occurrenceIds) + ") ORDER BY set_label, kind",
                rs -> {
                    long id = rs.getLong(1);
                    String state = rs.getString(5);
                    byOccurrence.computeIfAbsent(rs.getLong(2), k -> new ArrayList<>())
                            // A pending image still gets a URL - requesting it is what
                            // triggers the on-demand half of the hybrid fetch. Only an
                            // image the fetcher gave up on has nothing to offer.
                            .add(new DetailDto.Image(id, rs.getString(3), rs.getString(4), state,
                                    "unavailable".equals(state) ? null : contextPath + "/api/images/" + id));
                }, occurrenceIds.toArray());
        return byOccurrence;
    }

    /** Scoped to the current lot, matching the count on the grid cell. */
    private List<DetailDto.Alarm> alarms(String agentId) {
        return jdbc.query("""
                SELECT a.alarm_code, a.alarm_name, a.alarm_detail, a.alarm_at
                FROM alarms a
                JOIN lot_counters c ON c.agent_id = a.agent_id
                WHERE a.agent_id = ? AND c.lot_started_at IS NOT NULL AND a.alarm_at >= c.lot_started_at
                ORDER BY a.alarm_at DESC
                LIMIT ?
                """,
                (rs, i) -> new DetailDto.Alarm(rs.getString(1), rs.getString(2), rs.getString(3),
                        instant(rs.getTimestamp(4))),
                agentId, ALARM_LIMIT);
    }

    /**
     * Read from the rollup the ingest path writes alongside the counters, so the chart and
     * the grid are two views of one number. The old chart counted raw event rows, which a
     * unit failing several checks inflated - the same production looked worse on the chart
     * than on the card.
     */
    private List<DetailDto.TrendPoint> trend(String agentId, String lotId) {
        long bucketSeconds = settings.getInt("trend_bucket_minutes", 30) * 60L;

        Map<Instant, long[]> totals = new LinkedHashMap<>();
        jdbc.query("""
                SELECT FROM_UNIXTIME(FLOOR(UNIX_TIMESTAMP(bucket_start) / ?) * ?) AS b,
                       SUM(inspected_count), SUM(defect_unit_count)
                FROM vision_rollup
                WHERE agent_id = ? AND lot_id = ?
                GROUP BY b ORDER BY b
                """,
                rs -> {
                    totals.put(instant(rs.getTimestamp(1)), new long[]{rs.getLong(2), rs.getLong(3)});
                }, bucketSeconds, bucketSeconds, agentId, lotId);

        Map<Instant, Map<String, Long>> judgements = new HashMap<>();
        jdbc.query("""
                SELECT FROM_UNIXTIME(FLOOR(UNIX_TIMESTAMP(bucket_start) / ?) * ?) AS b,
                       judgement, SUM(unit_count)
                FROM vision_rollup_judgement
                WHERE agent_id = ? AND lot_id = ?
                GROUP BY b, judgement
                """,
                rs -> {
                    judgements.computeIfAbsent(instant(rs.getTimestamp(1)), k -> new LinkedHashMap<>())
                            .put(rs.getString(2), rs.getLong(3));
                }, bucketSeconds, bucketSeconds, agentId, lotId);

        List<DetailDto.TrendPoint> points = new ArrayList<>();
        totals.forEach((bucket, counts) -> {
            long inspected = counts[0];
            Map<String, Long> byJudgement = judgements.getOrDefault(bucket, Map.of());
            Map<String, Double> rates = new LinkedHashMap<>();
            byJudgement.forEach((code, count) ->
                    rates.put(code, inspected > 0 ? 100.0 * count / inspected : 0.0));
            points.add(new DetailDto.TrendPoint(bucket, inspected, counts[1],
                    inspected > 0 ? 100.0 * counts[1] / inspected : null, byJudgement, rates));
        });
        return points;
    }

    private static String placeholders(List<Long> ids) {
        return String.join(",", ids.stream().map(id -> "?").toList());
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    public static class UnknownSlotException extends RuntimeException {
        public UnknownSlotException(String line, String visionKey) {
            super("no such slot: " + line + "/" + visionKey);
        }
    }
}
