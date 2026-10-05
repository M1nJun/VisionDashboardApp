import { useEffect, useMemo, useState } from "react";
import { WINDOWS, windowLabel } from "./WindowFilter";
import type { DefectGroup, JudgementDef, NgEvent } from "../types";

const ALL = "ALL";

/**
 * Every NG this inspector recorded in the lot, newest first.
 *
 * Three filters, narrowing in the order an operator actually thinks: which kind of
 * verdict, then which defect within it, then how far back. The defect chips are built
 * from what the lot actually produced rather than from a fixed list, so an Example B lot
 * that never threw BURNT never offers BURNT as a filter.
 *
 * Rows open on double-click, matching the inspection PCs' own viewer.
 */
export default function NgEventsTable({
  events,
  total,
  topDefects,
  judgements,
  onOpen,
}: {
  events: NgEvent[];
  total: number;
  topDefects: DefectGroup[];
  judgements: JudgementDef[];
  /** The rows as filtered on screen, and which one was opened. The viewer steps through
   *  this list - opening the third of three matches must not drop you into all 416. */
  onOpen: (rows: NgEvent[], index: number) => void;
}) {
  // NG is what an operator opens this table to look at. On an Example B inspector the DLNG
  // rows outnumber the real defects several times over, and starting on ALL buried them.
  const [judgement, setJudgement] = useState<string>(
    () => (judgements.some((j) => j.code === "NG") ? "NG" : ALL)
  );
  const [label, setLabel] = useState<string>(ALL);
  const [windowMinutes, setWindowMinutes] = useState<number | null>(null);

  // A defect name only exists inside its judgement, so changing the judgement has to
  // clear it - otherwise the table silently shows nothing and looks broken.
  useEffect(() => {
    setLabel(ALL);
  }, [judgement]);

  const labelOptions = useMemo(() => {
    const scoped = judgement === ALL
      ? topDefects
      : topDefects.filter((d) => d.judgement === judgement);
    const byLabel = new Map<string, number>();
    for (const group of scoped) {
      byLabel.set(group.label, (byLabel.get(group.label) ?? 0) + group.count);
    }
    return [...byLabel.entries()].sort((a, b) => b[1] - a[1]);
  }, [topDefects, judgement]);

  const filtered = useMemo(() => {
    const cutoff = windowMinutes === null ? null : Date.now() - windowMinutes * 60_000;
    return events.filter((e) => {
      if (judgement !== ALL && e.judgement !== judgement) return false;
      if (label !== ALL && e.label !== label) return false;
      if (cutoff !== null) {
        const at = e.occurredAt ? new Date(e.occurredAt).getTime() : NaN;
        if (Number.isNaN(at) || at < cutoff) return false;
      }
      return true;
    });
  }, [events, judgement, label, windowMinutes]);

  return (
    <div className="panel events-panel">
      <div className="panel-head">
        <h2>NG Events</h2>
        <span className="events-count">
          {filtered.length.toLocaleString("en-GB")} shown
          {total > events.length && ` · ${total.toLocaleString("en-GB")} in lot`}
        </span>
      </div>

      {judgements.length > 1 && (
        <div className="seg inline-full judge-seg" role="group" aria-label="Judgement">
          <button type="button" className={judgement === ALL ? "on" : ""} onClick={() => setJudgement(ALL)}>
            ALL
          </button>
          {judgements.map((j) => (
            <button
              key={j.code}
              type="button"
              className={`${judgeClass(j.code)}${judgement === j.code ? " on" : ""}`}
              onClick={() => setJudgement(j.code)}
            >
              {j.code}
            </button>
          ))}
        </div>
      )}

      <div className="seg inline-full wrap" role="group" aria-label="Defect name">
        <button type="button" className={label === ALL ? "on" : ""} onClick={() => setLabel(ALL)}>
          ALL
        </button>
        {labelOptions.map(([name, count]) => (
          <button
            key={name}
            type="button"
            title={`${name} — ${count} cells`}
            className={label === name ? "on" : ""}
            onClick={() => setLabel(name)}
          >
            {name}
          </button>
        ))}
      </div>

      <div className="seg inline-full" role="group" aria-label="Time window">
        {WINDOWS.map((w) => (
          <button
            key={w.label}
            type="button"
            className={windowMinutes === w.minutes ? "on" : ""}
            onClick={() => setWindowMinutes(w.minutes)}
          >
            {w.label}
          </button>
        ))}
      </div>

      <div className="events-scroll">
        <table className="events-table">
          <thead>
            <tr>
              <th className="col-n">#</th>
              <th className="col-time">Time</th>
              <th>Cell</th>
              <th>Code</th>
              <th className="col-defect">Defect</th>
            </tr>
          </thead>
          <tbody>
            {filtered.map((event, i) => (
              <tr
                key={event.id}
                className={judgeClass(event.judgement)}
                onDoubleClick={() => onOpen(filtered, i)}
                title="Double-click to open the images"
              >
                <td className="col-n">{String(i + 1).padStart(2, "0")}</td>
                <td className="col-time">{stamp(event.occurredAt)}</td>
                <td className="mono">{event.cellId ?? "–"}</td>
                <td>
                  <span className={`judge-tag ${judgeClass(event.judgement)}`}>{event.judgement}</span>
                </td>
                <td className="col-defect" title={event.label}>{event.label}</td>
              </tr>
            ))}
          </tbody>
        </table>

        {filtered.length === 0 && (
          <div className="state-note">
            {events.length === 0
              ? "Nothing has failed in this lot."
              : `No NG matching this filter in the last ${windowLabel(windowMinutes)}.`}
          </div>
        )}
      </div>

      <div className="events-hint">Double-click a row to open its images</div>
    </div>
  );
}

/** One class per judgement, so the chip, the row's rail, the filter chip and the trend
 *  all take the same colour. C-NG loses its hyphen because a class name cannot carry one
 *  usefully. */
export function judgeClass(code: string): string {
  return `judge-${code.replace(/[^a-z0-9]/gi, "").toUpperCase()}`;
}

/**
 * The colour for a judgement, wherever it is drawn.
 *
 * Keyed on the code rather than on its position in the vision type's list: NG is red on
 * an Example E inspector for the same reason it is red on Example B, and reading the hue off
 * an index made that a coincidence rather than a rule.
 */
export function judgeColour(code: string): string {
  switch (code.replace(/[^a-z0-9]/gi, "").toUpperCase()) {
    case "NG":
      return "var(--judge-ng)";
    case "DLNG":
      return "var(--judge-dlng)";
    case "CNG":
      return "var(--judge-cng)";
    default:
      return "var(--text-dim)";
  }
}

/** Seconds included: two units failing in the same minute is common, and the operator
 *  is usually trying to line an image up against something else's timestamp. */
function stamp(iso: string | null): string {
  if (!iso) return "–";
  const d = new Date(iso);
  return Number.isNaN(d.getTime())
    ? "–"
    : d.toLocaleTimeString("en-GB", { hour12: false });
}
