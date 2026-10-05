import { fmtPct } from "../../format";
import { useCatalog } from "../../hooks/useCatalog";
import type { AiFacts, AiFinding } from "../../types";
import { rowLabel } from "../VisionGrid";
import { Tip, VERDICT, useTip, worstVerdict, type TipRow } from "./aiVisual";

/**
 * Where the window's findings are, laid out exactly like the main grid.
 *
 * Same lines across, same inspectors down, same row names - the floor already knows
 * where "B-1, Example B Cathode" sits on the wall, so a mark in that position needs no reading.
 * Each cell takes the colour of its worst verdict, and carries the glyph and the
 * judgement codes so it never depends on colour alone.
 *
 * Above each line: how much of the window it actually ran. It sits over the column
 * because it qualifies everything below it - a red cell on a line that ran four minutes
 * is a different conversation from one on a line that ran thirty.
 */
export default function FindingsMap({
  facts,
  onPick,
}: {
  facts: AiFacts;
  /** Scrolls to the finding's evidence row. */
  onPick: (key: string) => void;
}) {
  const catalog = useCatalog();
  const { tip, show, showAt, hide } = useTip();

  const bySlot = new Map<string, AiFinding[]>();
  for (const f of facts.findings) {
    const key = `${f.line}/${f.visionKey}`;
    bySlot.set(key, [...(bySlot.get(key) ?? []), f]);
  }
  const alarmsBySlot = new Map<string, number>();
  for (const a of facts.alarms) {
    const key = `${a.line}/${a.visionKey}`;
    alarmsBySlot.set(key, (alarmsBySlot.get(key) ?? 0) + a.count);
  }
  const silentBySlot = new Map(facts.statuses.map((s) => [`${s.line}/${s.visionKey}`, s]));
  // Cells the grid is showing red. Marked so the report and the wall never disagree about
  // which cells are red, but they raise nothing on their own - almost every one is a
  // single defect on a thin window.
  const redBySlot = new Map(facts.thresholds.map((t) => [`${t.line}/${t.visionKey}`, t]));
  const lineFact = new Map(facts.lines.map((l) => [l.line, l]));

  const columns = `128px repeat(${catalog.lines.length}, minmax(0, 1fr))`;

  const rowsFor = (findings: AiFinding[], alarms: number, silent?: string,
                   red?: { ratePct: number; thresholdPct: number }): TipRow[] => [
    ...findings.map((f) => ({
      value: f.normalHigh === null
        ? `${f.count} of ${f.inspected} cells`
        : `usually ${Math.floor(f.normalLow ?? 0)}-${Math.ceil(f.normalHigh)}, now ${f.count}`,
      label: `${f.judgement} ${VERDICT[f.verdict].label}`,
      keyClass: `v-key-${f.verdict}`,
    })),
    ...(red ? [{ value: fmtPct(red.ratePct), label: `over the grid line of ${fmtPct(red.thresholdPct)}` }] : []),
    ...(alarms > 0 ? [{ value: `${alarms}`, label: "Equipment alarm" }] : []),
    ...(silent ? [{ value: silent, label: "State" }] : []),
  ];

  return (
    <section className="panel ai-map">
      <h2>
        Where
        <span className="ai-sub">same layout as the main grid · click a cell to jump to its evidence</span>
      </h2>

      <div className="ai-map-grid" style={{ gridTemplateColumns: columns }}>
        <div className="ai-map-corner" />
        {catalog.lines.map((line) => {
          const l = lineFact.get(line);
          const share = l ? l.activeMinutes / facts.windowMinutes : 0;
          return (
            <div className="ai-map-line" key={line}>
              <b>{line}</b>
              <div className="ai-meter" title={`ran ${l?.activeMinutes ?? 0} of ${facts.windowMinutes} minutes`}>
                <i style={{ width: `${Math.round(share * 100)}%` }} />
              </div>
              <span className={share === 0 ? "stopped" : ""}>
                {l ? (share === 0 ? "stopped" : `${l.activeMinutes}/${facts.windowMinutes} min`) : "–"}
                {l?.lotChanged && " · lot"}
              </span>
            </div>
          );
        })}

        {catalog.visionTypes.map((type) => {
          const label = rowLabel(type.displayName);
          return (
            <div className="ai-map-row" key={type.key} style={{ display: "contents" }}>
              <div className="ai-map-rowhead" title={type.displayName}>
                {label.name} {label.pole && <span>{label.pole}</span>}
              </div>
              {catalog.lines.map((line) => {
                const key = `${line}/${type.key}`;
                const findings = bySlot.get(key) ?? [];
                const worst = worstVerdict(findings);
                const alarms = alarmsBySlot.get(key) ?? 0;
                const silent = silentBySlot.get(key);
                const silentText = silent ? (silent.status === "OFFLINE" ? "no heartbeat" : "no production") : undefined;
                const red = redBySlot.get(key);
                const title = `${line} ${type.displayName}`;
                const interactive = findings.length > 0 || alarms > 0 || !!silent || !!red;
                return (
                  <div
                    key={key}
                    className={`ai-map-cell${worst ? ` v-cell-${worst}` : ""}${silent ? " silent" : ""}`
                      + (red && !worst ? " is-red" : "")}
                    tabIndex={interactive ? 0 : -1}
                    onPointerMove={interactive ? (e) => show(e, title, rowsFor(findings, alarms, silentText, red)) : undefined}
                    onPointerLeave={hide}
                    onFocus={interactive ? (e) => showAt(e.currentTarget, title, rowsFor(findings, alarms, silentText, red)) : undefined}
                    onBlur={hide}
                    onClick={() => findings.length && onPick(`${findings[0].line}/${findings[0].visionKey}/${findings[0].judgement}`)}
                    onKeyDown={(e) => {
                      if ((e.key === "Enter" || e.key === " ") && findings.length) {
                        onPick(`${findings[0].line}/${findings[0].visionKey}/${findings[0].judgement}`);
                      }
                    }}
                  >
                    {worst && (
                      <span className="ai-map-mark">
                        <i aria-hidden="true">{VERDICT[worst].glyph}</i>
                        {findings.map((f) => f.judgement).join(" ")}
                        {findings.some((f) => f.episodeStart && !f.isNew) && <em>open</em>}
                      </span>
                    )}
                    {!worst && red && <span className="ai-map-red">THR</span>}
                    {!worst && silent && <span className="ai-map-silent">{silent.status === "OFFLINE" ? "OFF" : "IDLE"}</span>}
                    {alarms > 0 && <span className="ai-map-alarm">AL {alarms}</span>}
                  </div>
                );
              })}
            </div>
          );
        })}
      </div>

      <div className="ai-legend">
        {(["SPIKE", "ELEVATED", "INSUFFICIENT"] as const).map((v) => (
          <span key={v}><i className={`v-key-${v}`} />{VERDICT[v].glyph} {VERDICT[v].label}</span>
        ))}
        <span className="plain">open = carried over from an earlier window</span>
        <span className="plain">THR = red on the main grid</span>
        <span className="plain">AL = equipment alarm</span>
      </div>
      <Tip tip={tip} />
    </section>
  );
}
