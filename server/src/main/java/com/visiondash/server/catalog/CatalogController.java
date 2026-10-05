package com.visiondash.server.catalog;

import com.visiondash.server.settings.SettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hands the catalog to the frontend so the UI never keeps its own copy.
 *
 * The old dashboard hard-coded the vision list, the row order, and a duplicate of the
 * threshold table in TypeScript. The TypeScript copy had drifted - it still named a
 * vision type the backend had split in two months earlier, so those cells quietly scored
 * themselves against another vision type's thresholds. Serving it removes the second copy.
 */
@RestController
@RequestMapping("/api/catalog")
public class CatalogController {

    private final VisionCatalog catalog;
    private final SettingsService settings;

    public CatalogController(VisionCatalog catalog, SettingsService settings) {
        this.catalog = catalog;
        this.settings = settings;
    }

    public record CatalogResponse(
            List<String> lines,
            List<VisionTypeView> visionTypes,
            Map<String, VisionCatalog.Thresholds> thresholds,
            List<String> commonVisionKeys,
            int pollIntervalSeconds,
            /** How long a wholly idle line counts as planned downtime before it is a
             *  breakdown. The grid decides PD or BM from this. */
            int plannedDowntimeMaxMinutes
    ) {
    }

    public record VisionTypeView(
            String key,
            String displayName,
            String shortName,
            int gridOrder,
            String thresholdProfile,
            Integer productionRefPriority,
            String okJudgement,
            List<VisionType.Judgements.Defect> judgements,
            List<String> imageSets
    ) {
    }

    @GetMapping
    public CatalogResponse get() {
        List<VisionTypeView> types = catalog.gridOrdered().stream()
                .map(t -> new VisionTypeView(t.key(), t.displayName(), t.shortName(),
                        t.gridOrder(), t.thresholdProfile(),
                        t.productionRefPriority(), t.judgements().ok(), t.judgements().defect(),
                        List.copyOf(t.imageSetLabels())))
                .toList();

        // Live values, not the catalog defaults: an operator who tunes a threshold in
        // Settings must see the same number the server colours cells with.
        Map<String, VisionCatalog.Thresholds> thresholds = new LinkedHashMap<>();
        catalog.thresholdProfiles().forEach((profile, fallback) -> thresholds.put(profile,
                new VisionCatalog.Thresholds(
                        settings.getDouble("defect_rate_warning_pct_" + profile, fallback.warnPct()),
                        settings.getDouble("defect_rate_critical_pct_" + profile, fallback.critPct()))));

        return new CatalogResponse(catalog.lines(), types, thresholds,
                catalog.commonVisionKeys(),
                settings.getInt("dashboard_poll_interval_seconds", 5),
                settings.getInt("line_downtime_planned_max_minutes", 3));
    }
}
