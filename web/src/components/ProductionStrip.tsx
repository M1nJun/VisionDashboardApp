import { fmtInt } from "../format";
import { useCatalog } from "../hooks/useCatalog";
import type { LineDowntime, LineProduction } from "../types";

/**
 * Output per line, against the lot's target.
 *
 * Laid out on the same column template as the grid below, so a line's card sits directly
 * above its column. Given the whole page width it was a second, unrelated row of boxes
 * that happened to be about the same seven lines.
 *
 * Every inspector on a line sees the same cells, so a line's output is one reference
 * inspector's count - not the sum of all of them, which counted each unit once per
 * station and made the old dashboard's total roughly six times the truth.
 */
export default function ProductionStrip({
  lines,
  downtime,
  columns,
}: {
  lines: LineProduction[];
  /** PD or BM per line, for the lines that are wholly stopped. */
  downtime: Map<string, LineDowntime>;
  /** The grid's column template, so the two line up exactly. */
  columns: string;
}) {
  const catalog = useCatalog();
  const shortNameOf = (key: string | null) =>
    catalog.visionTypes.find((v) => v.key === key)?.shortName ?? "–";

  return (
    <div className="production-strip">
      <div className="strip-lines" style={{ gridTemplateColumns: columns }}>
        {/* Holds the grid's row-label column open so the readouts land above their own
            columns. It stays empty: the vision names below it are what that column is
            for, and the band starts where they end. */}
        <div className="strip-gutter" />

        {lines.map((line) => {
          const missing = line.production === null;
          const made = line.production ?? 0;
          const pct = line.targetCells > 0 ? (100 * made) / line.targetCells : 0;
          const source = shortNameOf(line.sourceVisionKey);
          const stopped = downtime.get(line.line);
          return (
            <div
              key={line.line}
              className={
                `strip-line${line.estimated ? " estimated" : ""}` +
                `${missing ? " missing" : ""}${stopped ? ` stopped ${stopped.kind}` : ""}`
              }
              title={
                missing
                  ? "No reference inspector is reporting on this line"
                  : `${fmtInt(made)} of ${fmtInt(line.targetCells)} this lot (${pct.toFixed(1)}%)`
                    + (line.estimated
                      ? ` · estimated from ${source}, the preferred reference is down`
                      : ` · counted at ${source}`)
                    + (stopped ? ` · ${stopped.label}, stopped ${stopped.minutes} min` : "")
              }
            >
              {/* In the corner rather than beside the line number: a badge that appears
                  and disappears in the flow would shift the line name every time a line
                  stopped, which is exactly when you want to keep reading it. */}
              {stopped && (
                <span className={`downtime-tag ${stopped.kind}`}>
                  {stopped.kind.toUpperCase()} {stopped.minutes}m
                </span>
              )}
              <div className="line-id">{line.line}</div>
              <div className="count">{missing ? "–" : fmtInt(made)}</div>
              <div className="line-bar">
                <i style={{ width: `${Math.min(100, pct)}%` }} />
              </div>
              <div className="source">
                {missing ? "no ref" : `${pct.toFixed(0)}% · ${line.estimated ? "~" : ""}${source}`}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
