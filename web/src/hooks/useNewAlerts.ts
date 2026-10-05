import { useEffect, useRef, useState } from "react";
import { fetchAiReports, fetchAiStatus } from "../api";
import type { AiFinding, AiReport, AiReportStatus } from "../types";

/** The last report this browser has already interrupted somebody about. */
const SEEN_KEY = "visiondash.aiPopupSeenReport";

export interface NewAlerts {
  report: AiReport;
  /** Alerts confirmed in this window - never the ones that were already running. */
  findings: AiFinding[];
}

/**
 * Watches for alerts that have just been raised, so the floor hears about one without
 * having to be on the AI REPORT tab.
 *
 * Three rules keep this from becoming wallpaper:
 *
 * 1. Only newly confirmed alerts. An alert that has been running since yesterday evening
 *    is news once, and it was news yesterday. `isNew` is the server's word for "this is
 *    the window it was confirmed in", so a four-day fault interrupts nobody on day two.
 * 2. Nothing on the first poll. Whatever the newest report is when a screen opens is
 *    recorded as seen and not shown - otherwise every refresh reopens an alert somebody
 *    already dealt with twenty minutes ago.
 * 3. One report at a time. A newer report replaces whatever is on screen rather than
 *    queueing behind it, so a display nobody is standing at cannot accumulate a stack.
 *
 * The seen marker is per browser and survives a reload. It is deliberately not on the
 * server: two people at two screens should each be told once, and neither of them
 * dismissing it should silence the other.
 *
 * @param suppressed true while the reader is already looking at the report this would be
 *                   about. The window still counts as seen - it has been.
 * @param intervalMs how often to look. Half a minute on the floor; the demo build asks
 *                   more often so a visitor sees an alert arrive rather than reading
 *                   that one would.
 */
export function useNewAlerts(
  suppressed = false,
  intervalMs = 30_000,
): { alerts: NewAlerts | null; dismiss: () => void } {
  const [alerts, setAlerts] = useState<NewAlerts | null>(null);
  const seen = useRef<number | null>(readSeen());
  const enabled = useRef(true);
  const quiet = useRef(suppressed);
  quiet.current = suppressed;

  useEffect(() => {
    let stopped = false;

    const check = async () => {
      try {
        // Cheap enough to sit behind every tab: one row, and the tab that is open polls
        // its own twenty-four regardless.
        const status = await fetchAiStatus().catch(() => null as AiReportStatus | null);
        if (stopped) return;
        if (status) {
          enabled.current = status.popupEnabled !== false;
          // Nothing to poll for, and asking every thirty seconds would fill the console
          // of a server where the migration has simply not been run yet.
          if (!status.schemaReady) return;
        }

        const reports = await fetchAiReports(1);
        if (stopped) return;
        const report = reports[0];
        if (!report || report.id === seen.current) return;

        const first = seen.current === null;
        seen.current = report.id;
        writeSeen(report.id);
        // Suppressed still marks it seen: somebody reading the AI tab when the report
        // landed has seen it, and should not be shown it again on their way back to GRID.
        if (first || !enabled.current || quiet.current) return;

        const fresh = report.facts?.findings.filter((f) => f.verdict === "SPIKE" && f.isNew) ?? [];
        if (fresh.length > 0) {
          setAlerts({ report, findings: fresh });
        }
      } catch {
        // Server restarting, or the migration has not been run. The dashboard carries on.
      }
    };

    void check();
    const timer = window.setInterval(check, intervalMs);
    return () => {
      stopped = true;
      window.clearInterval(timer);
    };
  }, [intervalMs]);

  return { alerts, dismiss: () => setAlerts(null) };
}

function readSeen(): number | null {
  try {
    const raw = window.localStorage.getItem(SEEN_KEY);
    return raw === null ? null : Number(raw) || null;
  } catch {
    // Storage blocked. The first poll still baselines, in memory, for this visit.
    return null;
  }
}

function writeSeen(id: number) {
  try {
    window.localStorage.setItem(SEEN_KEY, String(id));
  } catch {
    // Same alert may reappear after a reload. Better than not showing it at all.
  }
}
