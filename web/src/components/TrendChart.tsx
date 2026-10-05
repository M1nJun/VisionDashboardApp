import { useMemo, useRef, useState } from "react";
import type { TrendPoint } from "../types";

export interface TrendRef {
  label: string;
  value: number;
  stroke: string;
}

/**
 * How this lot is running: how much failed, and whether the rate is inside its limits.
 *
 * The line is the rate **for the lot so far**, not for each bucket on its own. A bucket
 * that happened to inspect three cells and fail one is 33%, and a per-bucket line spent
 * its whole range on noise like that - the warn and critical marks, at 0.05% and 0.1%,
 * were pressed flat against the axis and told the operator nothing. Accumulated, the
 * line is the number the lot is actually judged on, it moves slowly, and it sits on the
 * same scale as its limits.
 *
 * The bars keep the per-bucket detail the line smooths away: when failures clustered.
 *
 * Three visual roles, three clearly different treatments - solid bars for counts, one
 * bright line for the rate, dashed marks for the limits. They used to be three shades of
 * the same red.
 */
export default function TrendChart({
  points,
  judgement,
  refs,
  colour,
}: {
  points: TrendPoint[];
  judgement: string;
  /** Limits for this judgement, on the rate axis. Empty draws none. */
  refs: TrendRef[];
  /** The judgement's own colour. Fixed per kind of verdict rather than driven by how
   *  the rate is doing - the limits already say that, and a line that changed hue as it
   *  crossed one was a third signal competing with them. */
  colour: string;
}) {
  const width = 720;
  const height = 300;
  const pad = { top: 16, right: 58, bottom: 26, left: 44 };
  const plotW = width - pad.left - pad.right;
  const plotH = height - pad.top - pad.bottom;

  const svgRef = useRef<SVGSVGElement>(null);
  const [hover, setHover] = useState<number | null>(null);

  const counts = useMemo(
    () => points.map((p) => p.judgements?.[judgement] ?? 0),
    [points, judgement]
  );

  /** Rate from the lot's first bucket up to and including each one. */
  const cumulative = useMemo(() => {
    let failed = 0;
    let inspected = 0;
    return points.map((p, i) => {
      failed += counts[i] ?? 0;
      inspected += p.inspected;
      return inspected > 0 ? (100 * failed) / inspected : 0;
    });
  }, [points, counts]);

  const warn = refs.find((r) => r.label === "warn")?.value ?? null;
  const crit = refs.find((r) => r.label === "crit")?.value ?? null;

  // Always leave room above the highest limit, so warn and crit are on screen even when
  // the lot is running clean - "how close am I" is the question the chart exists for.
  const limitCeiling = refs.length > 0 ? Math.max(...refs.map((r) => r.value)) * 1.4 : 0;
  const ratePeak = Math.max(limitCeiling, Math.max(...cumulative, 0) * 1.3, 0.0001);
  const countPeak = Math.max(...counts, 1);

  const step = points.length <= 1 ? plotW : plotW / (points.length - 1);
  const x = (i: number) => (points.length <= 1 ? plotW / 2 : i * step);
  const yRate = (v: number) => plotH - Math.min(v / ratePeak, 1) * plotH;
  const yCount = (v: number) => plotH - (v / countPeak) * plotH;
  const barW = Math.max(3, Math.min(22, (points.length <= 1 ? plotW : step) * 0.5));

  // The lot's clock, marked every hour from where it started. Reading two end labels
  // told you the span but never where in it anything happened; an hour is the unit
  // shift work is actually talked about in.
  const hours = useMemo(() => {
    if (points.length === 0) return [];
    const times = points.map((p) => new Date(p.bucketStart).getTime());
    const first = times[0];
    const last = times[times.length - 1];
    if (!Number.isFinite(first) || !Number.isFinite(last)) return [];

    // Whole hours on the wall clock, not "one hour after the lot started" - an operator
    // reads these against the clock on the wall.
    const marks: { at: number; index: number }[] = [];
    const start = new Date(first);
    start.setMinutes(0, 0, 0);
    for (let at = start.getTime(); at <= last; at += 3_600_000) {
      if (at <= first) continue;
      marks.push({ at, index: indexAt(times, at) });
    }
    return marks;
  }, [points]);

  const line = cumulative
    .map((v, i) => `${i === 0 ? "M" : "L"}${x(i).toFixed(1)},${yRate(v).toFixed(1)}`)
    .join(" ");

  if (points.length === 0) {
    return <div className="state-note">No production recorded for this lot yet.</div>;
  }

  const now = cumulative[cumulative.length - 1];
  const id = `trend-${judgement.replace(/[^a-z0-9]/gi, "")}`;

  const onMove = (event: React.PointerEvent<SVGSVGElement>) => {
    const svg = svgRef.current;
    if (!svg) return;
    const box = svg.getBoundingClientRect();
    if (box.width === 0) return;
    const local = ((event.clientX - box.left) / box.width) * width - pad.left;
    setHover(Math.min(points.length - 1, Math.max(0, Math.round(local / step))));
  };

  return (
    <div className="trend-wrap">
      {/* The one number the chart is about, stated rather than left to be read off an
          axis, and coloured by where it stands against its limits. */}
      <div className="trend-head">
        <div className="trend-now-rate">
          <span className="rate-value" style={{ color: colour }}>{now.toFixed(3)}%</span>
          <span className="rate-caption">{judgement} rate · lot to date</span>
        </div>
        <div className="trend-legend">
          <span className="trend-key bars"><i />{judgement} per {bucketLabel(points)}</span>
          <span className="trend-key rate" style={{ color: colour }}>
            <i style={{ background: colour }} />lot rate
          </span>
          {refs.map((ref) => (
            <span key={ref.label} className={`trend-key limit ${ref.label}`}>
              <i />{ref.label} {ref.value}%
            </span>
          ))}
        </div>
      </div>

      <svg
        ref={svgRef}
        className="trend"
        viewBox={`0 0 ${width} ${height}`}
        role="img"
        aria-label={`${judgement} rate across the current lot`}
        onPointerMove={onMove}
        onPointerLeave={() => setHover(null)}
      >
        <defs>
          <linearGradient id={`${id}-line`} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor={colour} stopOpacity="0.28" />
            <stop offset="100%" stopColor={colour} stopOpacity="0" />
          </linearGradient>
          <filter id={`${id}-glow`} x="-20%" y="-40%" width="140%" height="180%">
            <feGaussianBlur stdDeviation="2.4" result="b" />
            <feMerge><feMergeNode in="b" /><feMergeNode in="SourceGraphic" /></feMerge>
          </filter>
        </defs>

        <g transform={`translate(${pad.left},${pad.top})`}>
          {/* Bands rather than bare lines: "how much headroom is left" reads instantly. */}
          {warn !== null && crit !== null && crit <= ratePeak && (
            <rect className="band-warn" x={0} y={yRate(crit)} width={plotW}
                  height={Math.max(0, yRate(warn) - yRate(crit))} />
          )}
          {crit !== null && crit <= ratePeak && (
            <rect className="band-crit" x={0} y={0} width={plotW} height={yRate(crit)} />
          )}

          {[0.25, 0.5, 0.75].map((f) => (
            <line key={f} className="grid-line" x1={0} x2={plotW} y1={plotH * f} y2={plotH * f} />
          ))}
          <line className="grid-base" x1={0} x2={plotW} y1={plotH} y2={plotH} />

          {counts.map((c, i) =>
            c > 0 ? (
              <rect key={i} className="trend-bar" x={x(i) - barW / 2} y={yCount(c)}
                    width={barW} height={plotH - yCount(c)} rx="1.5"
                    style={{ transformOrigin: `${x(i)}px ${plotH}px`, animationDelay: `${i * 14}ms` }} />
            ) : null
          )}

          {refs.map((ref) =>
            ref.value <= ratePeak ? (
              <g key={ref.label}>
                <line className={`limit-line ${ref.label}`} x1={0} x2={plotW}
                      y1={yRate(ref.value)} y2={yRate(ref.value)} />
                <text className={`limit-label ${ref.label}`} x={plotW + 6} y={yRate(ref.value) + 3}>
                  {ref.label}
                </text>
              </g>
            ) : null
          )}

          <path className="trend-fill" d={`${line} L${x(cumulative.length - 1)},${plotH} L${x(0)},${plotH} Z`}
                fill={`url(#${id}-line)`} />
          <path className="trend-line" d={line} pathLength={1} stroke={colour}
                filter={`url(#${id}-glow)`} />
          <circle className="trend-now" cx={x(cumulative.length - 1)}
                  cy={yRate(now)} r="3.6" fill={colour} />

          {hover !== null && (
            <g className="trend-cursor">
              <line x1={x(hover)} x2={x(hover)} y1={0} y2={plotH} stroke={colour} />
              <circle cx={x(hover)} cy={yRate(cumulative[hover])} r="4.5" stroke={colour} />
            </g>
          )}

          {[1, 0.5, 0].map((f) => (
            <text key={`c${f}`} className="trend-axis count" x={-8}
                  y={plotH - plotH * f + 3} textAnchor="end">
              {Math.round(countPeak * f)}
            </text>
          ))}
          {[1, 0.5].map((f) => (
            <text key={`r${f}`} className="trend-axis rate" x={plotW + 6} y={plotH - plotH * f + 3}>
              {(ratePeak * f).toFixed(2)}%
            </text>
          ))}

          {/* Lot start is always named; the hours after it are marked where they fall. */}
          <text className="trend-axis" x={0} y={plotH + 16}>{clock(points[0].bucketStart)}</text>
          {/* A lot that started at 12:58 would print its start label right under the 13:00
              mark, so a mark with no room beside the start is dropped rather than drawn
              over it. */}
          {hours.filter((mark) => x(mark.index) > 36).map((mark) => (
            <g key={mark.at}>
              <line className="trend-hour" x1={x(mark.index)} x2={x(mark.index)} y1={0} y2={plotH} />
              <text className="trend-axis" x={x(mark.index)} y={plotH + 16} textAnchor="middle">
                {clockOf(mark.at)}
              </text>
            </g>
          ))}
          {/* Only worth printing while no hour has passed - otherwise it crowds the last mark. */}
          {hours.length === 0 && points.length > 1 && (
            <text className="trend-axis" x={plotW} y={plotH + 16} textAnchor="end">
              {clock(points[points.length - 1].bucketStart)}
            </text>
          )}
        </g>
      </svg>

      {hover !== null && (
        <div
          className="trend-tip"
          style={{
            left: `${((pad.left + x(hover)) / width) * 100}%`,
            top: `${((pad.top + yRate(cumulative[hover])) / height) * 100}%`,
          }}
        >
          <span className="tip-time">{clock(points[hover].bucketStart)}</span>
          <span className="tip-rate" style={{ color: colour }}>{cumulative[hover].toFixed(3)}%</span>
          <span className="tip-sub">lot rate to this point</span>
          <span className="tip-sub">
            {counts[hover]} {judgement} of {points[hover].inspected.toLocaleString("en-GB")} here
          </span>
        </div>
      )}
    </div>
  );
}

/** Bucket width, worked out from the data rather than assumed, so the bar legend is
 *  honest when the trend setting changes. */
function bucketLabel(points: TrendPoint[]): string {
  if (points.length < 2) return "bucket";
  const minutes = Math.round(
    (new Date(points[1].bucketStart).getTime() - new Date(points[0].bucketStart).getTime()) / 60000
  );
  if (!Number.isFinite(minutes) || minutes <= 0) return "bucket";
  return minutes >= 60 ? `${Math.round(minutes / 60)}h` : `${minutes}m`;
}

function clock(iso: string): string {
  return clockOf(new Date(iso).getTime());
}

function clockOf(at: number): string {
  return Number.isNaN(at)
    ? "–"
    : new Date(at).toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit", hour12: false });
}

/**
 * Where a moment sits on the index axis, as a fraction between the two buckets around
 * it. The plot spaces buckets evenly by index rather than by time, so an hour mark has
 * to be interpolated - placing it at bucketMinutes arithmetic would drift the moment
 * the server has a gap in its buckets.
 */
function indexAt(times: number[], at: number): number {
  if (times.length <= 1) return 0;
  for (let i = 1; i < times.length; i++) {
    if (times[i] < at) continue;
    const span = times[i] - times[i - 1];
    return span > 0 ? i - 1 + (at - times[i - 1]) / span : i;
  }
  return times.length - 1;
}
