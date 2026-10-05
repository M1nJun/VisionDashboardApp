import type {
  AiReport,
  AiReportStatus,
  Catalog,
  Grid,
  Occurrence,
  Setting,
  VisionDetail,
} from "./types";

// Same context path Spring Boot is configured with, so the built SPA and the API are
// one origin and one deployment.
const BASE = "/dashboard/api";

async function getJson<T>(path: string): Promise<T> {
  const response = await fetch(`${BASE}${path}`);
  if (!response.ok) {
    throw new Error(`${path} returned ${response.status}`);
  }
  return (await response.json()) as T;
}

export const fetchCatalog = () => getJson<Catalog>("/catalog");

/** Fingerprint of the deployed frontend and catalog - see BuildController. */
export const fetchBuildId = () => getJson<{ id: string }>("/build").then((b) => b.id);
/** windowMinutes null asks for the whole lot. */
export const fetchGrid = (windowMinutes: number | null) =>
  getJson<Grid>(windowMinutes ? `/grid?windowMinutes=${windowMinutes}` : "/grid");
export const fetchSettings = () => getJson<Setting[]>("/settings");

export const fetchDetail = (line: string, visionKey: string) =>
  getJson<VisionDetail>(`/vision/${encodeURIComponent(line)}/${encodeURIComponent(visionKey)}`);

/** One NG in full - measurements and images - loaded when a table row is opened. */
export const fetchOccurrence = (line: string, visionKey: string, occurrenceId: number) =>
  getJson<Occurrence>(
    `/vision/${encodeURIComponent(line)}/${encodeURIComponent(visionKey)}/defects/${occurrenceId}`
  );

/**
 * The Settings unlock token, held for this browser tab only.
 *
 * sessionStorage rather than localStorage: closing the tab locks Settings again, which
 * is what you want on a shared PC standing on the floor.
 */
const TOKEN_KEY = "visiondash.settingsToken";

export function settingsToken(): string | null {
  try {
    return window.sessionStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

/**
 * Whether the token this tab is holding is still one the server honours.
 *
 * Tokens expire on the server, so "we have a token" is not the same as "we are
 * unlocked". Asking is what keeps the password a real gate rather than a formality the
 * first visit performs once.
 */
export async function settingsSessionValid(): Promise<boolean> {
  const token = settingsToken();
  if (!token) return false;
  try {
    const response = await fetch(`${BASE}/settings/session`, {
      headers: { "X-Settings-Token": token },
    });
    if (!response.ok) clearSettingsToken();
    return response.ok;
  } catch {
    // The server being briefly unreachable is not a wrong password; ask again rather
    // than dropping a token that may well still be good.
    return false;
  }
}

export function clearSettingsToken(): void {
  try {
    window.sessionStorage.removeItem(TOKEN_KEY);
  } catch {
    // Nothing to clear if storage was blocked in the first place.
  }
}

/** Exchanges the password for a token. Throws when the password is wrong. */
export async function unlockSettings(password: string): Promise<void> {
  const response = await fetch(`${BASE}/settings/unlock`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ password }),
  });
  if (!response.ok) {
    throw new Error("wrong password");
  }
  const { token } = (await response.json()) as { token: string };
  try {
    window.sessionStorage.setItem(TOKEN_KEY, token);
  } catch {
    // A browser with storage blocked still works; it just asks again next time.
  }
}

export async function updateSetting(key: string, value: string): Promise<void> {
  const response = await fetch(`${BASE}/settings/${encodeURIComponent(key)}`, {
    method: "PUT",
    headers: {
      "Content-Type": "application/json",
      "X-Settings-Token": settingsToken() ?? "",
    },
    body: JSON.stringify({ value }),
  });
  if (!response.ok) {
    throw new Error(`Could not save ${key}: HTTP ${response.status}`);
  }
}

/* ---- AI report ---- */

/** Why the feature is or is not working, asked before anything else is rendered. */
export const fetchAiStatus = () => getJson<AiReportStatus>("/ai/status");

/** Newest first. The tab opens on the first and lists the rest as history. */
export const fetchAiReports = (limit = 24) => getJson<AiReport[]>(`/ai/reports?limit=${limit}`);

/**
 * Re-runs the window that has just closed, replacing its report.
 *
 * Behind the Settings password because it spends a minute of the GPU and overwrites a
 * stored row. It exists so that whoever installs Ollama on the central PC can prove it
 * works without waiting ten minutes to find out the model tag was wrong.
 */
export async function runAiReport(): Promise<string> {
  const response = await fetch(`${BASE}/ai/run`, {
    method: "POST",
    headers: { "X-Settings-Token": settingsToken() ?? "" },
  });
  if (response.status === 401) {
    throw new Error("Unlock Settings first - this runs the model on demand.");
  }
  if (!response.ok) {
    throw new Error(`Could not start a report: HTTP ${response.status}`);
  }
  const { message } = (await response.json()) as { started: boolean; message: string };
  return message;
}
