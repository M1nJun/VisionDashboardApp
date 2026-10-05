import { useMemo } from "react";
import { useNavigate } from "react-router-dom";
import { fetchGrid } from "../api";
import ProductionStrip from "../components/ProductionStrip";
import RankPanel from "../components/RankPanel";
import VisionGrid from "../components/VisionGrid";
import { fmtInt, fmtPct } from "../format";
import { useCatalog } from "../hooks/useCatalog";
import { usePolling } from "../hooks/usePolling";
import { useWindowScope } from "../hooks/useWindow";
import type { LineDowntime } from "../types";

export default function GridPage() {
  const catalog = useCatalog();
  const navigate = useNavigate();
  const pollMs = catalog.pollIntervalSeconds * 1000;
  const { minutes: windowMinutes } = useWindowScope();
  const { data, error, loading } = usePolling(
    () => fetchGrid(windowMinutes),
    pollMs,
    [pollMs, windowMinutes]
  );

  /**
   * Lines where every inspector has stopped, and for how long.
   *
   * A line is down when all of its inspectors are idle - not when any one of them is.
   * One station pausing while the rest keep running is normal. A line with nothing
   * deployed is not down, it is absent.
   *
   * The clock starts at the last unit any of them inspected, and how long it has run
   * decides what kind of stop this is: a changeover is planned and expected, a stop that
   * outlasts one is a breakdown. Where the boundary sits is a Settings value, because it
   * is a plant decision rather than a property of the software.
   */
  const downtime = useMemo(() => {
    const seen = new Map<string, { total: number; idle: number; lastEvent: number }>();
    for (const cell of data?.cells ?? []) {
      if (cell.status === "NOT_DEPLOYED") continue;
      const line = seen.get(cell.line) ?? { total: 0, idle: 0, lastEvent: 0 };
      line.total += 1;
      if (cell.status === "IDLE") line.idle += 1;
      const at = cell.lastEventAt ? new Date(cell.lastEventAt).getTime() : 0;
      if (Number.isFinite(at)) line.lastEvent = Math.max(line.lastEvent, at);
      seen.set(cell.line, line);
    }

    const out = new Map<string, LineDowntime>();
    for (const [line, counts] of seen) {
      if (counts.total === 0 || counts.idle !== counts.total) continue;
      const minutes = counts.lastEvent > 0
        ? Math.max(0, Math.round((Date.now() - counts.lastEvent) / 60000))
        : 0;
      const planned = minutes <= catalog.plannedDowntimeMaxMinutes;
      out.set(line, {
        kind: planned ? "pd" : "bm",
        label: planned ? "Planned downtime" : "Breakdown",
        minutes,
      });
    }
    return out;
  }, [data, catalog.plannedDowntimeMaxMinutes]);

  if (loading && !data) {
    return <div className="page"><div className="state-note">Loading…</div></div>;
  }
  if (!data) {
    return <div className="page"><div className="state-note">Cannot load the grid. {error}</div></div>;
  }

  const { totals } = data;
  const achieved = totals.targetCells > 0 ? (100 * totals.production) / totals.targetCells : 0;

  // One template for the strip and the grid, so a line's card sits over its column.
  // Named once: the grid's row labels sit in it, and the yield band has to know where
  // it ends so it can start after it.
  const labelColumn = "152px";
  const gridColumns = `${labelColumn} repeat(${catalog.lines.length}, minmax(0, 1fr))`;

  // page-fit, not page: the wall display has to hold everything at once, so this screen
  // never scrolls. Only the rank list scrolls, inside its own bounded height.
  return (
    <div className="page page-fit">
      <div className="stat-row">
        {/* Output and progress against target are one tile, not two: both read the same
            current-lot number, and showing it twice under different labels invited the
            question of which one was right. */}
        <div className="stat target">
          <div className="label">Production · Lot</div>
          <div className="value">
            {fmtInt(totals.production)}
            <span className="sub"> / {fmtInt(totals.targetCells)}</span>
          </div>
          <div className="target-bar">
            <i style={{ width: `${Math.min(100, achieved)}%` }} />
          </div>
          <div className="sub">
            {achieved.toFixed(1)}% of target ·{" "}
            {data.lines.filter((l) => l.production !== null).length} of {catalog.lines.length} lines reporting
          </div>
        </div>

        {/* Which verdicts each of these counts is declared in the catalog, so narrowing
            the headline rate to NG on Example B and Example C was a data change, not a UI one. */}
        {totals.rates.map((rate) => (
          <div key={rate.key} className={`stat metric metric-${rate.key}`}>
            <div className="label">{rate.label} · Lot</div>
            <div className="value">{fmtPct(rate.ratePct)}</div>
            {/* Cells, not units: a cell is the thing the line makes, and it is the
                word the process itself uses. */}
            <div className="sub">{fmtInt(rate.units)} cells</div>
          </div>
        ))}

        <div className="stat fleet">
          <div className="label">Inspectors</div>
          <div className="value">
            {totals.running + totals.idle + totals.offline}
            <span className="sub"> / {totals.running + totals.idle + totals.offline + totals.notDeployed}</span>
          </div>
          <div className="fleet-breakdown">
            <span className="run">RUN {totals.running}</span>
            <span className="idle">IDLE {totals.idle}</span>
            <span className="off">OFF {totals.offline}</span>
          </div>
        </div>
      </div>

      <div className="grid-layout">
        <RankPanel
          cells={data.cells}
          windowMinutes={windowMinutes}
          onSelect={(line, visionKey) => navigate(`/vision/${line}/${visionKey}`)}
        />
        {/* The band that heads the grid runs across this column and then down its left
            edge, so the width of the label column is declared here, where both halves
            of it can read the same value. */}
        <div className="grid-main" style={{ ["--label-col" as string]: labelColumn }}>
          <ProductionStrip lines={data.lines} downtime={downtime} columns={gridColumns} />
          <VisionGrid
            cells={data.cells}
            downtime={downtime}
            columns={gridColumns}
            onSelect={(line, visionKey) => navigate(`/vision/${line}/${visionKey}`)}
          />
        </div>
      </div>
    </div>
  );
}
