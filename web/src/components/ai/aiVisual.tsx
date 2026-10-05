import { useLayoutEffect, useRef, useState, type ReactNode } from "react";
import type { AiFinding, AiReport, AiSeverity, AiVerdict } from "../../types";

/**
 * Shared vocabulary for the AI report's charts.
 *
 * Verdicts and severities are STATUS, not series: a fixed scale with a reserved meaning,
 * so every mark that carries one also carries its glyph and its word. The colours were
 * stepped against the panel surface with the palette validator - the orange and yellow the
 * rest of the dashboard uses for warnings sat too close together (normal-vision ΔE 12.4)
 * to tell one level from the next at a glance.
 */
export const VERDICT: Record<AiVerdict, { label: string; glyph: string; rank: number }> = {
  SPIKE: { label: "Alert", glyph: "▲", rank: 3 },
  ELEVATED: { label: "Watch", glyph: "△", rank: 2 },
  INSUFFICIENT: { label: "Too few units", glyph: "·", rank: 1 },
};

export const VERDICT_ORDER: AiVerdict[] = ["SPIKE", "ELEVATED", "INSUFFICIENT"];

export const SEVERITY: Record<AiSeverity, { label: string }> = {
  NORMAL: { label: "Normal" },
  WATCH: { label: "Watch" },
  ALERT: { label: "Alert" },
};

/** The one headline rate a report carries under a catalog key, or null. */
export function rateOf(report: AiReport, key: string): number | null {
  const rate = report.facts?.fleet.rates.find((r) => r.key === key);
  return rate?.ratePct ?? null;
}

/** Worst verdict among findings, for colouring a cell that holds several. */
export function worstVerdict(findings: AiFinding[]): AiVerdict | null {
  let worst: AiVerdict | null = null;
  for (const f of findings) {
    if (!worst || VERDICT[f.verdict].rank > VERDICT[worst].rank) worst = f.verdict;
  }
  return worst;
}

/** Percentage points, signed, three decimals like every other rate on the dashboard. */
export const fmtPp = (delta: number): string =>
  `${delta > 0 ? "+" : delta < 0 ? "−" : "±"}${Math.abs(delta).toFixed(3)}%p`;

export const fmtClockShort = (iso: string): string => {
  const d = new Date(iso);
  return Number.isNaN(d.getTime())
    ? "–"
    : d.toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit", hour12: false });
};

/** Width of an element, kept current. SVG charts draw at real pixels so text never
 *  stretches, which a viewBox with preserveAspectRatio="none" would do. */
export function useWidth<T extends HTMLElement>() {
  const ref = useRef<T>(null);
  const [width, setWidth] = useState(0);
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    setWidth(el.clientWidth);
    const observer = new ResizeObserver((entries) => setWidth(entries[0].contentRect.width));
    observer.observe(el);
    return () => observer.disconnect();
  }, []);
  return [ref, width] as const;
}

/* ---------------- tooltip ---------------- */

export interface TipRow {
  value: string;
  label: string;
  /** A short key stroke in a status colour, when the row is about a verdict. */
  keyClass?: string;
}

export interface TipState {
  x: number;
  y: number;
  title: string;
  rows: TipRow[];
}

/**
 * One floating readout for a chart. Values lead and labels follow - the reader already
 * knows which mark they are on and wants the number. Everything shown here is also in the
 * detail table below, so the tooltip enhances and never gates.
 */
export function useTip() {
  const [tip, setTip] = useState<TipState | null>(null);
  const show = (event: { clientX: number; clientY: number }, title: string, rows: TipRow[]) =>
    setTip({ x: event.clientX, y: event.clientY, title, rows });
  /** For keyboard focus, which has no pointer position: anchor to the element. */
  const showAt = (el: Element, title: string, rows: TipRow[]) => {
    const r = el.getBoundingClientRect();
    setTip({ x: r.left + r.width / 2, y: r.bottom, title, rows });
  };
  const hide = () => setTip(null);
  return { tip, show, showAt, hide };
}

export function Tip({ tip }: { tip: TipState | null }): ReactNode {
  if (!tip) return null;
  // Kept on screen: flipped left/up when it would run off the edge.
  const flipX = tip.x > window.innerWidth - 280;
  const flipY = tip.y > window.innerHeight - 180;
  const style = {
    left: flipX ? undefined : tip.x + 14,
    right: flipX ? window.innerWidth - tip.x + 14 : undefined,
    top: flipY ? undefined : tip.y + 14,
    bottom: flipY ? window.innerHeight - tip.y + 14 : undefined,
  };
  return (
    <div className="ai-tip" style={style} role="tooltip">
      <div className="ai-tip-title">{tip.title}</div>
      {tip.rows.map((row, i) => (
        <div className="ai-tip-row" key={i}>
          {row.keyClass && <i className={`ai-tip-key ${row.keyClass}`} />}
          <b>{row.value}</b>
          <span>{row.label}</span>
        </div>
      ))}
    </div>
  );
}

/** The verdict word with its glyph - status is never carried by colour alone. */
export function VerdictChip({ verdict }: { verdict: AiVerdict }) {
  const v = VERDICT[verdict];
  return (
    <span className={`ai-vchip v-${verdict}`}>
      <span aria-hidden="true">{v.glyph}</span> {v.label}
    </span>
  );
}

/**
 * When an alert started, in the terms somebody standing at the screen uses.
 *
 * The report used to count windows - "4 windows in a row" - which asked the reader to know how long
 * a report window is and multiply. A clock time needs neither, and stays true if the
 * interval is ever changed.
 *
 * The date appears as soon as the start is not today, rather than after some number of
 * hours: at 09:30 a bare "19:05" reads as this morning, and the whole point of the line is
 * to say that this has been running since last night.
 */
export function fmtSince(iso: string): string {
  const then = new Date(iso);
  if (Number.isNaN(then.getTime())) return "–";
  const clock = then.toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit", hour12: false });
  const now = new Date();
  const midnight = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  if (then >= midnight) return clock;
  const yesterday = new Date(midnight.getTime() - 86_400_000);
  if (then >= yesterday) return `yesterday ${clock}`;
  const days = Math.ceil((midnight.getTime() - then.getTime()) / 86_400_000);
  const date = then.toLocaleDateString("en-GB", { month: "short", day: "numeric" });
  return `${date} ${clock} (${days}d ago)`;
}
