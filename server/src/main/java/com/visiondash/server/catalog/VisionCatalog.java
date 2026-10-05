package com.visiondash.server.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The one place any component learns what a vision type is.
 *
 * Loaded and cross-checked at startup, and a failed check aborts startup on
 * purpose: every drift bug this replaces (an image fetcher keyed on a display
 * name that no longer existed, a frontend threshold lookup silently falling back
 * to another vision type's profile) was invisible precisely because the mismatch
 * was tolerated at runtime.
 */
@Component
public class VisionCatalog {
    private static final Logger log = LoggerFactory.getLogger(VisionCatalog.class);

    public record Thresholds(double warnPct, double critPct) {
    }

    /**
     * A headline rate on the dashboard header, declared in the catalog rather than the
     * frontend: which verdicts the floor is judged on is a business decision that changes
     * without a rebuild.
     *
     * Every source is one (vision type, judgement) pair, and the denominator is always
     * line output, so the rate reads as "verdicts per hundred cells produced".
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FleetMetric(String key, String label, List<Source> sources) {
        public record Source(String visionKey, String judgement) {
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CatalogFile(Map<String, Thresholds> thresholdProfiles,
                               List<FleetMetric> fleetMetrics,
                               List<String> commonVisionKeys,
                               List<VisionType> visionTypes) {
    }

    private final Map<String, VisionType> byKey = new LinkedHashMap<>();
    private final Map<String, Thresholds> thresholdProfiles;
    private final Topology topology;
    private final List<VisionType> gridOrdered;
    private final List<VisionType> productionRefOrder;
    private final List<FleetMetric> fleetMetrics;
    private final List<String> commonVisionKeys;

    public VisionCatalog(@Value("${visiondash.catalog.dir:}") String overrideDir) {
        ObjectMapper mapper = new ObjectMapper();
        CatalogFile catalog = read(mapper, overrideDir, "vision-catalog.json", CatalogFile.class);
        this.topology = read(mapper, overrideDir, "topology.json", Topology.class);
        this.thresholdProfiles = Map.copyOf(catalog.thresholdProfiles());

        for (VisionType type : catalog.visionTypes()) {
            if (byKey.put(type.key(), type) != null) {
                throw new IllegalStateException("duplicate vision key in catalog: " + type.key());
            }
        }
        this.gridOrdered = catalog.visionTypes().stream()
                .sorted(Comparator.comparingInt(VisionType::gridOrder))
                .toList();
        this.productionRefOrder = catalog.visionTypes().stream()
                .filter(v -> v.productionRefPriority() != null)
                .sorted(Comparator.comparingInt(VisionType::productionRefPriority))
                .toList();

        this.fleetMetrics = catalog.fleetMetrics() == null ? List.of() : List.copyOf(catalog.fleetMetrics());
        this.commonVisionKeys = catalog.commonVisionKeys() == null
                ? List.of() : List.copyOf(catalog.commonVisionKeys());

        validate();
        log.info("Catalog loaded: {} vision types, {} lines, {} PCs; production reference order {}",
                byKey.size(), topology.lines().size(), topology.pcs().size(),
                productionRefOrder.stream().map(VisionType::key).toList());
    }

    private <T> T read(ObjectMapper mapper, String overrideDir, String name, Class<T> type) {
        Resource resource = overrideDir == null || overrideDir.isBlank()
                ? new ClassPathResource("contracts/" + name)
                : new FileSystemResource(java.nio.file.Path.of(overrideDir, name));
        try (InputStream in = resource.getInputStream()) {
            return mapper.readValue(in, type);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read contract file " + resource, e);
        }
    }

    private void validate() {
        List<String> errors = new ArrayList<>();

        for (VisionType type : byKey.values()) {
            if (!thresholdProfiles.containsKey(type.thresholdProfile())) {
                errors.add(type.key() + ": unknown thresholdProfile " + type.thresholdProfile());
            }
            if (type.judgements().defect().stream().noneMatch(VisionType.Judgements.Defect::drivesColor)) {
                errors.add(type.key() + ": no defect judgement has drivesColor=true");
            }
            if (type.imageSetLabels().isEmpty()) {
                errors.add(type.key() + ": no image sets defined");
            }
        }

        int expectedOrders = byKey.size();
        for (int i = 1; i <= expectedOrders; i++) {
            final int order = i;
            if (gridOrdered.stream().noneMatch(v -> v.gridOrder() == order)) {
                errors.add("gridOrder " + order + " is missing (must be 1.." + expectedOrders + " with no gaps)");
            }
        }

        // A metric naming a judgement no vision type declares would quietly read 0.00%
        // forever, which looks like good news rather than a broken configuration.
        for (FleetMetric metric : fleetMetrics) {
            for (FleetMetric.Source source : metric.sources()) {
                VisionType type = byKey.get(source.visionKey());
                if (type == null) {
                    errors.add("fleetMetric " + metric.key() + ": unknown visionKey " + source.visionKey());
                } else if (type.judgements().defect().stream()
                        .noneMatch(d -> d.code().equals(source.judgement()))) {
                    errors.add("fleetMetric " + metric.key() + ": " + source.visionKey()
                            + " has no judgement " + source.judgement());
                }
            }
        }

        for (String key : commonVisionKeys) {
            if (!byKey.containsKey(key)) {
                errors.add("commonVisionKeys names " + key + ", which is not in the catalog");
            }
        }

        if (productionRefOrder.isEmpty()) {
            errors.add("no vision type has productionRefPriority - line production would be undefined");
        }

        Map<String, String> slotOwner = new LinkedHashMap<>();
        for (Topology.Pc pc : topology.pcs()) {
            if (!topology.lines().contains(pc.line())) {
                errors.add("pc " + pc.ip() + " is on unlisted line " + pc.line());
            }
            for (String key : pc.hosts()) {
                if (!byKey.containsKey(key)) {
                    errors.add("pc " + pc.ip() + " hosts " + key + ", which is not in the catalog");
                    continue;
                }
                String slot = pc.line() + "/" + key;
                String previous = slotOwner.put(slot, pc.ip());
                if (previous != null) {
                    errors.add("slot " + slot + " is claimed by both " + previous + " and " + pc.ip());
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new IllegalStateException("vision catalog / topology mismatch:\n  - " + String.join("\n  - ", errors));
        }
    }

    public Optional<VisionType> find(String visionKey) {
        return Optional.ofNullable(byKey.get(visionKey));
    }

    public VisionType require(String visionKey) {
        return find(visionKey).orElseThrow(() -> new UnknownVisionKeyException(visionKey));
    }

    /** Vision types top-to-bottom as the grid renders them. */
    public List<VisionType> gridOrdered() {
        return gridOrdered;
    }

    /** Which inspector stands in for a line's production, best candidate first. */
    public List<VisionType> productionRefOrder() {
        return productionRefOrder;
    }

    /** The headline rates shown above the grid, in the order the catalog lists them. */
    public List<FleetMetric> fleetMetrics() {
        return fleetMetrics;
    }

    /** The vision types the rank panel's "Common" filter covers. */
    public List<String> commonVisionKeys() {
        return commonVisionKeys;
    }

    public Thresholds thresholds(String profile) {
        return thresholdProfiles.get(profile);
    }

    public Map<String, Thresholds> thresholdProfiles() {
        return thresholdProfiles;
    }

    public List<String> lines() {
        return topology.lines();
    }

    /** The inspection PC that hosts this slot, for SMB image fetching. */
    public Optional<String> hostIp(String line, String visionKey) {
        return topology.pcs().stream()
                .filter(pc -> pc.line().equals(line) && pc.hosts().contains(visionKey))
                .map(Topology.Pc::ip)
                .findFirst();
    }

    /** agent_id is derived, never configured, so it cannot disagree with the slot it serves. */
    public static String agentId(String line, String visionKey) {
        return line + "_" + visionKey;
    }

    public static class UnknownVisionKeyException extends RuntimeException {
        public UnknownVisionKeyException(String visionKey) {
            super("unknown visionKey: " + visionKey);
        }
    }
}
