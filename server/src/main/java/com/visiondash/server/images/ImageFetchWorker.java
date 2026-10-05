package com.visiondash.server.images;

import com.visiondash.server.settings.SettingsService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Keeps recent defect images flowing onto the central PC.
 *
 * Runs several copies at once on purpose. The previous fetcher was a single loop, so one
 * inspection PC that had gone quiet held up every other pending image behind it for as
 * long as its probe and copy timeouts lasted.
 */
@Component
public class ImageFetchWorker {
    private static final Logger log = LoggerFactory.getLogger(ImageFetchWorker.class);

    private static final int PARALLEL_COPIES = 6;
    private static final int BATCH = 60;

    private final ImageFetchService service;
    private final SettingsService settings;
    private final JdbcTemplate jdbc;
    private final ExecutorService pool = Executors.newFixedThreadPool(PARALLEL_COPIES, r -> {
        Thread t = new Thread(r, "image-fetch");
        t.setDaemon(true);
        return t;
    });

    public ImageFetchWorker(ImageFetchService service, SettingsService settings, JdbcTemplate jdbc) {
        this.service = service;
        this.settings = settings;
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.SECONDS)
    public void pump() {
        List<ImageFetchService.Pending> due = service.due(BATCH);
        if (due.isEmpty()) {
            return;
        }

        int fetched = 0;
        List<java.util.concurrent.Future<Boolean>> running = due.stream()
                .filter(p -> service.claim(p.id()))
                .map(p -> pool.submit(() -> service.fetch(p)))
                .toList();

        for (var future : running) {
            try {
                if (future.get(60, TimeUnit.SECONDS)) {
                    fetched++;
                }
            } catch (Exception e) {
                // The row keeps its lease and comes back round; nothing to undo here.
                log.warn("image fetch did not settle: {}", e.toString());
            }
        }

        if (fetched > 0 || running.size() > fetched) {
            log.info("images: {} fetched, {} still pending of {} due", fetched,
                    running.size() - fetched, due.size());
        }
    }

    /**
     * Deletes cached files nobody has opened for a long time.
     *
     * The cache is a convenience, not the record: the inspection PCs keep the originals
     * for three months, and anything deleted here is re-fetched on demand if it is still
     * on the share. Without this the central PC fills up with photos nobody ever viewed.
     */
    @Scheduled(cron = "0 20 3 * * *")
    public void prune() {
        int days = settings.getInt("image_cache_retention_days", 90);
        List<String> stale = jdbc.query("""
                SELECT local_path FROM defect_images
                WHERE state = 'ready' AND local_path IS NOT NULL
                  AND COALESCE(last_viewed_at, fetched_at) < DATE_SUB(NOW(3), INTERVAL ? DAY)
                LIMIT 5000
                """, (rs, i) -> rs.getString(1), days);

        String root = settings.getString("image_local_root", "D:\\VisionDashboardImages");
        int removed = 0;
        for (String path : stale) {
            // Only ever unlink inside the configured cache root, whatever the row claims.
            if (path == null || !Paths.get(path).normalize().startsWith(Paths.get(root).normalize())) {
                continue;
            }
            try {
                if (Files.deleteIfExists(Path.of(path))) {
                    removed++;
                }
            } catch (IOException e) {
                log.warn("could not delete cached image {}: {}", path, e.getMessage());
            }
        }

        if (!stale.isEmpty()) {
            // Back to pending rather than gone: the source may still be on the share, so a
            // later request can pull it again instead of showing a permanent blank.
            jdbc.update("""
                    UPDATE defect_images
                       SET state = 'pending', local_path = NULL, byte_size = NULL, fetched_at = NULL,
                           attempts = 0, next_attempt_at = NULL,
                           expires_at = DATE_ADD(NOW(3), INTERVAL ? HOUR)
                     WHERE state = 'ready' AND local_path IS NOT NULL
                       AND COALESCE(last_viewed_at, fetched_at) < DATE_SUB(NOW(3), INTERVAL ? DAY)
                    """, settings.getInt("image_retry_budget_hours", 6), days);
            log.info("image cache: pruned {} file(s) untouched for {} days", removed, days);
        }
    }

    @PreDestroy
    public void stop() {
        pool.shutdownNow();
    }
}
