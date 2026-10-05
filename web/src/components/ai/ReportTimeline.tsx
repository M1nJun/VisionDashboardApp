import { useState } from "react";
import { fmtInt, fmtPct } from "../../format";
import type { AiReport } from "../../types";
import { SEVERITY, Tip, fmtClockShort, rateOf, useTip, useWidth } from "./aiVisual";

// Wide enough for the longest metric name the catalog can declare. The names come from
// the catalog at runtime, so this cannot be measured at build time - and a clipped axis
// label ("...ple B DLNG") is worse than a few spare pixels.
const GUTTER = 112;
const RIGHT = 14;
const BLOCK_H = 22;
const CHART_H = 64;
const GAP = 14;
const AXIS_H = 18;

/**
 * The last N windows on one time axis: what state each was in, and where the headline
 * rates went.
 *
 * Severity blocks and the rate lines share a column per window, so a red block and the
 * spike that caused it sit directly above one another, and one crosshair reads all of
 * them. The rates are separate charts rather than two lines on one plot - NG runs around
 * 0.2% and DLNG around 7%, and a shared axis would flatten one into the floor (a second
 * y-axis would invent a relationship between them that is not there).
 *
 * Clicking a column opens that window below. It replaces the text list of past reports,
 * which had to be read line by line to find the bad one.
 */
export default function ReportTimeline({
  reports,
  selectedId,
  onSelect,
}: {
  /** Newest first, as the API returns them. */
  reports: AiReport[];
  selectedId: number | null;
  onSelect: (id: number) => void;
}) {
  const [ref, width] = useWidth<HTMLDivElement>();
  const { tip, show, showAt, hide } = useTip();
  const [hover, setHover] = useState<number | null>(null);

  const windows = [...reports].reverse();
  const rateKeys = windows.find((r) => r.facts)?.facts?.fleet.rates ?? [];
  const n = windows.length;
  const plotW = Math.max(0, width - GUTTER - RIGHT);
  const colW = n > 0 ? plotW / n : 0;
  const height = BLOCK_H + GAP + rateKeys.length * (CHART_H + GAP) + AXIS_H;
  const cx = (i: number) => GUTTER + colW * (i + 0.5);

  // Time labels only as often as they fit, always keeping the newest.
  const labelEvery = Math.max(1, Math.ceil(46 / Math.max(colW, 1)));

  const rowsFor = (r: AiReport) => [
    { value: r.status === "SKIPPED" ? "No production" : SEVERITY[r.severity].label, label: "State",
      keyClass: `sev-key-${r.status === "SKIPPED" ? "SKIPPED" : r.severity}` },
    ...rateKeys.map((k) => ({ value: fmtPct(rateOf(r, k.key)), label: k.label })),
    { value: fmtInt(r.facts?.fleet.produced ?? null), label: "Produced" },
    { value: `${r.facts?.findings.length ?? 0}`, label: "Items" },
  ];
  const titleFor = (r: AiReport) => `${fmtClockShort(r.windowStart)} – ${fmtClockShort(r.windowEnd)}`;

  const indexAt = (clientX: number, svg: SVGSVGElement) => {
    const x = clientX - svg.getBoundingClientRect().left - GUTTER;
    return Math.min(n - 1, Math.max(0, Math.floor(x / Math.max(colW, 1))));
  };

  return (
    <section className="panel ai-timeline">
      <h2>
        Last {n} windows
        <span className="ai-sub">click a block to open that window</span>
      </h2>
      <div ref={ref} className="ai-timeline-plot">
        {width > 0 && n > 0 && (
          <svg
            width={width}
            height={height}
            onPointerMove={(e) => {
              const i = indexAt(e.clientX, e.currentTarget);
              setHover(i);
              show(e, titleFor(windows[i]), rowsFor(windows[i]));
            }}
            onPointerLeave={() => {
              setHover(null);
              hide();
            }}
            onClick={(e) => onSelect(windows[indexAt(e.clientX, e.currentTarget)].id)}
          >
            {/* severity row */}
            <text className="ai-axis-label" x={GUTTER - 8} y={BLOCK_H / 2 + 4} textAnchor="end">State</text>
            {windows.map((r, i) => (
              <rect
                key={r.id}
                x={GUTTER + colW * i + 1}
                y={0}
                width={Math.max(1, colW - 2)}
                height={BLOCK_H}
                rx={3}
                className={
                  `sev-fill-${r.status === "SKIPPED" ? "SKIPPED" : r.status === "PENDING" ? "PENDING" : r.severity}` +
                  (r.id === selectedId ? " is-selected" : "")
                }
                tabIndex={0}
                aria-label={`${titleFor(r)} ${SEVERITY[r.severity].label}`}
                onFocus={(e) => showAt(e.currentTarget, titleFor(r), rowsFor(r))}
                onBlur={hide}
                onKeyDown={(e) => {
                  if (e.key === "Enter" || e.key === " ") onSelect(r.id);
                }}
              />
            ))}

            {/* one small chart per headline rate */}
            {rateKeys.map((rate, k) => {
              const top = BLOCK_H + GAP + k * (CHART_H + GAP);
              const values = windows.map((r) => rateOf(r, rate.key));
              const max = Math.max(0, ...values.filter((v): v is number => v !== null));
              const yMax = max > 0 ? max * 1.15 : 1;
              const y = (v: number) => top + CHART_H - (v / yMax) * CHART_H;

              // Gaps where a window had no production: a line drawn through them would
              // claim a rate for windows that had none.
              const segments: string[] = [];
              let current = "";
              values.forEach((v, i) => {
                if (v === null) {
                  if (current) segments.push(current);
                  current = "";
                } else {
                  current += `${current ? "L" : "M"}${cx(i).toFixed(1)},${y(v).toFixed(1)}`;
                }
              });
              if (current) segments.push(current);

              const lastIndex = values.map((v, i) => (v === null ? -1 : i)).filter((i) => i >= 0).pop();
              const selectedIndex = windows.findIndex((r) => r.id === selectedId);

              return (
                <g key={rate.key}>
                  <text className="ai-axis-label" x={GUTTER - 8} y={top + 10} textAnchor="end">{rate.label}</text>
                  <text className="ai-tick" x={GUTTER - 8} y={top + CHART_H} textAnchor="end">0</text>
                  <text className="ai-tick" x={GUTTER - 8} y={top + CHART_H / 2 + 4} textAnchor="end">
                    {(yMax / 2).toFixed(yMax < 1 ? 2 : 1)}%
                  </text>
                  <line className="ai-grid" x1={GUTTER} x2={GUTTER + plotW} y1={top + CHART_H} y2={top + CHART_H} />
                  <line className="ai-grid" x1={GUTTER} x2={GUTTER + plotW} y1={top + CHART_H / 2} y2={top + CHART_H / 2} />
                  {segments.map((d, i) => (
                    <path key={i} d={d} className="ai-line" />
                  ))}
                  {selectedIndex >= 0 && values[selectedIndex] !== null && (
                    <circle className="ai-dot" cx={cx(selectedIndex)} cy={y(values[selectedIndex]!)} r={4.5} />
                  )}
                  {lastIndex !== undefined && (
                    <text className="ai-endlabel" x={cx(lastIndex)} y={y(values[lastIndex]!) - 9} textAnchor="end">
                      {fmtPct(values[lastIndex])}
                    </text>
                  )}
                </g>
              );
            })}

            {/* time axis */}
            {windows.map((r, i) =>
              i % labelEvery === (n - 1) % labelEvery ? (
                <text key={r.id} className="ai-tick" x={cx(i)} y={height - 4} textAnchor="middle">
                  {fmtClockShort(r.windowStart)}
                </text>
              ) : null
            )}

            {hover !== null && (
              <line className="ai-crosshair" x1={cx(hover)} x2={cx(hover)} y1={0} y2={height - AXIS_H} />
            )}
          </svg>
        )}
      </div>
      <div className="ai-legend">
        <span><i className="sev-key-ALERT" />Alert</span>
        <span><i className="sev-key-WATCH" />Watch</span>
        <span><i className="sev-key-NORMAL" />Normal</span>
        <span><i className="sev-key-SKIPPED" />No production</span>
      </div>
      <Tip tip={tip} />
    </section>
  );
}
