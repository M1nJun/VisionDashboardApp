package com.visiondash.server.images;

import com.visiondash.server.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Serves a cached defect image, fetching it first if nobody has yet.
 *
 * This is the second half of the hybrid: the worker prefetches recent defects so opening
 * them is instant, and anything older is pulled here, the first time somebody actually
 * asks for it. Together they keep the disk to images people look at without ever telling
 * an operator "too old, gone".
 */
@RestController
@RequestMapping("/api/images")
public class ImageController {
    private static final Logger log = LoggerFactory.getLogger(ImageController.class);

    private final JdbcTemplate jdbc;
    private final ImageFetchService fetchService;
    private final SettingsService settings;

    public ImageController(JdbcTemplate jdbc, ImageFetchService fetchService, SettingsService settings) {
        this.jdbc = jdbc;
        this.fetchService = fetchService;
        this.settings = settings;
    }

    @GetMapping("/{id}")
    public ResponseEntity<Resource> get(@PathVariable long id) {
        Optional<String> ready = readyPath(id);

        if (ready.isEmpty()) {
            // Not cached yet. Pull it now rather than making the operator wait for the
            // next sweep - and only if nothing else already has it claimed.
            Optional<ImageFetchService.Pending> pending = fetchService.byId(id);
            if (pending.isPresent() && fetchService.claim(id)) {
                fetchService.fetch(pending.get());
                ready = readyPath(id);
            }
        }

        if (ready.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Path path = Paths.get(ready.get());
        // The stored path is written by this application, but a bad row must not be able
        // to hand out arbitrary files from the central PC.
        Path root = Paths.get(settings.getString("image_local_root", "D:\\VisionDashboardImages"));
        if (!path.normalize().startsWith(root.normalize())) {
            log.error("image {} points outside the cache root: {}", id, path);
            return ResponseEntity.notFound().build();
        }
        if (!Files.exists(path) || !Files.isReadable(path)) {
            // The file went missing under us - put the row back in the queue so the next
            // request can recover instead of failing forever.
            jdbc.update("UPDATE defect_images SET state = 'pending', local_path = NULL, "
                    + "next_attempt_at = NULL WHERE id = ?", id);
            return ResponseEntity.notFound().build();
        }

        // Drives the cache pruner: an image somebody keeps opening is not stale.
        jdbc.update("UPDATE defect_images SET last_viewed_at = NOW(3) WHERE id = ?", id);

        return ResponseEntity.ok()
                .contentType(contentType(path))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)))
                .body(new FileSystemResource(path));
    }

    private Optional<String> readyPath(long id) {
        List<String> rows = jdbc.query(
                "SELECT local_path FROM defect_images WHERE id = ? AND state = 'ready'",
                (rs, i) -> rs.getString(1), id);
        return rows.isEmpty() || rows.get(0) == null ? Optional.empty() : Optional.of(rows.get(0));
    }

    private MediaType contentType(Path path) {
        try {
            String probed = Files.probeContentType(path);
            if (probed != null) {
                return MediaType.parseMediaType(probed);
            }
        } catch (IOException ignored) {
            // Vision output is JPEG in practice; the fallback below is right often enough.
        }
        return MediaType.IMAGE_JPEG;
    }
}
