import { fmtInt } from "../../format";
import type { AiFacts, AiFinding } from "../../types";
import { Tip, VerdictChip, fmtSince, useTip, useWidth } from "./aiVisual";

const TRACK_H = 34;
/** Must match .ai-ev-row in theme.css: its two fixed columns, two 12px gaps, panel padding. */
const LABEL_W = 250;
const COUNT_W = 128;
const ROW_GAPS = 24 + 28;

/**
 * Each finding as one sentence: what this much production normally yields, and what it
 * yielded.
 *
 * This card used to plot rates with a confidence interval and a p-value. Both were
 * correct and neither was checkable by the person reading them - "conservatively
 * 95%" tells you nothing about what was conservative about it. A band drawn in defects
 * is the same judgement in the unit the floor already works in: the grey bar is what
 * normal looks like for this inspector at this production, and the dot is where the
 * window landed.
 *
 * The bar's width is the part worth noticing. It is wide for Example B DLNG because that
 * inspector genuinely swings that far between half-hours, and narrow for an inspector
 * that almost never produces a defect - which is why one NG can sit outside a narrow band
 * while twenty DLNG sit inside a wide one.
 */
export default function EvidenceChart({ facts, focusKey }: { facts: AiFacts; focusKey: string | null }) {
  const [ref, width] = useWidth<HTMLElement>();
  const { tip, show, hide } = useTip();
  const trackW = Math.max(120, width - LABEL_W - COUNT_W - ROW_GAPS);

  return (
    <section className="panel ai-evidence" ref={ref}>
      <h2>
        Compared with usual
        <span className="ai-sub">
          usual level = last {facts.baselineHours}h · range = how far this inspector swings over {facts.spreadDays} days
        </span>
      </h2>
      <div className="ai-legend ai-legend-top">
        <span>
          <svg width="30" height="10"><rect x="0" y="2" width="30" height="6" rx="3" className="ai-band-key" /></svg>
          usual range
        </span>
        <span>
          <svg width="12" height="12"><line x1="6" x2="6" y1="1" y2="11" className="ai-expect-key" /></svg>
          usual level
        </span>
        <span>
          <svg width="12" height="12"><circle cx="6" cy="6" r="4.5" className="ai-now-key" /></svg>
          this window
        </span>
      </div>

      {facts.findings.length === 0 ? (
        <div className="state-note">Every inspector stayed inside its usual range.</div>
      ) : (
        <div className="ai-ev-rows">
          {facts.findings.map((f) => {
            const key = `${f.line}/${f.visionKey}/${f.judgement}`;
            return (
              <div key={key} id={`ev-${key}`} className={`ai-ev-row${focusKey === key ? " is-focus" : ""}`}>
                <div className="ai-ev-label">
                  <div className="ai-ev-who">
                    <b>{f.line}</b> {f.displayName} <span className="judgement">{f.judgement}</span>
                  </div>
                  <VerdictChip verdict={f.verdict} />
                  {f.episodeStart && (
                    <span className="ai-since">
                      {f.isNew
                        ? `since ${fmtSince(f.episodeStart)} · raised now`
                        : `open since ${fmtSince(f.episodeStart)}`}
                    </span>
                  )}
                </div>
                <div className="ai-ev-track">
                  {f.normalHigh === null ? (
                    <div className="ai-ev-thin">
                      Too few units — {f.count} in {fmtInt(f.inspected)} cells; no usual range was computed
                    </div>
                  ) : (
                    <Track finding={f} width={trackW} onHover={show} onLeave={hide} />
                  )}
                </div>
                <div className="ai-ev-count">
                  {f.normalHigh === null ? (
                    <>
                      <b>{fmtInt(f.count)}</b>
                      <span>{fmtInt(f.inspected)} cells</span>
                    </>
                  ) : (
                    <>
                      <span className="normal">usually {bandText(f)}</span>
                      <b>now {fmtInt(f.count)}</b>
                      <span>{fmtInt(f.inspected)} cells · {f.activeMinutes} min</span>
                    </>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      )}
      <Tip tip={tip} />
    </section>
  );
}

export function bandText(f: AiFinding): string {
  return `${Math.floor(f.normalLow ?? 0)}-${Math.ceil(f.normalHigh ?? 0)}`;
}

function Track({
  finding: f,
  width,
  onHover,
  onLeave,
}: {
  finding: AiFinding;
  width: number;
  onHover: (e: { clientX: number; clientY: number }, title: string, rows: { value: string; label: string }[]) => void;
  onLeave: () => void;
}) {
  const high = f.normalHigh ?? 0;
  const low = f.normalLow ?? 0;
  const expected = f.expected ?? 0;
  // Room past whichever is further out, so the count's label never sits on the band.
  const max = Math.max(f.count, high) * 1.25 || 1;
  const pad = 8;
  const x = (v: number) => pad + (v / max) * (width - pad * 2);
  const mid = TRACK_H / 2;
  const inside = f.count <= high;

  const rows = [
    { value: `${fmtInt(f.count)}`, label: "this window" },
    { value: bandText(f), label: "usual range" },
    { value: `${expected.toFixed(1)}`, label: "usual level" },
    { value: `x${(f.spread ?? 1).toFixed(1)}`, label: "this inspector usual swing" },
    { value: `${fmtInt(f.inspected)}`, label: "cells checked" },
  ];

  const labelRight = x(f.count) + 58 < width;

  return (
    <svg
      width={width}
      height={TRACK_H}
      onPointerMove={(e) => onHover(e, `${f.line} ${f.displayName} ${f.judgement}`, rows)}
      onPointerLeave={onLeave}
    >
      <line className="ai-grid" x1={pad} x2={width - pad} y1={mid} y2={mid} />
      {/* What normal looks like here: the whole point of the row. */}
      <rect
        className="ai-band"
        x={x(low)}
        y={mid - 7}
        width={Math.max(3, x(high) - x(low))}
        height={14}
        rx={4}
      />
      <line className="ai-expect" x1={x(expected)} x2={x(expected)} y1={mid - 9} y2={mid + 9} />
      <circle
        className={`ai-now v-dot-${inside ? "INSIDE" : f.verdict}`}
        cx={x(f.count)}
        cy={mid}
        r={6}
      />
      <text
        className="ai-endlabel"
        x={labelRight ? x(f.count) + 11 : x(f.count)}
        y={labelRight ? mid + 4 : mid - 12}
        textAnchor={labelRight ? "start" : "middle"}
      >
        {fmtInt(f.count)}
      </text>
    </svg>
  );
}
