package com.visiondash.server.ai;

import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

/**
 * Renders the finished facts into something a 12B model can write up without getting
 * anything wrong.
 *
 * Three decisions here matter more than the wording:
 *
 * 1. The facts go in as a labelled text block, not as JSON. A model this size reproduces a
 *    figure it read under a heading far more reliably than one it had to walk a nested
 *    object to find, and the report only ever needs to be read, never parsed.
 *
 * 2. Everything is counted in defects. "usually 0-26, this window 64" is a sentence
 *    anybody on the floor can check against the screen; "p=0.003, 95% lower bound 6.4%"
 *    was a sentence only its author could check. The statistics behind the band did not
 *    get simpler - they stopped being the reader's problem.
 *
 * 3. A finding whose window was too thin arrives with NO rate in it at all - ratePct is
 *    null by then, and this class prints counts in its place. Telling a model not to quote
 *    a number while showing it the number is a request it will honour most of the time;
 *    not giving it the number is a guarantee.
 */
@Component
public class AiPrompt {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter SINCE = DateTimeFormatter.ofPattern("MMM d HH:mm");

    /**
     * The rules, kept apart from the data so that Ollama caches them across reports and so
     * no fact can ever be read as an instruction.
     *
     * Negative rules carry their reason. A bare "do not invent causes" is followed less
     * consistently by a small model than the same rule with the because-clause that makes
     * it make sense, and the cost is a few dozen tokens of a prompt that is already tiny.
     */
    public String system() {
        return """
                You write the shift summary for a vision inspection floor. You are given
                figures already aggregated over one window, and you turn them into a short
                report somebody reads off a wall display.

                How to read the data
                - Each item is written as "at this volume, usually A-B; this window, K".
                  A-B is the range that inspector normally moves in. K above it is abnormal.
                - The usual range differs per inspector. One that naturally swings a lot,
                  like Example B DLNG, has a wide range; one that almost never produces a
                  defect has a narrow one. Never work a range out for yourself.

                Absolute rules
                1. Use only the numbers given. Do not calculate anything. The counts, the
                   usual ranges and the multiples are already worked out in the data. If you
                   add or divide, you will be wrong.
                2. For an item with no usual range, never speak in percentages or ratios.
                   Too little was inspected to judge one, and the data says "too few units"
                   for exactly that reason. Write it as a count - "1 in 120 inspected" - and
                   say the judgement is being withheld.
                3. Do not guess at causes. Where an equipment alarm or a stopped line is in
                   the data too, you may place the facts side by side - "there was also an
                   alarm in the same window" - and no further.
                4. Do not invent a line, an inspector or a defect type that is not in the data.
                5. Do not give instructions or corrective actions. Report what happened.
                6. Superlatives - "the most", "the worst" - only for the item the data marks
                   with "** the largest count in this window". The list is ordered by
                   importance, not by count.
                7. Do not invent an order or a moment. Running and stopped minutes are totals
                   only. Write "ran 2 of 30 minutes", never "ran 2 minutes then stopped".
                8. Do not rename a metric. "NG Rate" counts NG on two inspection stages only,
                   so calling it "defect rate" claims something wider than it measures.
                9. Write inspector names exactly as the data spells them. Anode and cathode
                   are different inspectors; do not swap them.

                Kinds of item
                - [Alert] Well outside the usual range and held for two windows or more.
                  These matter most. Where "open since ..." is given, that is when it
                  started - say so, and do not count windows.
                - [Watch] Outside the usual range but short of the alert bar.
                - [Too few units] Not enough inspected to judge. No percentages.

                Output
                - First line: one sentence summarising the window. No bullet, no number.
                - Then 3 to 6 lines starting with "- ". Alerts first.
                - Where several lines show the same thing, say it in one line. Transcribing
                  the data row by row gives ten lines and buries whatever is most urgent.
                - If nothing stands out, say so and stop. Do not pad.
                - Stay under 400 characters. This is read from across a floor.
                """;
    }

    /** The window's evidence, as the labelled block described in the class comment. */
    public String render(AiFacts facts) {
        ZoneId zone = ZoneId.systemDefault();
        StringBuilder out = new StringBuilder(2048);

        out.append("# Window: ")
                .append(STAMP.format(facts.windowStart().atZone(zone)))
                .append(" - ")
                .append(CLOCK.format(facts.windowEnd().atZone(zone)))
                .append(" (").append(facts.windowMinutes()).append(" min)\n");
        out.append("# \"Usual\" means: each inspector's own last ").append(facts.baselineHours())
                .append(" hours, and how far it has swung over the last ")
                .append(facts.spreadDays()).append(" days\n\n");

        renderProduction(out, facts);
        renderFindings(out, facts);
        renderAlarms(out, facts);
        renderStatuses(out, facts);
        return out.toString();
    }

    private void renderProduction(StringBuilder out, AiFacts facts) {
        AiFacts.Fleet fleet = facts.fleet();
        out.append("[Production]\n");
        out.append("Total produced ").append(count(fleet.produced())).append(" cells\n");
        for (AiFacts.Rate rate : fleet.rates()) {
            out.append(rate.label()).append(" ")
                    .append(rate.ratePct() == null ? "cannot be computed" : pct(rate.ratePct()))
                    .append(" (").append(count(rate.units())).append(")\n");
        }
        for (AiFacts.LineFact line : facts.lines()) {
            out.append("  ").append(line.line()).append(": ")
                    .append(line.produced() == null ? "no production"
                            : count(line.produced()) + " cells");
            // The most important qualifier in the block: a line that ran two minutes of
            // thirty did not have a good or a bad half hour, it had two minutes.
            if (line.stoppedMinutes() > 0) {
                out.append(", ran ").append(line.activeMinutes()).append(" of ")
                        .append(facts.windowMinutes()).append(" minutes");
            }
            if (line.lotChanged()) {
                out.append(", lot changed mid-window");
            }
            out.append("\n");
        }
        out.append("\n");
    }

    private void renderFindings(StringBuilder out, AiFacts facts) {
        out.append("[Items worth a look]\n");
        if (facts.findings().isEmpty()) {
            out.append("None. Every inspector stayed inside its usual range.\n\n");
            return;
        }
        // Marked because the list is ordered by importance, not by count: an alert on a
        // full window outranks a bigger number on a thin one. Left unmarked, the model
        // picked whichever number looked largest and called it the worst.
        AiFacts.Finding most = facts.findings().stream()
                .max(Comparator.comparingLong(AiFacts.Finding::count)).orElse(null);

        int index = 1;
        for (AiFacts.Finding f : facts.findings()) {
            out.append(index++).append(") [").append(verdictLabel(f.verdict())).append("] ")
                    .append(f.line()).append(" ").append(f.displayName())
                    .append(" / ").append(f.judgement());
            if (f.episodeStart() != null) {
                out.append(" / open since ")
                        .append(SINCE.format(f.episodeStart().atZone(ZoneId.systemDefault())));
                if (f.isNew()) {
                    out.append(" (raised in this window)");
                }
            }
            out.append("\n");

            out.append("   inspected ").append(count(f.inspected())).append(" cells, ")
                    .append(f.judgement()).append(" ").append(count(f.count())).append("\n");

            if (f.normalHigh() == null) {
                // INSUFFICIENT: no band exists, so no rate can reach the prose.
                out.append("   ** too few units - no usual range was computed. ")
                        .append("Mention the count only.\n");
            } else {
                out.append("   at this volume, usually ").append(band(f))
                        .append("; this window, ").append(count(f.count()));
                if (f.expected() != null && f.expected() > 0) {
                    out.append(" (x").append(String.format("%.1f", f.count() / f.expected()))
                            .append(" the usual)");
                }
                out.append("\n");
                if (f.frozen()) {
                    out.append("   ** \"usual\" here is this inspector as it was ")
                            .append("before the problem started\n");
                }
            }
            if (f.activeMinutes() < facts.windowMinutes()) {
                out.append("   this inspector ran ").append(f.activeMinutes()).append(" of ")
                        .append(facts.windowMinutes()).append(" minutes\n");
            }
            if (!f.topItems().isEmpty()) {
                out.append("   main defects: ").append(items(f.topItems())).append("\n");
            }
            if (f == most) {
                out.append("   ** the largest count in this window\n");
            }
        }
        out.append("\n");
    }

    private void renderAlarms(StringBuilder out, AiFacts facts) {
        if (facts.alarms().isEmpty()) {
            return;
        }
        out.append("[Equipment alarms]\n");
        for (AiFacts.AlarmFact alarm : facts.alarms()) {
            out.append("  ").append(alarm.line()).append(" ").append(alarm.displayName())
                    .append(": ");
            if (alarm.code() != null && !alarm.code().isBlank()) {
                out.append(alarm.code()).append(" ");
            }
            out.append(alarm.name() == null ? "(unnamed)" : alarm.name())
                    .append(" x").append(alarm.count()).append("\n");
        }
        out.append("\n");
    }

    private void renderStatuses(StringBuilder out, AiFacts facts) {
        if (facts.statuses().isEmpty()) {
            return;
        }
        // "Not reporting" rather than "stopped": an offline inspector produces no rows,
        // which in every table above is indistinguishable from a clean run.
        out.append("[Inspectors not reporting]\n");
        for (AiFacts.StatusFact status : facts.statuses()) {
            out.append("  ").append(status.line()).append(" ").append(status.displayName())
                    .append(": ")
                    .append("OFFLINE".equals(status.status()) ? "no heartbeat" : "no production");
            if (status.minutesSinceEvent() != null && status.minutesSinceEvent() > 0) {
                out.append(" (last inspected ").append(status.minutesSinceEvent())
                        .append(" min ago)");
            }
            out.append("\n");
        }
        out.append("\n");
    }

    /** The band as the screen prints it: whole defects, because half a defect is not a thing. */
    private static String band(AiFacts.Finding f) {
        long low = Math.round(Math.floor(f.normalLow()));
        long high = Math.round(Math.ceil(f.normalHigh()));
        return low + "-" + high;
    }

    /** Handed over rather than left to the model to word for itself. */
    private static String verdictLabel(String verdict) {
        return switch (verdict) {
            case AiFacts.Verdict.SPIKE -> "Alert";
            case AiFacts.Verdict.ELEVATED -> "Watch";
            case AiFacts.Verdict.INSUFFICIENT -> "Too few units";
            default -> verdict;
        };
    }

    private static String items(List<AiFacts.ItemCount> items) {
        StringBuilder sb = new StringBuilder();
        for (AiFacts.ItemCount item : items) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(item.name());
            if (item.side() != null) {
                sb.append("(").append(item.side()).append(")");
            }
            sb.append(" ").append(item.count());
        }
        return sb.toString();
    }

    private static String pct(double value) {
        return String.format("%.3f%%", value);
    }

    private static String count(long value) {
        return String.format("%,d", value);
    }
}
