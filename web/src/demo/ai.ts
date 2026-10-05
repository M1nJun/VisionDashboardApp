/**
 * Example AI reports for the demo build.
 *
 * Unlike the grid fixtures, these are written rather than captured. A captured report is
 * a description of a real shift on a real line, which is exactly the thing this repository
 * does not publish - so the numbers here are invented, and chosen to put every state the
 * tab can draw on screen at once:
 *
 *   - an alert that has been running since yesterday evening, judged against a frozen
 *     baseline, so "open since" and the frozen marker both appear;
 *   - an alert confirmed in this window, so the header shows new against continuing;
 *   - a watch, outside the usual range but below the alert bar;
 *   - a too-few-units item, carrying a count and deliberately no rate at all;
 *   - a cell the grid is colouring red, marked but not raised;
 *   - an equipment alarm, and an inspector that stopped reporting.
 *
 * They are generated relative to now, so the window always reads as the one that just
 * closed however long the page has been open.
 */

import type { AiFacts, AiFinding, AiReport, AiReportStatus } from "../types";

const WINDOW_MIN = 30;

const floorToWindow = (ms: number) => {
  const d = new Date(ms);
  d.setSeconds(0, 0);
  d.setMinutes(Math.floor(d.getMinutes() / WINDOW_MIN) * WINDOW_MIN);
  return d;
};

const iso = (d: Date) => d.toISOString();
const minutesBefore = (from: Date, n: number) => new Date(from.getTime() - n * 60_000);

function finding(
  line: string,
  visionKey: string,
  displayName: string,
  judgement: string,
  verdict: AiFinding["verdict"],
  inspected: number,
  count: number,
  expected: number | null,
  low: number | null,
  high: number | null,
  spread: number | null,
  ratePct: number | null,
  baselinePct: number | null,
  activeMinutes: number,
  episodeStart: string | null,
  isNew: boolean,
  frozen: boolean,
  topItems: { name: string; side: string | null; count: number }[]
): AiFinding {
  return {
    line, visionKey, displayName, judgement, verdict, inspected, count, expected,
    normalLow: low, normalHigh: high, spread, ratePct, baselinePct, activeMinutes,
    episodeStart, isNew, frozen, topItems,
  };
}

function buildFacts(end: Date): AiFacts {
  const start = minutesBefore(end, WINDOW_MIN);
  const yesterdayEvening = new Date(end);
  yesterdayEvening.setDate(yesterdayEvening.getDate() - 1);
  yesterdayEvening.setHours(20, 50, 0, 0);

  return {
    windowStart: iso(start),
    windowEnd: iso(end),
    windowMinutes: WINDOW_MIN,
    baselineHours: 24,
    spreadDays: 7,
    fleet: {
      produced: 18_420,
      activeMinutes: WINDOW_MIN,
      inspectedAllStations: 128_940,
      defectUnitsAllStations: 812,
      rates: [
        { key: "ngRate", label: "NG Rate", units: 128_940, ratePct: 0.193 },
        { key: "dlngRate", label: "Example B DLNG", units: 36_840, ratePct: 1.482 },
      ],
    },
    lines: ["A-1", "A-2", "B-1", "B-2", "C-1", "C-2", "C-3"].map((line) => ({
      line,
      production: 2_630,
      referenceVisionKey: "EXAMPLE_C",
      activeMinutes: line === "C-2" ? 4 : WINDOW_MIN,
      stoppedMinutes: line === "C-2" ? WINDOW_MIN - 4 : 0,
      lotChanged: line === "A-2",
    })),
    findings: [
      // Running since last night: the comparison is frozen at what this inspector did
      // before the fault, which is why the multiple is so large.
      finding("C-1", "EXAMPLE_B_ANODE", "Example B Anode Vision", "C-NG", "SPIKE",
        5_260, 64, 0.9, 0, 26, 5.8, 1.217, 0.018, WINDOW_MIN,
        iso(yesterdayEvening), false, true,
        [{ name: "CHECK_A", side: "LOWER", count: 41 }, { name: "ITEM_B_L", side: "UPPER", count: 23 }]),
      // Flagged last window, confirmed in this one.
      finding("A-1", "EXAMPLE_E", "Example E Vision", "NG", "SPIKE",
        2_630, 12, 1.4, 0, 5, 1.2, 0.456, 0.053, WINDOW_MIN,
        iso(minutesBefore(start, WINDOW_MIN)), true, false,
        [{ name: "CHECK_E", side: null, count: 12 }]),
      // Outside the range, not far enough past it to raise.
      finding("A-2", "EXAMPLE_B_CATHODE", "Example B Cathode Vision", "DLNG", "ELEVATED",
        5_260, 118, 78.4, 42, 114, 3.1, 2.243, 1.491, WINDOW_MIN,
        null, false, false,
        [{ name: "B_L", side: "UPPER", count: 71 }, { name: "CHECK_A", side: "LOWER", count: 47 }]),
      // Barely ran: a count, and no percentage anywhere.
      finding("C-2", "EXAMPLE_A_ANODE", "Example A Anode Vision", "NG", "INSUFFICIENT",
        104, 1, null, null, null, null, null, null, 4,
        null, false, false, [{ name: "CHECK_C", side: null, count: 1 }]),
    ],
    thresholds: [
      {
        line: "C-3", visionKey: "EXAMPLE_C", judgement: "NG",
        ratePct: 0.061, thresholdPct: 0.05, count: 2, inspected: 3_280,
      },
    ],
    alarms: [
      {
        line: "C-1", visionKey: "EXAMPLE_B_ANODE", displayName: "Example B Anode Vision",
        code: "52", name: "Cylinder Timeout", count: 3,
        lastAt: iso(minutesBefore(end, 6)),
      },
    ],
    statuses: [
      {
        line: "C-2", visionKey: "EXAMPLE_D", displayName: "Example D Vision",
        status: "IDLE", minutesSinceEvent: 26,
      },
    ],
    rule: {
      minInspected: 200, sigma: 3, minExcess: 3, minRatio: 2,
      confirmWindows: 2, clearWindows: 2, fastRatio: 5, fastExcess: 10,
    },
  };
}

const SUMMARY = [
  "C-1 Example B Anode Vision C-NG has been high since yesterday evening.",
  "- C-1 Example B Anode Vision C-NG: at this volume usually 0-26, this window 64 (x71 the usual). Open since yesterday 20:50.",
  "- A-1 Example E Vision NG: usually 0-5, this window 12. Carried over from the previous window and raised to an alert now.",
  "- A-2 Example B Cathode Vision DLNG sits just past its usual 42-114 range at 118.",
  "- C-2 ran 4 of 30 minutes.",
].join("\n");

/**
 * The history behind the timeline, oldest severities varied so the strip reads as a
 * shift rather than a flat line. Only the newest report carries facts: the tab asks for
 * the rest to draw the strip, and draws everything else from the one that is open.
 */
function history(end: Date, count: number): AiReport[] {
  const out: AiReport[] = [];
  for (let i = 1; i <= count; i++) {
    const windowEnd = minutesBefore(end, WINDOW_MIN * i);
    out.push({
      id: 900 - i,
      windowStart: iso(minutesBefore(windowEnd, WINDOW_MIN)),
      windowEnd: iso(windowEnd),
      generatedAt: iso(windowEnd),
      status: "OK",
      severity: i % 5 === 1 ? "ALERT" : i % 3 === 0 ? "WATCH" : "NORMAL",
      headline: "Earlier window",
      summary: null,
      model: "gemma3:12b",
      latencyMs: 7_000 + i * 120,
      error: null,
      facts: null,
    });
  }
  return out;
}

/**
 * A fresh report id once the page has been open long enough.
 *
 * The alert popup only fires when the newest report's id changes after the first poll,
 * which is exactly what happens on the floor every half hour. Here the same thing is
 * arranged on a timer so a visitor sees it happen rather than reading that it would.
 */
const OPENED_AT = Date.now();
const SECOND_REPORT_AFTER_MS = 6_000;
const arrived = () => Date.now() - OPENED_AT >= SECOND_REPORT_AFTER_MS;

export function demoReports(limit: number): AiReport[] {
  const end = floorToWindow(Date.now());
  const facts = buildFacts(end);
  const fresh = arrived();

  const current: AiReport = {
    id: fresh ? 901 : 900,
    windowStart: facts.windowStart,
    windowEnd: facts.windowEnd,
    generatedAt: iso(end),
    status: "OK",
    severity: "ALERT",
    headline: SUMMARY.split("\n")[0],
    summary: SUMMARY.split("\n").slice(1).join("\n"),
    model: "gemma3:12b",
    latencyMs: 8_420,
    error: null,
    // Before the "new" report lands, the A-1 alert has not been confirmed yet - so the
    // popup has something to announce when it does.
    facts: fresh ? facts : {
      ...facts,
      findings: facts.findings.map((f) =>
        f.line === "A-1" ? { ...f, verdict: "ELEVATED", episodeStart: null, isNew: false } : f),
    },
  };

  return [current, ...history(end, Math.max(0, limit - 1))];
}

export function demoAiStatus(): AiReportStatus {
  const end = floorToWindow(Date.now());
  return {
    enabled: true,
    schemaReady: true,
    ollamaAvailable: true,
    model: "gemma3:12b",
    intervalMinutes: WINDOW_MIN,
    lastReportAt: iso(end),
    nextRunAt: iso(new Date(end.getTime() + WINDOW_MIN * 60_000)),
    popupEnabled: true,
  };
}
