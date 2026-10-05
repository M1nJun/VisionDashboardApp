import { useMemo, useState } from "react";
import { fmtPct, primaryJudgement } from "../format";
import { useCatalog } from "../hooks/useCatalog";
import { windowLabel } from "./WindowFilter";
import type { GridCell, VisionTypeView } from "../types";

const COMMON = "COMMON";

/**
 * Which inspectors are worst right now, worst first.
 *
 * Everything the panel knows about vision types - which exist, what they are called in
 * a narrow column, which judgements they produce, which of them count as Common - comes
 * from the catalog. There is no list here to fall out of step with the backend.
 */
export default function RankPanel({
  cells,
  windowMinutes,
  onSelect,
}: {
  cells: GridCell[];
  windowMinutes: number | null;
  onSelect: (line: string, visionKey: string) => void;
}) {
  const catalog = useCatalog();
  const [visionFilter, setVisionFilter] = useState<string>(COMMON);
  const [judgement, setJudgement] = useState<string | null>(null);

  // Row one is Common and the inspectors it covers; row two is everything else, in grid
  // order. Both fall out of the catalog, so adding a vision type needs no change here.
  const commonTypes = catalog.commonVisionKeys
    .map((key) => catalog.visionTypes.find((v) => v.key === key))
    .filter((v): v is VisionTypeView => v !== undefined);
  const otherTypes = catalog.visionTypes.filter(
    (v) => !catalog.commonVisionKeys.includes(v.key)
  );

  const selectedType = catalog.visionTypes.find((v) => v.key === visionFilter) ?? null;
  // Only offer a judgement choice where there is one to make: Example B reports three,
  // every other vision type reports one and gets no redundant control. Common spans
  // several types, so it always ranks by each cell's own colour-driving judgement.
  const judgementOptions = selectedType && selectedType.judgements.length > 1
    ? selectedType.judgements
    : [];
  const activeJudgement = judgementOptions.length > 0
    ? judgement ?? primaryJudgement(selectedType!.judgements)?.code ?? null
    : null;

  const inScope = useMemo(() => {
    if (visionFilter === COMMON) {
      return (cell: GridCell) => catalog.commonVisionKeys.includes(cell.visionKey);
    }
    return (cell: GridCell) => cell.visionKey === visionFilter;
  }, [visionFilter, catalog.commonVisionKeys]);

  const ranked = useMemo(() => {
    const scored = cells
      .filter((c) => c.status !== "NOT_DEPLOYED")
      .filter(inScope)
      .map((cell) => {
        // With one vision type selected, rank by the chosen judgement. Across Common,
        // rank each cell by its own colour-driving judgement, which is the same number
        // its card in the grid is coloured by.
        const entry = activeJudgement
          ? cell.judgements.find((j) => j.code === activeJudgement)
          : primaryJudgement(cell.judgements);
        return { cell, rate: entry?.ratePct ?? null, count: entry?.count ?? 0 };
      })
      // A cell that inspected nothing in the window has no rate. Ranking it as 0% would
      // sort every stopped inspector to the bottom looking perfect.
      .filter((row): row is { cell: GridCell; rate: number; count: number } => row.rate !== null);

    return scored.sort((a, b) => b.rate - a.rate);
  }, [cells, inScope, activeJudgement]);

  const worst = ranked.length > 0 ? ranked[0].rate : 0;
  const deployed = cells.filter((c) => c.status !== "NOT_DEPLOYED" && inScope(c)).length;

  return (
    <div className="panel rank-panel">
      <h2>NG Rate Rank</h2>

      <div className="seg" role="group" aria-label="Vision type filter">
        <button
          type="button"
          title={`The ${catalog.commonVisionKeys.length} inspectors every line is judged on`}
          className={visionFilter === COMMON ? "on" : ""}
          onClick={() => {
            setVisionFilter(COMMON);
            setJudgement(null);
          }}
        >
          Common
        </button>
        {commonTypes.map((type) => (
          <FilterChip
            key={type.key}
            type={type}
            active={visionFilter === type.key}
            onSelect={() => {
              setVisionFilter(type.key);
              setJudgement(null);
            }}
          />
        ))}
      </div>

      <div className="seg" role="group" aria-label="Other vision types">
        {otherTypes.map((type) => (
          <FilterChip
            key={type.key}
            type={type}
            active={visionFilter === type.key}
            onSelect={() => {
              setVisionFilter(type.key);
              setJudgement(null);
            }}
          />
        ))}
      </div>

      {judgementOptions.length > 0 && (
        <div className="seg" role="group" aria-label="Judgement">
          {judgementOptions.map((option) => (
            <button
              key={option.code}
              type="button"
              className={activeJudgement === option.code ? "on" : ""}
              onClick={() => setJudgement(option.code)}
            >
              {option.code}
            </button>
          ))}
        </div>
      )}

      <div className="rank-scope">
        <span>
          {ranked.length} of {deployed} inspectors
        </span>
        <span className="chip">{windowLabel(windowMinutes)}</span>
      </div>

      <div className="rank-rows">
        {ranked.length === 0 && (
          <div className="state-note">
            {windowMinutes === null
              ? "No data yet."
              : `Nothing inspected in the last ${windowLabel(windowMinutes)}.`}
          </div>
        )}
        {ranked.map((row, index) => (
          <button
            key={`${row.cell.line}/${row.cell.visionKey}`}
            type="button"
            className={`rank-row color-${row.cell.colorLevel}`}
            title={`${row.cell.line} ${row.cell.displayName}`}
            onClick={() => onSelect(row.cell.line, row.cell.visionKey)}
          >
            <span className="rail" />
            <span className="rank">{index + 1}</span>
            <span className="who">
              <span className="line-no">{row.cell.line}</span>
              <span className="badge">{row.cell.shortName}</span>
            </span>
            <span className="metric">
              <span className="value">{fmtPct(row.rate)}</span>
              <span className="sub">
                {row.count} {row.count === 1 ? "cell" : "cells"}
              </span>
            </span>
            <span className="bar">
              <i style={{ width: `${worst > 0 ? (row.rate / worst) * 100 : 0}%` }} />
            </span>
          </button>
        ))}
      </div>
    </div>
  );
}

function FilterChip({
  type,
  active,
  onSelect,
}: {
  type: VisionTypeView;
  active: boolean;
  onSelect: () => void;
}) {
  return (
    <button type="button" title={type.displayName} className={active ? "on" : ""} onClick={onSelect}>
      {type.shortName}
    </button>
  );
}
