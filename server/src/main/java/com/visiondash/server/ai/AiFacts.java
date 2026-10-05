package com.visiondash.server.ai;

import java.time.Instant;
import java.util.List;

/**
 * Everything the report is allowed to say, worked out in SQL and arithmetic before any
 * model is involved.
 *
 * The division of labour this whole feature rests on: this record holds the facts, and the
 * model supplies only the sentences. Nothing downstream asks the model to count, divide,
 * compare or decide - it is handed finished numbers and finished verdicts and writes them
 * up. A 12B model asked to compute a rate change from raw rows will sometimes get it
 * right, and "sometimes" is not a property a production report can have.
 *
 * Everything here is counted in DEFECTS rather than percentages. "at this volume, usually 0-26; this window, 64" is the same judgement a p-value and a confidence bound were making,
 * in the only unit the floor works in. The statistics did not get simpler - the reporting
 * did.
 */
public record AiFacts(
        Instant windowStart,
        Instant windowEnd,
        int windowMinutes,
        /** Hours of history the level was measured from, for the screen to state. */
        int baselineHours,
        /** Days of history the spread was measured from. */
        int spreadDays,
        Fleet fleet,
        List<LineFact> lines,
        /** Only what cleared the bar, worst first. Quiet slots are absent. */
        List<Finding> findings,
        /** Cells the grid is showing red for. Carried so the map can mark them; they raise
         *  nothing on their own - almost all of them are a single defect on a thin window. */
        List<ThresholdMark> thresholds,
        List<AlarmFact> alarms,
        List<StatusFact> statuses,
        /** The rule, so the screen and the guide can state it rather than imply it. */
        Rule rule
) {

    public record Fleet(
            long produced,
            int activeMinutes,
            long inspectedAllStations,
            long defectUnitsAllStations,
            List<Rate> rates
    ) {
    }

    /** A headline rate as the catalog defines it, e.g. NG across Example B and Example C. */
    public record Rate(String key, String label, long units, Double ratePct) {
    }

    public record LineFact(
            String line,
            Long produced,
            String referenceVisionKey,
            int activeMinutes,
            int stoppedMinutes,
            boolean lotChanged
    ) {
    }

    /**
     * One inspector, one judgement, one verdict.
     *
     * @param verdict      SPIKE (an open alert), ELEVATED (outside the band
     *                     but not yet an alert), INSUFFICIENT (too little
     *                     production to judge a rate, counts only).
     * @param expected     what this much production normally yields, in defects.
     * @param normalLow    bottom of the normal band, in defects.
     * @param normalHigh   top of it. The one number the whole verdict turns on.
     * @param spread       how far this slot normally wanders: 1.0 is chance, Example B DLNG
     *                     measures 2.5-5. The band is this wide because the inspector is.
     * @param ratePct      100k/n, for the map's tooltip. Null on an INSUFFICIENT finding,
     *                     and null in the sense of "must not be shown": a rate off a
     *                     handful of cells is noise wearing a number.
     * @param episodeStart when this alert began. The screen prints the time rather than a
     *                     count of windows - nobody thinks in report intervals.
     * @param isNew        true in the window the alert was confirmed, false while it runs
     *                     on. Lets the header say "1 new, 2 continuing".
     * @param frozen       true while the comparison is against the inspector as it was
     *                     before this fault, rather than against its recent history.
     */
    public record Finding(
            String line,
            String visionKey,
            String displayName,
            String judgement,
            String verdict,
            long inspected,
            long count,
            Double expected,
            Double normalLow,
            Double normalHigh,
            Double spread,
            Double ratePct,
            Double baselinePct,
            int activeMinutes,
            Instant episodeStart,
            boolean isNew,
            boolean frozen,
            List<ItemCount> topItems
    ) {
    }

    /** @param side LOWER/UPPER on Example B, null where the vision type has no sides. */
    public record ItemCount(String name, String side, long count) {
    }

    /** A cell the grid is colouring red, mirrored onto the report's map. */
    public record ThresholdMark(String line, String visionKey, String judgement,
                                double ratePct, double thresholdPct, long count, long inspected) {
    }

    public record AlarmFact(
            String line,
            String visionKey,
            String displayName,
            String code,
            String name,
            long count,
            Instant lastAt
    ) {
    }

    /** An inspector that was not reporting during the window. */
    public record StatusFact(
            String line,
            String visionKey,
            String displayName,
            /** OFFLINE (no heartbeat) or IDLE (alive, but nothing inspected). */
            String status,
            Long minutesSinceEvent
    ) {
    }

    /**
     * The bar a finding had to clear.
     *
     * @param sigma      how many band-widths past normal counts as abnormal.
     * @param minExcess  fewest extra defects over normal worth raising.
     * @param minRatio   and how many times normal, so a busy inspector is not flagged for
     *                   a rise that is large in cells and trivial in proportion.
     * @param confirmWindows consecutive windows needed before an alert is raised.
     * @param clearWindows   and consecutive quiet windows needed before one is released.
     */
    public record Rule(
            long minInspected,
            double sigma,
            double minExcess,
            double minRatio,
            int confirmWindows,
            int clearWindows,
            double fastRatio,
            double fastExcess
    ) {
    }

    /** Kept as constants rather than an enum: they cross into JSON, SQL and a prompt. */
    static final class Verdict {
        /** An open alert: past the normal band, big enough, and it held for two windows. */
        static final String SPIKE = "SPIKE";
        /** Outside the band, but not enough to raise - or not yet confirmed. */
        static final String ELEVATED = "ELEVATED";
        /** Too little produced to judge a rate. Counts only, never a percentage. */
        static final String INSUFFICIENT = "INSUFFICIENT";

        private Verdict() {
        }
    }
}
