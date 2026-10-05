package com.visiondash.server.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * One entry of contracts/vision-catalog.json, narrowed to what the server needs.
 *
 * The catalog file also carries CSV parsing rules, which only the deployer and the
 * agent read - unknown properties are ignored here rather than split into a second
 * file, because a second file is exactly how the old system ended up with five
 * spellings of the same vision type.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VisionType(
        String key,
        String displayName,
        /** Fixed-width code the dashboard prints in narrow columns ("EXB-C"). Supplied
         *  here rather than abbreviated in the frontend, because a truncated name is
         *  unreadable and a frontend-side abbreviation is a second place to get it wrong. */
        String shortName,
        int gridOrder,
        String thresholdProfile,
        Integer productionRefPriority,
        Judgements judgements,
        Source source) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Judgements(String ok, List<Defect> defect) {
        /**
         * warnPct/critPct are reference lines for this judgement's own trend, and are
         * null for the judgement that drives cell colour - that one is scored against
         * the vision type's thresholdProfile, which an operator can tune in Settings.
         */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Defect(String code, boolean drivesColor, Double warnPct, Double critPct) {
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Source(Images images) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Images(Map<String, Object> sets) {
        }
    }

    public boolean isOk(String judgement) {
        return judgements.ok().equalsIgnoreCase(judgement);
    }

    public Optional<Judgements.Defect> defectFor(String judgement) {
        return judgements.defect().stream()
                .filter(d -> d.code().equalsIgnoreCase(judgement))
                .findFirst();
    }

    /** The single judgement whose rate drives cell colour - NG for every current type. */
    public String colourJudgement() {
        return judgements.defect().stream()
                .filter(Judgements.Defect::drivesColor)
                .map(Judgements.Defect::code)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(key + " has no colour-driving judgement"));
    }

    public List<String> defectCodes() {
        return judgements.defect().stream().map(Judgements.Defect::code).toList();
    }

    /** Valid values for an incoming image's "set" field (LOWER/UPPER, LEFT/RIGHT, ...). */
    public Set<String> imageSetLabels() {
        return source.images().sets().keySet();
    }
}
