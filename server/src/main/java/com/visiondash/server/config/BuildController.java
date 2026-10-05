package com.visiondash.server.config;

import com.visiondash.server.catalog.VisionCatalog;
import com.visiondash.server.catalog.VisionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.StringJoiner;

/**
 * A fingerprint of what this server is serving, so an open dashboard can tell that it
 * has been replaced underneath itself.
 *
 * The frontend reads the catalog exactly once, when the page loads. A dashboard left up
 * on the wall across a deployment therefore kept drawing the previous topology - two
 * lines that no longer exist - while polling the new server for grid data, and read
 * fields the new API had stopped sending. Nothing errored; the screen was simply wrong,
 * and stayed wrong until somebody thought to reload a page nobody was sitting at.
 *
 * So the fingerprint covers both halves of that: the built frontend, and every piece of
 * catalog the page caches at mount. When it changes, the page reloads itself.
 */
@RestController
@RequestMapping("/api/build")
public class BuildController {

    private static final Logger log = LoggerFactory.getLogger(BuildController.class);

    public record BuildInfo(String id) {
    }

    private final BuildInfo info;

    public BuildController(VisionCatalog catalog) {
        this.info = new BuildInfo(fingerprint(catalog));
        log.info("Build fingerprint {} - dashboards reload themselves when this changes", info.id());
    }

    @GetMapping
    public BuildInfo get() {
        return info;
    }

    private static String fingerprint(VisionCatalog catalog) {
        StringJoiner parts = new StringJoiner("|");
        parts.add(indexHtmlHash());
        parts.add(catalog.lines().toString());
        for (VisionType type : catalog.gridOrdered()) {
            parts.add(type.key() + ":" + type.shortName() + ":" + type.gridOrder()
                    + ":" + type.judgements().defect());
        }
        return sha256(parts.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 12);
    }

    /** Missing index.html is not fatal here - the server still serves its API, and a
     *  constant stands in so the frontend simply never asks for a reload. */
    private static String indexHtmlHash() {
        try (InputStream in = new ClassPathResource("/static/index.html").getInputStream()) {
            return sha256(in.readAllBytes());
        } catch (IOException e) {
            log.warn("no bundled frontend to fingerprint: {}", e.getMessage());
            return "no-frontend";
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16));
                hex.append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
