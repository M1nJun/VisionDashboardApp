/**
 * Mirrors the server's DTOs exactly. Nothing here is a second copy of domain
 * knowledge - there is no vision list, no row order, no threshold table, because
 * every one of those arrives from /api/catalog at runtime. The previous dashboard
 * kept its own copies and they drifted out of step with the backend silently.
 */

export type CellStatus = "NOT_DEPLOYED" | "OFFLINE" | "IDLE" | "RUNNING";
export type ColorLevel = "GREY" | "GREEN" | "YELLOW" | "RED";

export interface Thresholds {
  warnPct: number;
  critPct: number;
}

export interface JudgementDef {
  code: string;
  /** Exactly one per vision type. Its rate is what colours a cell. */
  drivesColor: boolean;
  /**
   * Reference lines for this judgement's own trend. Null on the colour-driving
   * judgement, which is scored against the vision type's thresholdProfile instead so
   * that tuning a threshold in Settings moves the grid and the chart together.
   */
  warnPct: number | null;
  critPct: number | null;
}

export interface VisionTypeView {
  key: string;
  displayName: string;
  /** Fixed-width code for narrow columns, e.g. "EXB-C". */
  shortName: string;
  gridOrder: number;
  thresholdProfile: string;
  productionRefPriority: number | null;
  okJudgement: string;
  judgements: JudgementDef[];
  imageSets: string[];
}

export interface Catalog {
  lines: string[];
  visionTypes: VisionTypeView[];
  thresholds: Record<string, Thresholds>;
  /** The inspectors the plant is judged on - what the rank panel's Common filter covers. */
  commonVisionKeys: string[];
  pollIntervalSeconds: number;
  /** Minutes a wholly stopped line counts as planned before it is called a breakdown. */
  plannedDowntimeMaxMinutes: number;
}

/** A line where every inspector has stopped, and what kind of stop it is. */
export interface LineDowntime {
  kind: "pd" | "bm";
  label: string;
  minutes: number;
}

export interface JudgementCount {
  code: string;
  count: number;
  ratePct: number | null;
  drivesColor: boolean;
}

export interface GridCell {
  line: string;
  visionKey: string;
  displayName: string;
  shortName: string;
  status: CellStatus;
  colorLevel: ColorLevel;
  agentId: string | null;
  lotId: string | null;
  modelId: string | null;
  inspectedCount: number | null;
  defectUnitCount: number | null;
  defectRatePct: number | null;
  judgements: JudgementCount[];
  /** True when a time window was asked for and this inspector produced nothing in it.
   *  The rate is null rather than 0%, so an idle station cannot rank as the best one. */
  windowEmpty: boolean;
  alarmCount: number | null;
  lastEventAt: string | null;
  lastHeartbeatAt: string | null;
  productionReference: boolean;
}

export interface LineProduction {
  line: string;
  /** null when no reference inspector on this line is reporting. */
  production: number | null;
  sourceVisionKey: string | null;
  /** true when the preferred reference was down and a later candidate stood in. */
  estimated: boolean;
  /** What this line is expected to produce in a lot. `production` is measured against it. */
  targetCells: number;
}

/** A headline rate declared in the catalog, e.g. NG across Example B and Example C. */
export interface FleetRate {
  key: string;
  label: string;
  units: number;
  ratePct: number | null;
}

export interface GridTotals {
  production: number;
  /** Every defect verdict from every inspector - the broad number, kept for context. */
  defectUnits: number;
  defectRatePct: number | null;
  /** The narrow rates the floor is actually judged on, in catalog order. */
  rates: FleetRate[];
  targetCells: number;
  running: number;
  idle: number;
  offline: number;
  notDeployed: number;
}

export interface Grid {
  cells: GridCell[];
  lines: LineProduction[];
  totals: GridTotals;
  /** Echo of the window the cells were built for; null means the whole lot. */
  windowMinutes: number | null;
}

export interface DefectGroup {
  label: string;
  judgement: string;
  count: number;
}

export interface DefectItem {
  name: string;
  rawValue: string | null;
  value: number | null;
  side: string | null;
}

export interface DefectImage {
  id: number;
  set: string;
  kind: "MAIN" | "OVERLAY";
  state: "pending" | "ready" | "unavailable";
  url: string | null;
}

export interface Occurrence {
  id: number;
  judgement: string;
  label: string;
  cellId: string | null;
  unitSeq: number;
  occurredAt: string | null;
  items: DefectItem[];
  images: DefectImage[];
}

/** A row in the NG table. Measurements and images are fetched only when one is opened. */
export interface NgEvent {
  id: number;
  occurredAt: string | null;
  cellId: string | null;
  unitSeq: number;
  judgement: string;
  label: string;
}

export interface AlarmEntry {
  code: string | null;
  name: string | null;
  detail: string | null;
  at: string | null;
}

export interface TrendPoint {
  bucketStart: string;
  inspected: number;
  defectUnits: number;
  defectRatePct: number | null;
  judgements: Record<string, number>;
  judgementRatePct: Record<string, number>;
}

export interface VisionDetail {
  cell: GridCell;
  /** Doubles as the defect-name filter for the event table. */
  topDefects: DefectGroup[];
  events: NgEvent[];
  /** What the lot actually holds, so a capped list can say how much it is showing. */
  eventTotal: number;
  alarms: AlarmEntry[];
  trend: TrendPoint[];
}

export interface Setting {
  key: string;
  value: string;
  description: string | null;
}

/* ===================== AI report ===================== */

/**
 * Why the report bothered mentioning a slot at all.
 *
 * Decided in SQL and arithmetic on the server, never by the model, and the tab colours by
 * them - so what a badge means today is what it meant last week.
 *
 * - SPIKE        an open alert: past this inspector's normal band, by enough, for long
 *                enough. Stays an alert until the count comes back inside the band.
 * - ELEVATED     outside the band but not enough to raise, or not yet confirmed.
 * - INSUFFICIENT too little produced in the window to judge a rate at all. These carry no
 *                rate and no band, on purpose - see AiFinding.normalHigh.
 */
export type AiVerdict = "SPIKE" | "ELEVATED" | "INSUFFICIENT";
export type AiSeverity = "NORMAL" | "WATCH" | "ALERT";
export type AiStatus = "PENDING" | "OK" | "LLM_FAILED" | "SKIPPED";

export interface AiItemCount {
  name: string;
  /** LOWER/UPPER on Example B; null where the vision type has no sides. */
  side: string | null;
  count: number;
}

export interface AiFinding {
  line: string;
  visionKey: string;
  displayName: string;
  judgement: string;
  verdict: AiVerdict;
  /** Cells checked in the window - what the band is scaled to. */
  inspected: number;
  count: number;
  /** Defects this much production normally yields. */
  expected: number | null;
  normalLow: number | null;
  /**
   * Top of the normal band, in defects - the number the verdict turns on.
   *
   * Null on an INSUFFICIENT finding, and null in the sense of "must not be shown" rather
   * than "was not measured": too few cells went through to say anything about a rate.
   */
  normalHigh: number | null;
  /** How far this inspector normally wanders: 1.0 is chance, Example B DLNG measures 2.5-5. */
  spread: number | null;
  ratePct: number | null;
  baselinePct: number | null;
  activeMinutes: number;
  /** When this alert began. Shown as a time, because nobody thinks in report windows. */
  episodeStart: string | null;
  /** True in the window the alert was confirmed; drives the header's "N new, M continuing". */
  isNew: boolean;
  /** True while the comparison is against the inspector as it was before this fault. */
  frozen: boolean;
  topItems: AiItemCount[];
}

/** A cell the grid is colouring red. Marked on the map; raises nothing by itself. */
export interface AiThresholdMark {
  line: string;
  visionKey: string;
  judgement: string;
  ratePct: number;
  thresholdPct: number;
  count: number;
  inspected: number;
}

export interface AiLineFact {
  line: string;
  production: number | null;
  referenceVisionKey: string | null;
  activeMinutes: number;
  /** Minutes of the window with no production at all. The thin-window warning. */
  stoppedMinutes: number;
  lotChanged: boolean;
}

export interface AiFleet {
  produced: number;
  activeMinutes: number;
  inspectedAllStations: number;
  defectUnitsAllStations: number;
  rates: FleetRate[];
}

export interface AiAlarm {
  line: string;
  visionKey: string;
  /** The Korean name, so the table and the prose call the inspector the same thing. */
  displayName: string;
  code: string | null;
  name: string | null;
  count: number;
  lastAt: string | null;
}

export interface AiStatusFact {
  line: string;
  visionKey: string;
  displayName: string;
  status: "OFFLINE" | "IDLE";
  minutesSinceEvent: number | null;
}

/** The bar a finding had to clear, so the screen can state the rule rather than imply it. */
export interface AiRule {
  minInspected: number;
  sigma: number;
  minExcess: number;
  minRatio: number;
  confirmWindows: number;
  clearWindows: number;
  fastRatio: number;
  fastExcess: number;
}

export interface AiFacts {
  windowStart: string;
  windowEnd: string;
  windowMinutes: number;
  baselineHours: number;
  spreadDays: number;
  fleet: AiFleet;
  lines: AiLineFact[];
  findings: AiFinding[];
  thresholds: AiThresholdMark[];
  alarms: AiAlarm[];
  statuses: AiStatusFact[];
  rule: AiRule;
}

export interface AiReport {
  id: number;
  windowStart: string;
  windowEnd: string;
  generatedAt: string | null;
  status: AiStatus;
  severity: AiSeverity;
  headline: string | null;
  /** Null when the model was unavailable. The findings below still hold every number. */
  summary: string | null;
  model: string | null;
  latencyMs: number | null;
  error: string | null;
  /** Null only for a row written by an older server build. */
  facts: AiFacts | null;
}

/** Why the tab is empty, when it is - each of these is a one-minute fix. */
export interface AiReportStatus {
  enabled: boolean;
  /** False until db/migrate-ai-reports.sql has been run on the central PC. */
  schemaReady: boolean;
  ollamaAvailable: boolean;
  model: string;
  intervalMinutes: number;
  lastReportAt: string | null;
  nextRunAt: string | null;
  /** Whether a newly raised alert interrupts whatever tab somebody is on.
   *  Optional so an older server, which does not send it, still behaves as if on. */
  popupEnabled?: boolean;
}
