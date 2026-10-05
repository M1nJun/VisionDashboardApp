package com.visiondash.server.ai;

import java.util.List;

/**
 * What "normal" means for one inspector, in defects.
 *
 * The first version of this file asked a textbook question - how unlikely is this count
 * under that inspector's own recent rate - and got the wrong answer for the most
 * important judgement on the floor. Example B DLNG does not behave like coin flips: measured
 * over five days of real production it swings about three times as far between half-hour
 * windows as pure chance allows, because the rate itself moves with the lot, the model and
 * the hour. A test that assumes pure chance calls that ordinary movement significant, and
 * it did: 166 of 221 report windows came back as alerts, 153 of them on one "spike"
 * somewhere among 49 inspectors.
 *
 * So normal is measured rather than assumed, in two parts:
 *
 *   level  - how many defects this much production normally yields.  Last 24 hours.
 *   spread - how far that count normally wanders, as a multiple of what chance alone
 *            would give.  Last 7 days.  1.0 means "behaves like chance"; Example B DLNG
 *            measures 2.5-5, and 132 of 147 inspector/judgement pairs measure 1.0.
 *
 * and the band is  level ± z·sqrt(spread × level).
 *
 * The other reason for the change is that the band can be read out loud. "at this volume, usually 0-26; this window, 64" is the same judgement a p-value was making, in the only terms
 * the floor actually works in: how many cells.
 */
final class Stats {

    private Stats() {
    }

    /** Multiplier turning a median absolute deviation into a standard deviation. */
    private static final double MAD_TO_SD = 1.4826;

    /**
     * Expected defects for this much production, nudged off zero.
     *
     * An inspector with a clean 24 hours has an observed rate of exactly 0, which predicts
     * zero defects with certainty and would make the very next one infinitely surprising.
     * The Jeffreys prior (+0.5 over +1) is the standard fix and is invisible once the
     * denominator is in the thousands, which it always is here.
     */
    static double level(long defects, long inspected) {
        return (defects + 0.5) / (inspected + 1.0);
    }

    /**
     * Top of the normal band, in defects.
     *
     * sqrt(spread × level) rather than sqrt(level): the width grows with how much this
     * particular inspector actually wanders, so a steady one stays tightly judged while a
     * naturally restless one is not accused every half hour.
     */
    static double bandHigh(double expected, double spread, double z) {
        return expected + z * Math.sqrt(Math.max(spread, 1.0) * Math.max(expected, 0));
    }

    /** Bottom of the band, floored at zero - a negative number of defects says nothing. */
    static double bandLow(double expected, double spread, double z) {
        return Math.max(0, expected - z * Math.sqrt(Math.max(spread, 1.0) * Math.max(expected, 0)));
    }

    /**
     * How far this inspector's counts normally wander, from its own history.
     *
     * Each value is one window's count expressed as (observed − expected) / sqrt(expected),
     * which is 1.0-ish when chance is the only thing at work. The spread is the square of
     * their robust spread, so it reads as "this many times the variance chance alone
     * would give".
     *
     * Median-based on purpose. A mean would let one bad afternoon widen the band that
     * afternoon is judged against; the median ignores up to half the values, so a fault
     * has to become the inspector's normal life before it moves. Windows that belong to an
     * alert are excluded before they get here as well - see EpisodeStore - because a
     * broken inspector must not teach the system that broken is normal. C-1's C-NG
     * measured a spread of 50 with its own four-day failure left in.
     *
     * @return null when there is not enough history to measure; the caller falls back to
     *         the vision type's own figure rather than guessing.
     */
    static Double spread(List<Double> residuals, int minWindows) {
        if (residuals == null || residuals.size() < minWindows) {
            return null;
        }
        double[] values = residuals.stream().mapToDouble(Double::doubleValue).toArray();
        double centre = median(values);
        double[] deviations = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            deviations[i] = Math.abs(values[i] - centre);
        }
        double sd = median(deviations) * MAD_TO_SD;
        return sd * sd;
    }

    /** One window's count as a multiple of the chance-only wobble around its expectation. */
    static double residual(long count, double expected) {
        return expected <= 0 ? 0 : (count - expected) / Math.sqrt(expected);
    }

    static double median(double[] values) {
        if (values.length == 0) {
            return 0;
        }
        double[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        int mid = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2.0;
    }
}
