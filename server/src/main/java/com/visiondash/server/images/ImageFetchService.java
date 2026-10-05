package com.visiondash.server.images;

import com.visiondash.server.catalog.VisionCatalog;
import com.visiondash.server.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Pulls defect images onto the central PC.
 *
 * Retries run against a <em>time budget</em>, not an attempt count. The count-based
 * version burned through its five attempts in under a minute, so an inspection PC that
 * rebooted - or a vision that wrote its CSV row a few seconds before the image - lost
 * those images permanently. Here a row keeps its place in the queue, backing off
 * exponentially, until {@code expires_at} passes.
 *
 * A configuration problem never produces {@code unavailable}: if no PC in the topology
 * hosts this inspector, the row waits and recovers by itself once the topology is fixed.
 * Only a source that could not be read within the budget is given up on.
 */
@Service
public class ImageFetchService {
    private static final Logger log = LoggerFactory.getLogger(ImageFetchService.class);

    /** How long a claimed row is hidden from other workers - comfortably longer than one
     *  probe plus one copy, so a slow fetch is never picked up twice. */
    private static final Duration CLAIM_LEASE = Duration.ofMinutes(2);
    private static final Duration FIRST_BACKOFF = Duration.ofSeconds(20);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(10);
    /** A misconfigured slot cannot be fixed by retrying soon, but must still recover on
     *  its own once someone corrects the topology. */
    private static final Duration CONFIG_BACKOFF = Duration.ofMinutes(15);

    private static final int PROBE_TIMEOUT_MS = 2000;
    private static final int COPY_TIMEOUT_MS = 20000;

    private final JdbcTemplate jdbc;
    private final VisionCatalog catalog;
    private final SettingsService settings;
    private final SmbImageSource source;

    public ImageFetchService(JdbcTemplate jdbc, VisionCatalog catalog, SettingsService settings,
                             SmbImageSource source) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.settings = settings;
        this.source = source;
    }

    public record Pending(long id, long occurrenceId, String line, String visionKey, String setLabel,
                          String kind, String sourcePath, int attempts, LocalDateTime expiresAt) {
    }

    /**
     * Rows due for a fetch, newest defect first.
     *
     * Only defects inside the prefetch window are pulled on a timer. Older ones stay
     * pending and are fetched when somebody actually opens them, which is the hybrid the
     * disk budget depends on - copying every image the fleet ever produced would fill the
     * central PC with photos nobody looks at.
     */
    public List<Pending> due(int limit) {
        int windowHours = settings.getInt("image_prefetch_window_hours", 24);
        return jdbc.query("""
                SELECT im.id, im.occurrence_id, o.line, o.vision_key, im.set_label, im.kind,
                       im.source_path, im.attempts, im.expires_at
                FROM defect_images im
                JOIN defect_occurrences o ON o.id = im.occurrence_id
                WHERE im.state = 'pending'
                  AND (im.next_attempt_at IS NULL OR im.next_attempt_at <= NOW(3))
                  AND o.received_at >= DATE_SUB(NOW(3), INTERVAL ? HOUR)
                ORDER BY o.received_at DESC, im.id
                LIMIT ?
                """,
                (rs, i) -> new Pending(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getInt(8),
                        rs.getTimestamp(9) == null ? null : rs.getTimestamp(9).toLocalDateTime()),
                windowHours, limit);
    }

    public Optional<Pending> byId(long imageId) {
        return jdbc.query("""
                SELECT im.id, im.occurrence_id, o.line, o.vision_key, im.set_label, im.kind,
                       im.source_path, im.attempts, im.expires_at
                FROM defect_images im
                JOIN defect_occurrences o ON o.id = im.occurrence_id
                WHERE im.id = ? AND im.state = 'pending'
                """,
                (rs, i) -> new Pending(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getInt(8),
                        rs.getTimestamp(9) == null ? null : rs.getTimestamp(9).toLocalDateTime()),
                imageId).stream().findFirst();
    }

    /** Takes a lease so two workers - or a worker and an on-demand request - cannot copy
     *  the same file at once. Returns false if somebody else got there first. */
    public boolean claim(long imageId) {
        return jdbc.update("""
                UPDATE defect_images
                   SET next_attempt_at = ?
                 WHERE id = ? AND state = 'pending'
                   AND (next_attempt_at IS NULL OR next_attempt_at <= NOW(3))
                """, Timestamp.valueOf(LocalDateTime.now().plus(CLAIM_LEASE)), imageId) == 1;
    }

    /** Copies one claimed row and records the outcome. Returns true when the file landed. */
    public boolean fetch(Pending pending) {
        Optional<String> hostIp = catalog.hostIp(pending.line(), pending.visionKey());
        if (hostIp.isEmpty()) {
            // Loud, because nothing retries its way out of this - but not fatal to the row.
            log.error("no PC in the topology hosts {}/{}; image {} will wait",
                    pending.line(), pending.visionKey(), pending.id());
            reschedule(pending.id(), CONFIG_BACKOFF,
                    "no PC hosts " + pending.line() + "/" + pending.visionKey(), false);
            return false;
        }

        Path destination = localPath(pending);
        SmbImageSource.Result result = source.copy(pending.sourcePath(), hostIp.get(), destination,
                PROBE_TIMEOUT_MS, COPY_TIMEOUT_MS);

        switch (result) {
            case SmbImageSource.Result.Copied copied -> {
                jdbc.update("""
                        UPDATE defect_images
                           SET state = 'ready', local_path = ?, byte_size = ?, fetched_at = NOW(3),
                               attempts = attempts + 1, last_error = NULL, next_attempt_at = NULL
                         WHERE id = ?
                        """, destination.toString(), copied.bytes(), pending.id());
                return true;
            }
            case SmbImageSource.Result.Permanent permanent -> {
                markUnavailable(pending.id(), permanent.reason());
                return false;
            }
            case SmbImageSource.Result.Transient transientFailure -> {
                if (pending.expiresAt() != null && LocalDateTime.now().isAfter(pending.expiresAt())) {
                    markUnavailable(pending.id(), "gave up after the retry budget: " + transientFailure.reason());
                } else {
                    reschedule(pending.id(), backoff(pending.attempts()), transientFailure.reason(), true);
                }
                return false;
            }
        }
    }

    /** Doubles from 20s up to 10 minutes: a PC that is briefly busy is retried quickly,
     *  one that is switched off stops being hammered. */
    private static Duration backoff(int attempts) {
        Duration delay = FIRST_BACKOFF.multipliedBy(1L << Math.min(attempts, 6));
        return delay.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay;
    }

    private void reschedule(long imageId, Duration delay, String reason, boolean countAttempt) {
        jdbc.update("""
                UPDATE defect_images
                   SET next_attempt_at = ?, last_error = ?, attempts = attempts + ?
                 WHERE id = ?
                """, Timestamp.valueOf(LocalDateTime.now().plus(delay)), truncate(reason),
                countAttempt ? 1 : 0, imageId);
    }

    private void markUnavailable(long imageId, String reason) {
        jdbc.update("""
                UPDATE defect_images
                   SET state = 'unavailable', last_error = ?, attempts = attempts + 1,
                       next_attempt_at = NULL
                 WHERE id = ?
                """, truncate(reason), imageId);
    }

    private Path localPath(Pending pending) {
        String root = settings.getString("image_local_root", "D:\\VisionDashboardImages");
        String extension = extensionOf(pending.sourcePath());
        String name = pending.occurrenceId() + "_" + pending.setLabel() + "_"
                + pending.kind().toLowerCase() + extension;
        return Paths.get(root, pending.line(), pending.visionKey(), name);
    }

    private static String extensionOf(String path) {
        int dot = path == null ? -1 : path.lastIndexOf('.');
        if (dot < 0 || dot < path.length() - 6) {
            return ".jpg";
        }
        return path.substring(dot);
    }

    private static String truncate(String value) {
        if (value == null) return null;
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
