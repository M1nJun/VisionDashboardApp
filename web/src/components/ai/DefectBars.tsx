import type { AiFacts } from "../../types";
import { Tip, useTip, useWidth } from "./aiVisual";

const BAR = 14;
const ROW = 30;
const LABEL_W = 170;
const MAX_ROWS = 8;

/**
 * Which defects, by name, across everything the window flagged.
 *
 * One series, so one colour for every bar and no legend - the bar length is the only
 * thing being compared. Judgement is written beside each name rather than coloured in:
 * NG, DLNG and C-NG already own red, yellow and blue elsewhere on the dashboard, and
 * reusing those here would make a DLNG bar look like a warning.
 *
 * Built from each finding's top items, so it describes what was flagged - not every
 * defect in the window. The subtitle says so.
 */
export default function DefectBars({ facts }: { facts: AiFacts }) {
  const [ref, width] = useWidth<HTMLElement>();
  const { tip, show, hide } = useTip();

  const totals = new Map<string, { name: string; side: string | null; count: number;
    judgements: Set<string>; slots: string[] }>();
  for (const f of facts.findings) {
    for (const item of f.topItems) {
      const key = `${item.name}|${item.side ?? ""}`;
      const entry = totals.get(key) ?? { name: item.name, side: item.side, count: 0,
        judgements: new Set<string>(), slots: [] };
      entry.count += item.count;
      entry.judgements.add(f.judgement);
      entry.slots.push(`${f.line} ${f.displayName} ${f.judgement} ${item.count}`);
      totals.set(key, entry);
    }
  }
  const rows = [...totals.values()].sort((a, b) => b.count - a.count).slice(0, MAX_ROWS);
  const max = Math.max(1, ...rows.map((r) => r.count));
  const plotW = Math.max(60, width - 24 - LABEL_W - 40);

  return (
    <section className="panel ai-defects" ref={ref}>
      <h2>
        Main defect types
        <span className="ai-sub">what the items above actually caught</span>
      </h2>
      {rows.length === 0 ? (
        <div className="state-note">No defect types were recorded for the items above.</div>
      ) : (
        width > 0 && (
          <svg width={width - 24} height={rows.length * ROW}>
            {rows.map((r, i) => {
              const y = i * ROW + (ROW - BAR) / 2;
              const w = Math.max(3, (r.count / max) * plotW);
              const title = `${r.name}${r.side ? ` (${r.side})` : ""}`;
              return (
                <g
                  key={title}
                  onPointerMove={(e) => show(e, title, r.slots.map((s) => ({ value: "", label: s })))}
                  onPointerLeave={hide}
                >
                  {/* hit area bigger than the bar */}
                  <rect x={0} y={i * ROW} width={width - 24} height={ROW} fill="transparent" />
                  <text className="ai-bar-label" x={LABEL_W - 10} y={y + BAR - 3} textAnchor="end">
                    {r.name}
                    {r.side && <tspan className="ai-bar-side"> {r.side}</tspan>}
                    <tspan className="ai-bar-side"> · {[...r.judgements].join("/")}</tspan>
                  </text>
                  {/* Square at the baseline, 4px round at the data end. */}
                  <path
                    className="ai-bar"
                    d={`M${LABEL_W},${y} h${w - 4} a4,4 0 0 1 4,4 v${BAR - 8} a4,4 0 0 1 -4,4 h${-(w - 4)} z`}
                  />
                  <text className="ai-endlabel" x={LABEL_W + w + 6} y={y + BAR - 3}>{r.count}</text>
                </g>
              );
            })}
          </svg>
        )
      )}
      <Tip tip={tip} />
    </section>
  );
}
