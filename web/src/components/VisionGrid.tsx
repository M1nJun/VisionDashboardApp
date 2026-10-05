import { fmtPct, primaryJudgement } from "../format";
import { useCatalog } from "../hooks/useCatalog";
import type { GridCell, LineDowntime } from "../types";

/**
 * Lines across, vision types down. The row order and the set of vision types both come
 * from the catalog, so adding an inspection stage is a catalog edit rather than a
 * frontend change.
 */
export default function VisionGrid({
  cells,
  downtime,
  columns,
  onSelect,
}: {
  cells: GridCell[];
  /** Lines where every inspector has stopped; the outline is coloured by the kind. */
  downtime: Map<string, LineDowntime>;
  // minmax(0, 1fr), not a pixel minimum: the plant is a fixed set of lines by seven
  // inspectors and has to fit the screen, so the columns share the width they are given
  // rather than forcing the page sideways. Shared with the strip above so the two line up.
  columns: string;
  onSelect: (line: string, visionKey: string) => void;
}) {
  const catalog = useCatalog();
  const bySlot = new Map(cells.map((c) => [`${c.line}/${c.visionKey}`, c]));
  // Rows are stated explicitly on both this grid and the overlay below, so the two stay
  // aligned once the height is shared out. No header row: the yield card above each
  // column carries its line number, so a second row of them was the same fact twice.
  const rows = `repeat(${catalog.visionTypes.length}, minmax(0, 1fr))`;

  return (
    <div className="grid-wrap">
      <div className="grid-table" style={{ gridTemplateColumns: columns, gridTemplateRows: rows }}>
        {catalog.visionTypes.map((type, i) => (
          <Row
            key={type.key}
            label={rowLabel(type.displayName)}
            title={type.displayName}
            lines={catalog.lines}
            get={(line) => bySlot.get(`${line}/${type.key}`)}
            onSelect={onSelect}
            last={i === catalog.visionTypes.length - 1}
          />
        ))}
      </div>

      {/* Separate grid on purpose - see the note on .grid-overlay in theme.css.
          Every item states its own column and row. Mixing a definite row with an auto
          column let grid auto-placement decide where each outline landed, and it put
          them one column to the left: a line whose inspectors were all running was
          outlined while the idle line beside it was not. */}
      <div className="grid-overlay" style={{ gridTemplateColumns: columns, gridTemplateRows: rows }}>
        {catalog.lines.map((line, i) => {
          const stopped = downtime.get(line);
          return stopped ? (
            <div
              key={line}
              className={`idle-column ${stopped.kind}`}
              style={{ gridColumn: i + 2, gridRow: `1 / span ${catalog.visionTypes.length}` }}
            />
          ) : null;
        })}
      </div>
    </div>
  );
}

/**
 * The inspector's own name, not an abbreviation. There is room for it in the label
 * column, and "LDA-C" needed decoding every time. "Vision" is dropped because every row
 * is one - it is the column, not the distinction.
 */
export function rowLabel(displayName: string): { name: string; pole: string | null } {
  const bare = displayName.replace(/\s+Vision$/i, "");
  // Four of the seven rows are anode/cathode pairs, and the polarity is the whole
  // difference between them. Written as the sign, which is how the inspection PCs
  // already name their own files - MDL_ANODE(-), MDL_CATHODE(+) - so the screen and the
  // folder an operator opens next agree with each other.
  const pair = bare.match(/^(.*?)\s+(Anode|Cathode)$/i);
  if (!pair) return { name: bare, pole: null };
  return { name: pair[1], pole: /anode/i.test(pair[2]) ? "(-)" : "(+)" };
}

function Row({
  label,
  title,
  lines,
  get,
  onSelect,
  last,
}: {
  label: { name: string; pole: string | null };
  title: string;
  lines: string[];
  get: (line: string) => GridCell | undefined;
  onSelect: (line: string, visionKey: string) => void;
  /** The bottom of the band, so no rule is drawn under it. */
  last: boolean;
}) {
  return (
    <>
      <div className={`grid-row-head${last ? " last" : ""}`} title={title}>
        <span className="row-name">{label.name}</span>
        {label.pole && <span className="row-pole">{label.pole}</span>}
      </div>
      {lines.map((line) => {
        const cell = get(line);
        return cell ? (
          <Cell key={line} cell={cell} onSelect={onSelect} />
        ) : (
          <div key={line} className="cell blank" />
        );
      })}
    </>
  );
}

function Cell({ cell, onSelect }: { cell: GridCell; onSelect: (line: string, visionKey: string) => void }) {
  const deployed = cell.status !== "NOT_DEPLOYED";
  const primary = primaryJudgement(cell.judgements);
  // Everything else the vision type reports stays a plain grey figure. One colour
  // signal per card; the rest are facts, not warnings.
  const others = cell.judgements.filter((j) => !j.drivesColor);

  return (
    <div
      className={`cell color-${cell.colorLevel}${deployed ? "" : " blank"}`}
      onClick={() => deployed && onSelect(cell.line, cell.visionKey)}
      title={`${cell.line} ${cell.displayName}`}
    >
      {deployed ? (
        <div className={`rate${primary?.ratePct === null || primary === undefined ? " none" : ""}`}>
          {/* Nothing inspected inside the window is a dash, not 0.000%: the inspector was
              stopped, which is a different fact from a clean run. */}
          {cell.windowEmpty
            ? "–"
            : primary?.ratePct === null || primary === undefined
              ? "no data"
              : fmtPct(primary.ratePct)}
        </div>
      ) : (
        <div className="rate none">N/D</div>
      )}

      {deployed && !cell.windowEmpty && others.length > 0 && (
        <div className="extra">
          {others.map((j) => (
            <span key={j.code}>
              {j.code} {j.count}
            </span>
          ))}
        </div>
      )}

      <div className="foot">
        <span className={`live ${cell.status}`}>{shortStatus(cell.status)}</span>
        {/* The inspector's own alarm log. Called BM until PD/BM came to mean whether a
            stopped line was planned - two different things under one name. */}
        {cell.alarmCount ? <span className="alarm">ALARM {cell.alarmCount}</span> : <span />}
      </div>
    </div>
  );
}

function shortStatus(status: GridCell["status"]): string {
  switch (status) {
    case "RUNNING":
      return "RUN";
    case "IDLE":
      return "IDLE";
    case "OFFLINE":
      return "OFF";
    default:
      return "N/D";
  }
}
