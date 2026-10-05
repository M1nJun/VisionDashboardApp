package com.visiondash.server.ai;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Alerts as episodes: one row from the window a fault appeared in until it is released.
 *
 * The screen used to say "4 windows in a row", which asks the reader to know how long a
 * window is and to multiply. An episode carries the time it started, so the screen can say
 * "open since 09-11 20:30" - which is what somebody actually wants to know, and stays
 * true if the report interval is ever changed.
 *
 * Two things live on the row that could not live anywhere else:
 *
 * The frozen baseline. Normal is measured from recent history, so an inspector that stays
 * broken teaches the measurement that broken is normal - C-1's C-NG expected 0.2 defects
 * per window before it failed and 121 a day later, which would have switched its own alert
 * off while the fault was still running. The healthy figures are copied onto the episode
 * when it opens and the comparison stays against those for as long as it lasts.
 *
 * And the confirmation. One window outside the band is what ordinary variation looks like;
 * two in a row is a fault. A first odd window opens an unconfirmed row that reaches
 * nothing - not the screen, not the severity - and is deleted if the next window disagrees.
 */
@Service
public class EpisodeStore {

    private final JdbcTemplate jdbc;

    public EpisodeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param confirmed false while a single odd window waits for a second opinion.
     * @param frozenRate   defects per cell this slot ran at before the fault - the
     *                     comparison every window of this episode is made against.
     * @param frozenSpread how far it normally wandered, measured the same way.
     */
    public record Episode(long id, String line, String visionKey, String judgement,
                          LocalDateTime startedAt, LocalDateTime lastFlaggedAt,
                          LocalDateTime resolvedAt, int clearWindows, int flagWindows,
                          boolean confirmed, double frozenRate, double frozenSpread,
                          long peakCount, double peakExpected) {

        public String key() {
            return AiFactBuilder.slotKey(line, visionKey, judgement);
        }
    }

    /** A stretch of time that was not this slot behaving normally. */
    public record Range(LocalDateTime from, LocalDateTime to) {
    }

    public boolean tableExists() {
        Integer found = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 'ai_episodes'
                """, Integer.class);
        return found != null && found > 0;
    }

    /** Every episode that has not been released, keyed by slot and judgement. */
    public Map<String, Episode> open() {
        Map<String, Episode> byKey = new HashMap<>();
        for (Episode e : jdbc.query("SELECT * FROM ai_episodes WHERE resolved_at IS NULL",
                EpisodeStore::map)) {
            byKey.put(e.key(), e);
        }
        return byKey;
    }

    /** Confirmed episodes, newest first, for the history strip and the guide's examples. */
    public List<Episode> recent(int limit) {
        return jdbc.query("""
                SELECT * FROM ai_episodes WHERE confirmed = 1
                ORDER BY started_at DESC LIMIT ?
                """, EpisodeStore::map, Math.max(1, Math.min(limit, 200)));
    }

    /**
     * Windows to leave out when measuring what normal looks like.
     *
     * Both the open episodes and the ones already released: a fault that ended yesterday
     * would otherwise sit in the seven days of history the spread is measured from, widen
     * the band, and leave the inspector harder to flag for a week afterwards.
     */
    public Map<String, List<Range>> exclusions(LocalDateTime since) {
        Map<String, List<Range>> byKey = new HashMap<>();
        jdbc.query("""
                SELECT line, vision_key, judgement, started_at, resolved_at
                FROM ai_episodes
                WHERE confirmed = 1 AND (resolved_at IS NULL OR resolved_at >= ?)
                """, rs -> {
            String key = AiFactBuilder.slotKey(rs.getString(1), rs.getString(2), rs.getString(3));
            Timestamp resolved = rs.getTimestamp(5);
            byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(new Range(
                    rs.getTimestamp(4).toLocalDateTime(),
                    resolved == null ? LocalDateTime.now().plusYears(1) : resolved.toLocalDateTime()));
        }, Timestamp.valueOf(since));
        return byKey;
    }

    public static boolean excluded(List<Range> ranges, LocalDateTime window) {
        if (ranges == null) {
            return false;
        }
        for (Range r : ranges) {
            if (!window.isBefore(r.from()) && window.isBefore(r.to())) {
                return true;
            }
        }
        return false;
    }

    /** First odd window: opens the row, confirmed only when the count is past arguing with. */
    public void start(String line, String visionKey, String judgement, LocalDateTime window,
                      boolean confirmed, double frozenRate, double frozenSpread,
                      long count, double expected) {
        jdbc.update("""
                INSERT INTO ai_episodes
                  (line, vision_key, judgement, started_at, last_flagged_at, confirmed,
                   frozen_rate, frozen_spread, peak_count, peak_expected)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE last_flagged_at = VALUES(last_flagged_at)
                """, line, visionKey, judgement, Timestamp.valueOf(window), Timestamp.valueOf(window),
                confirmed ? 1 : 0, frozenRate, frozenSpread, count, expected);
    }

    /**
     * Still abnormal: one more window on the counter, and the clear counter resets.
     *
     * Whether that is enough to confirm is the caller's call - it holds the rule, and it
     * also knows about the fast path, where a window far enough past the band is confirmed
     * on its own without waiting for the counter.
     */
    public void extend(long id, LocalDateTime window, long count, double expected,
                       boolean confirmed) {
        jdbc.update("""
                UPDATE ai_episodes
                   SET last_flagged_at = ?, clear_windows = 0,
                       flag_windows = flag_windows + 1, confirmed = ?,
                       peak_count = GREATEST(peak_count, ?),
                       peak_expected = IF(? > peak_count, ?, peak_expected)
                 WHERE id = ?
                """, Timestamp.valueOf(window), confirmed ? 1 : 0, count, count, expected, id);
    }

    /** Back inside the band for one window - not released yet. */
    public void clearOne(long id) {
        jdbc.update("UPDATE ai_episodes SET clear_windows = clear_windows + 1 WHERE id = ?", id);
    }

    public void resolve(long id, LocalDateTime window) {
        jdbc.update("UPDATE ai_episodes SET resolved_at = ?, clear_windows = clear_windows + 1 "
                + "WHERE id = ?", Timestamp.valueOf(window), id);
    }

    /** A lone odd window that the next one disagreed with. It never reached the screen. */
    public void discard(long id) {
        jdbc.update("DELETE FROM ai_episodes WHERE id = ? AND confirmed = 0", id);
    }

    public int purge(LocalDateTime before) {
        return jdbc.update("DELETE FROM ai_episodes WHERE resolved_at IS NOT NULL AND resolved_at < ?",
                Timestamp.valueOf(before));
    }

    private static Episode map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        Timestamp resolved = rs.getTimestamp("resolved_at");
        return new Episode(rs.getLong("id"), rs.getString("line"), rs.getString("vision_key"),
                rs.getString("judgement"), rs.getTimestamp("started_at").toLocalDateTime(),
                rs.getTimestamp("last_flagged_at").toLocalDateTime(),
                resolved == null ? null : resolved.toLocalDateTime(),
                rs.getInt("clear_windows"), rs.getInt("flag_windows"), rs.getBoolean("confirmed"),
                rs.getDouble("frozen_rate"), rs.getDouble("frozen_spread"),
                rs.getLong("peak_count"), rs.getDouble("peak_expected"));
    }
}
