/**
 * The dashboard, running on captured data instead of a server.
 *
 * `api.ts` is the only place the app talks to the backend, so a demo build only has to
 * replace that one module. Every screen, every filter and every interaction is the real
 * component reading the real response shapes - the fixtures are the actual API's output,
 * taken from a seeded server by `web/demo/capture.py`, with the inspection stages,
 * defect codes and line names replaced by example vocabulary.
 *
 * Two things are added on top of the captured data, because a recording of a plant is
 * not the same as a plant:
 *
 *   - the timestamps are shifted forward, so the fleet reads as running now rather than
 *     as one that stopped on the afternoon the fixtures were taken;
 *   - the counts creep up between polls, so the screen is alive. A dashboard whose
 *     numbers never move looks broken, which is the opposite of what a demo is for.
 */

import type { Catalog, Grid, Occurrence, Setting, VisionDetail } from "../types";
import { demoAiStatus, demoReports } from "./ai";

/**
 * Reloads once when the deployed build is newer than the one running.
 *
 * GitHub Pages serves index.html with a ten-minute cache and no way to change the header.
 * The asset filenames are hashed, so a returning visitor holds a cached index.html that
 * points at the previous bundle and sees the previous app - which is why a new tab needed
 * a hard refresh to appear. The hashed assets are not the problem; the one unhashed file
 * is.
 *
 * So the deployed build id is published as a small file beside the page and fetched past
 * the cache. If it differs from the id compiled into this bundle, the page is out of date
 * and a reload - which revalidates the document - picks up the new one. The marker makes
 * this at most one reload per deployment per tab, so a stubborn cache cannot put the page
 * in a loop.
 */
const RELOADED_FOR = "visiondash.demoReloadedFor";

void (async function reloadIfStale() {
  try {
    const url = `${import.meta.env.BASE_URL}build-id.json?t=${Date.now()}`;
    const response = await fetch(url, { cache: "no-store" });
    if (!response.ok) return;
    const { id } = (await response.json()) as { id?: string };
    if (!id || id === __BUILD_ID__) return;
    if (window.sessionStorage.getItem(RELOADED_FOR) === id) return;
    window.sessionStorage.setItem(RELOADED_FOR, id);
    window.location.reload();
  } catch {
    // No manifest, no network, or storage blocked. The page it already has still works.
  }
})();
import bundle from "./data/fixtures.json";
import sampleMain from "./sample-main.svg";
import sampleOverlay from "./sample-overlay.svg";

type Fixtures = {
  capturedAt: string;
  catalog: Catalog;
  grid: Record<string, Grid>;
  detail: Record<string, VisionDetail>;
  occurrence: Record<string, Occurrence>;
  settings: Setting[];
};

const data = bundle as unknown as Fixtures;

/** How far the captured day has to move to land on today. */
const SHIFT_MS = Date.now() - new Date(data.capturedAt).getTime();
const OPENED_AT = Date.now();

const shift = (iso: string | null): string | null =>
  iso === null ? null : new Date(new Date(iso).getTime() + SHIFT_MS).toISOString();

/** Walks a response and moves every timestamp it carries. */
function shifted<T>(node: T): T {
  if (Array.isArray(node)) return node.map(shifted) as unknown as T;
  if (node && typeof node === "object") {
    const out: Record<string, unknown> = {};
    for (const [key, value] of Object.entries(node as Record<string, unknown>)) {
      out[key] = isTimestampField(key) && (typeof value === "string" || value === null)
        ? shift(value as string | null)
        : shifted(value);
    }
    return out as T;
  }
  return node;
}

const isTimestampField = (key: string) =>
  key === "occurredAt" || key === "bucketStart" || key === "at" ||
  key === "lastEventAt" || key === "lastHeartbeatAt";

/**
 * Cells inspected since the page was opened, at roughly the rate the seeded plant runs.
 * Everything that moves on screen is derived from this one number, so the grid, the
 * line output and the fleet totals stay consistent with each other.
 */
const producedSince = () => Math.floor((Date.now() - OPENED_AT) / 1000) * 3;

function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}

const rate = (part: number, whole: number) => (whole > 0 ? (100 * part) / whole : null);

/** Adds the production that has happened since the page opened. */
function advance(grid: Grid): Grid {
  const made = producedSince();
  if (made === 0) return grid;

  for (const cell of grid.cells) {
    if (cell.status !== "RUNNING" || cell.inspectedCount === null) continue;
    cell.inspectedCount += made;
    cell.defectRatePct = rate(cell.defectUnitCount ?? 0, cell.inspectedCount);
    for (const judgement of cell.judgements) {
      judgement.ratePct = rate(judgement.count, cell.inspectedCount);
    }
  }
  for (const line of grid.lines) {
    if (line.production !== null) line.production += made;
  }
  grid.totals.production += made * grid.lines.filter((l) => l.production !== null).length;
  grid.totals.defectRatePct = rate(grid.totals.defectUnits, grid.totals.production);
  return grid;
}

const later = <T,>(value: T): Promise<T> =>
  new Promise((resolve) => window.setTimeout(() => resolve(value), 90));

// ---- the API the app actually calls -------------------------------------------

export const fetchCatalog = () => later(data.catalog);

/** Constant: there is no deployment behind this build to notice a change in. */
export const fetchBuildId = () => later("demo");

export const fetchGrid = (windowMinutes: number | null) => {
  const key = windowMinutes === null ? "lot" : String(windowMinutes);
  const grid = data.grid[key] ?? data.grid.lot;
  return later(advance(shifted(clone(grid))));
};

export const fetchDetail = (line: string, visionKey: string) => {
  const detail = data.detail[`${line}/${visionKey}`];
  if (!detail) return Promise.reject(new Error(`no demo data for ${line}/${visionKey}`));
  return later(shifted(clone(detail)));
};

/**
 * Built from the row that was opened, over the template captured for that vision type.
 * Carrying every occurrence would have meant a shift of them in the bundle, and this
 * way every row in the table opens rather than only the handful that were captured.
 */
export const fetchOccurrence = (line: string, visionKey: string, occurrenceId: number) => {
  const template = data.occurrence[visionKey];
  if (!template) return Promise.reject(new Error(`no demo images for ${visionKey}`));

  const detail = data.detail[`${line}/${visionKey}`];
  const event = detail?.events.find((e) => e.id === occurrenceId);
  const occurrence = shifted(clone(template));

  if (event) {
    occurrence.id = event.id;
    occurrence.cellId = event.cellId;
    occurrence.unitSeq = event.unitSeq;
    occurrence.judgement = event.judgement;
    occurrence.label = event.label;
    occurrence.occurredAt = shift(event.occurredAt);
  }
  // The frames are drawings, not photographs from the line - see sample-main.svg.
  occurrence.images = occurrence.images.map((image) => ({
    ...image,
    state: "ready" as const,
    url: image.kind === "OVERLAY" ? sampleOverlay : sampleMain,
  }));
  return later(occurrence);
};

// ---- settings, which really do change ------------------------------------------

const settings: Setting[] = clone(data.settings);
const TOKEN = "demo-token";
const TOKEN_KEY = "visiondash.settingsToken";
/** The same password the real build ships with, so the demo shows the real lock. */
const PASSWORD = "mimi";

export const fetchSettings = () => later(clone(settings));

export function settingsToken(): string | null {
  try {
    return window.sessionStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

export function clearSettingsToken(): void {
  try {
    window.sessionStorage.removeItem(TOKEN_KEY);
  } catch {
    // Storage blocked; there is nothing held to clear.
  }
}

export const settingsSessionValid = () => later(settingsToken() === TOKEN);

export async function unlockSettings(password: string): Promise<void> {
  await later(null);
  if (password !== PASSWORD) throw new Error("wrong password");
  try {
    window.sessionStorage.setItem(TOKEN_KEY, TOKEN);
  } catch {
    // Storage blocked; the page still works, it just asks again next time.
  }
}

/** Kept in memory for the session, so saving a value visibly does something. */
export async function updateSetting(key: string, value: string): Promise<void> {
  await later(null);
  if (settingsToken() !== TOKEN) throw new Error(`Could not save ${key}: HTTP 401`);
  const entry = settings.find((s) => s.key === key);
  if (!entry) throw new Error(`Could not save ${key}: HTTP 404`);
  entry.value = value;
}

// ---- the AI report tab ---------------------------------------------------------

/**
 * Written rather than captured - see demo/ai.ts. Everything else in this module replays
 * a recording of a seeded server; a recording of a shift's AI reports would describe a
 * real line, which is the one thing this build must not carry.
 */
export const fetchAiStatus = () => later(demoAiStatus());

export const fetchAiReports = (limit = 24) => later(demoReports(limit));

/** There is no model behind this build to run. */
export const runAiReport = () =>
  later("The demo has no model behind it - reports here are examples.");
