import { fmtInt, fmtPct } from "../../format";
import type { AiReport } from "../../types";
import { VERDICT, VERDICT_ORDER, fmtPp, rateOf } from "./aiVisual";

/**
 * The window in five numbers, before any chart.
 *
 * Each rate is set against the window before it, because "0.193%" alone does not say
 * whether to worry and "up 0.052%p on the last half hour" does. The delta is coloured by
 * whether the move is bad - up is bad for every rate here - and always carries its sign
 * and arrow, so it reads without the colour.
 */
export default function KpiRow({ report, previous }: { report: AiReport; previous: AiReport | null }) {
  const facts = report.facts;
  if (!facts) return null;

  const activeLines = facts.lines.filter((l) => l.activeMinutes > 0);
  const avgActive = activeLines.length
    ? Math.round(activeLines.reduce((s, l) => s + l.activeMinutes, 0) / activeLines.length)
    : 0;
  const byVerdict = VERDICT_ORDER.map((v) => ({
    verdict: v,
    count: facts.findings.filter((f) => f.verdict === v).length,
  }));
  // An alert that has been running for a day is not the same news as one that started in
  // this window, and the difference is the whole reason somebody looks up.
  const alerts = facts.findings.filter((f) => f.verdict === "SPIKE");
  const fresh = alerts.filter((f) => f.isNew).length;
  const alarmCount = facts.alarms.reduce((s, a) => s + a.count, 0);

  return (
    <div className="ai-kpis">
      <div className="ai-kpi">
        <span className="label">Produced</span>
        <span className="value">{fmtInt(facts.fleet.produced)}</span>
        <span className="sub">
          {activeLines.length}/{facts.lines.length} lines running · {avgActive}/{facts.windowMinutes} min average
        </span>
      </div>

      {facts.fleet.rates.map((rate) => {
        const before = previous ? rateOf(previous, rate.key) : null;
        const delta = rate.ratePct !== null && before !== null ? rate.ratePct - before : null;
        return (
          <div className="ai-kpi" key={rate.key}>
            <span className="label">{rate.label}</span>
            <span className="value">{fmtPct(rate.ratePct)}</span>
            <span className="sub">
              {delta === null ? (
                "no previous window to compare"
              ) : (
                <>
                  <span className={`ai-delta ${delta > 0 ? "worse" : delta < 0 ? "better" : ""}`}>
                    {delta > 0 ? "▲" : delta < 0 ? "▼" : "–"} {fmtPp(delta)}
                  </span>{" "}
                  vs previous window · {fmtInt(rate.units)} cells
                </>
              )}
            </span>
          </div>
        );
      })}

      <div className="ai-kpi">
        <span className="label">Alerts</span>
        <span className="value">
          {alerts.length}
          {alerts.length > 0 && (
            <em className="ai-kpi-split">{fresh} new · {alerts.length - fresh} continuing</em>
          )}
        </span>
        <span className="ai-kpi-verdicts">
          {byVerdict.map(({ verdict, count }) => (
            <span key={verdict} className={`ai-mini v-${verdict}${count === 0 ? " zero" : ""}`}
                  title={VERDICT[verdict].label}>
              <i aria-hidden="true">{VERDICT[verdict].glyph}</i>
              {VERDICT[verdict].label} {count}
            </span>
          ))}
        </span>
      </div>

      <div className="ai-kpi">
        <span className="label">Equipment alarms</span>
        <span className="value">{alarmCount}</span>
        <span className="sub">
          {facts.alarms.length} kind(s) · {facts.statuses.length} inspector(s) not reporting
        </span>
      </div>
    </div>
  );
}
