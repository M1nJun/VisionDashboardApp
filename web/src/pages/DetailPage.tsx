import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import { fetchDetail } from "../api";
import DefectViewer from "../components/DefectImages";
import NgEventsTable, { judgeClass, judgeColour } from "../components/NgEventsTable";
import TrendChart, { type TrendRef } from "../components/TrendChart";
import { fmtClock, fmtInt, fmtPct, primaryJudgement } from "../format";
import type { NgEvent } from "../types";
import { useCatalog } from "../hooks/useCatalog";
import { usePolling } from "../hooks/usePolling";

export default function DetailPage() {
  const { line = "", visionKey = "" } = useParams();
  const catalog = useCatalog();
  const pollMs = catalog.pollIntervalSeconds * 1000;
  const { data, error, loading } = usePolling(
    () => fetchDetail(line, visionKey),
    pollMs,
    [line, visionKey, pollMs]
  );

  const visionType = catalog.visionTypes.find((v) => v.key === visionKey);
  const [trendJudgement, setTrendJudgement] = useState<string | null>(null);
  // The rows as the table has them filtered, so the viewer steps through what is on
  // screen rather than through every NG in the lot.
  const [open, setOpen] = useState<{ rows: NgEvent[]; index: number } | null>(null);
  const [alarmsOpen, setAlarmsOpen] = useState(false);

  if (loading && !data) {
    return <div className="page"><div className="state-note">Loading…</div></div>;
  }
  if (!data || !visionType) {
    return (
      <div className="page">
        <div className="state-note">
          Cannot load this inspector. {error} <Link to="/">Back to the grid</Link>
        </div>
      </div>
    );
  }

  const { cell } = data;
  const primary = primaryJudgement(visionType.judgements);
  const primaryCount = cell.judgements.find((j) => j.code === primary?.code)?.count ?? 0;
  const inspected = cell.inspectedCount ?? 0;
  const thresholds = catalog.thresholds[visionType.thresholdProfile];
  const trendCode = trendJudgement ?? primary?.code ?? visionType.judgements[0]?.code ?? "";
  const trendIndex = visionType.judgements.findIndex((j) => j.code === trendCode);

  // The colour-driving judgement is scored against the vision type's threshold profile,
  // which Settings can tune; the others carry their own limits in the catalog. Example B's
  // DLNG and C-NG run an order of magnitude above NG, so scoring all three against one
  // profile drew the NG lines far off the top of their own charts.
  const trendDef = visionType.judgements[trendIndex];
  const trendRefs: TrendRef[] = trendDef?.drivesColor
    ? [
        { label: "warn", value: thresholds.warnPct, stroke: "var(--yellow)" },
        { label: "crit", value: thresholds.critPct, stroke: "var(--red)" },
      ]
    : [
        trendDef?.warnPct != null
          ? { label: "warn", value: trendDef.warnPct, stroke: "var(--yellow)" }
          : null,
        trendDef?.critPct != null
          ? { label: "crit", value: trendDef.critPct, stroke: "var(--red)" }
          : null,
      ].filter((ref): ref is TrendRef => ref !== null);

  // page-fit like the grid: this is read standing at the line, and a page that has to be
  // scrolled to reach the event table is one nobody scrolls.
  return (
    <div className="page page-fit detail">
      <div className="detail-head">
        {/* Named for what it does, not for where it goes. "Grid" was a word in the
            corner that people read as a label rather than as the way back. */}
        <Link to="/" className="back">Back to Grid</Link>
        <h1>
          <span className="detail-line">{cell.line}</span>
          <span className="detail-vision">{cell.displayName}</span>
          <span className="badge">{cell.shortName}</span>
        </h1>

        <span className={`live ${cell.status}`}>{cell.status}</span>
      </div>

      <div className="stat-row">
        <div className="stat">
          <div className="label">Lot</div>
          <div className="value small">{cell.lotId ?? "–"}</div>
          <div className="sub">model {cell.modelId ?? "–"}</div>
        </div>
        <div className="stat production">
          <div className="label">Inspected / OK</div>
          <div className="value">{fmtInt(inspected)}</div>
          {/* OK subtracts only the colour-driving judgement: the others are recorded
              defect types but are not what this tile is asking about. */}
          <div className="sub">OK {fmtInt(inspected - primaryCount)}</div>
        </div>

        {visionType.judgements.map((judgement) => {
          const entry = cell.judgements.find((j) => j.code === judgement.code);
          return (
            <div className="stat" key={judgement.code}>
              <div className="label">{judgement.code} Rate</div>
              <div className="value" style={{ color: judgeColour(judgement.code) }}>
                {fmtPct(entry?.ratePct ?? null)}
              </div>
              <div className="sub">{fmtInt(entry?.count ?? 0)} cells</div>
            </div>
          );
        })}

        <div className="stat">
          <div className="label">Last Seen</div>
          <div className="value small">{fmtClock(cell.lastEventAt)}</div>
          <div className="sub">beat {fmtClock(cell.lastHeartbeatAt)}</div>
        </div>

        {/* Sits with the other readings rather than behind a bare count, and carries the
            latest alarm on its face - the question is almost always "what was it", and
            answering it here saves opening the list at all. */}
        <button
          type="button"
          className={`stat alarm-stat${(cell.alarmCount ?? 0) > 0 ? " active" : ""}`}
          onClick={() => setAlarmsOpen(true)}
        >
          <div className="label">Alarms</div>
          <div className="value">{cell.alarmCount ?? 0}</div>
          <div className="sub alarm-preview">
            {data.alarms.length === 0
              ? "none this lot"
              : `${fmtClock(data.alarms[0].at)} ${data.alarms[0].name ?? data.alarms[0].code ?? "alarm"}`}
          </div>
        </button>
      </div>

      <div className="detail-body">
        <div className="panel trend-panel">
          <div className="panel-head">
            <h2>NG Trend</h2>
            {visionType.judgements.length > 1 && (
              <div className="seg inline judge-seg">
                {visionType.judgements.map((j) => (
                  <button
                    key={j.code}
                    type="button"
                    className={`${judgeClass(j.code)}${trendCode === j.code ? " on" : ""}`}
                    onClick={() => setTrendJudgement(j.code)}
                  >
                    {j.code}
                  </button>
                ))}
              </div>
            )}
          </div>
          <TrendChart
            points={data.trend}
            judgement={trendCode}
            refs={trendRefs}
            colour={judgeColour(trendCode)}
          />
        </div>

        <NgEventsTable
          events={data.events}
          total={data.eventTotal}
          topDefects={data.topDefects}
          judgements={visionType.judgements}
          onOpen={(rows, index) => setOpen({ rows, index })}
        />
      </div>

      {open !== null && open.rows.length > 0 && (
        <DefectViewer
          line={line}
          visionKey={visionKey}
          events={open.rows}
          index={Math.min(open.index, open.rows.length - 1)}
          onIndex={(index) => setOpen((o) => (o === null ? o : { ...o, index }))}
          onClose={() => setOpen(null)}
        />
      )}

      {alarmsOpen && (
        <div className="modal-backdrop" onClick={() => setAlarmsOpen(false)}>
          <div
            className="modal alarms-modal"
            onClick={(e) => e.stopPropagation()}
            role="dialog"
            aria-modal="true"
          >
            <div className="modal-head">
              <div className="modal-title">
                <span className="viewer-defect">Alarms · this lot</span>
              </div>
              <div className="modal-actions">
                <button type="button" className="modal-close" onClick={() => setAlarmsOpen(false)}>
                  × Close
                </button>
              </div>
            </div>
            <div className="modal-body">
              {data.alarms.length === 0 ? (
                <div className="state-note">No alarms in this lot.</div>
              ) : (
                <table className="table">
                  <thead>
                    <tr><th>Time</th><th>Code</th><th>Alarm</th><th>Detail</th></tr>
                  </thead>
                  <tbody>
                    {data.alarms.map((alarm, i) => (
                      <tr key={i}>
                        <td className="num">{fmtClock(alarm.at)}</td>
                        <td className="num">{alarm.code ?? "–"}</td>
                        <td>{alarm.name ?? "–"}</td>
                        <td>{alarm.detail ?? "–"}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
