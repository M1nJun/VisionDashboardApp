import { useEffect, useMemo, useState } from "react";
import { fetchAiReports, fetchAiStatus, runAiReport } from "../api";
import DefectBars from "../components/ai/DefectBars";
import EvidenceChart from "../components/ai/EvidenceChart";
import FindingsMap from "../components/ai/FindingsMap";
import GuideDrawer from "../components/ai/GuideDrawer";
import KpiRow from "../components/ai/KpiRow";
import ReportTimeline from "../components/ai/ReportTimeline";
import { SEVERITY, VERDICT, fmtClockShort, fmtSince } from "../components/ai/aiVisual";
import { fmtClock, fmtInt, fmtPct } from "../format";
import { usePolling } from "../hooks/usePolling";
import type { AiFacts, AiReport, AiReportStatus } from "../types";

const PROSE_OPEN_KEY = "visiondash.aiProseOpen";

/**
 * The narrated view of the floor, drawn before it is written.
 *
 * Read top to bottom it answers, in order: how have the last few hours gone (timeline),
 * how did this window go (headline and five numbers), where (the map), how far from normal
 * and on what grounds (the evidence), and what defects (the bars). The model's prose and
 * the full table are still here, folded - the prose is one reading of the facts on screen,
 * and the table is the same facts without the drawing, for anyone who wants to check a mark.
 *
 * Everything the page asks the reader to trust is explained in How to read this, which opens
 * the report rather than sitting beside it: the rules only need reading once, and the space
 * they would take permanently is worth more to the numbers.
 *
 * Read-only and on its own poll. Nothing on this page writes, and nothing the grid needs
 * is fetched from here.
 */
export default function AiReportPage() {
  const { data: status } = usePolling<AiReportStatus>(fetchAiStatus, 30_000);
  const { data: reports, error, loading } = usePolling<AiReport[]>(() => fetchAiReports(24), 30_000);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [focusKey, setFocusKey] = useState<string | null>(null);
  const [guide, setGuide] = useState(false);

  // Follows the newest report unless somebody has deliberately opened an older one, so
  // a screen left on the wall keeps advancing on its own while a screen being read does
  // not jump out from under whoever is reading it.
  const selectedIndex = useMemo(() => {
    if (!reports || reports.length === 0) return -1;
    const i = reports.findIndex((r) => r.id === selectedId);
    return i >= 0 ? i : 0;
  }, [reports, selectedId]);

  // The highlight on an evidence row fades on its own; it is a pointer, not a state.
  useEffect(() => {
    if (!focusKey) return;
    const t = window.setTimeout(() => setFocusKey(null), 2200);
    return () => window.clearTimeout(t);
  }, [focusKey]);

  if (loading && !reports) {
    return <div className="page"><div className="state-note">Loading…</div></div>;
  }
  if (error && !reports) {
    return <div className="page"><div className="state-note">Cannot reach the server. {error}</div></div>;
  }
  if (status && !status.schemaReady) {
    return <SetupNote status={status} />;
  }
  if (!reports || reports.length === 0) {
    return <EmptyNote status={status} />;
  }

  const report = reports[selectedIndex];
  const previous = reports[selectedIndex + 1] ?? null;
  const facts = report.facts;

  const pick = (key: string) => {
    setFocusKey(key);
    document.getElementById(`ev-${key}`)?.scrollIntoView({ behavior: "smooth", block: "center" });
  };

  return (
    <div className="page ai-page">
      <StatusStrip status={status} onGuide={() => setGuide(true)} />

      <ReportTimeline reports={reports} selectedId={report.id} onSelect={setSelectedId} />

      <Headline report={report} following={selectedIndex === 0} onLatest={() => setSelectedId(null)} />

      {facts ? (
        <>
          <KpiRow report={report} previous={previous} />
          <div className="ai-row-2">
            <FindingsMap facts={facts} onPick={pick} />
            <EvidenceChart facts={facts} focusKey={focusKey} />
          </div>
          <div className="ai-row-3">
            <DefectBars facts={facts} />
            <AlarmsCard report={report} />
          </div>
          <details className="panel ai-fold">
            <summary>Detail table — every value the charts are drawn from</summary>
            <FindingsTable facts={facts} />
          </details>
        </>
      ) : (
        <div className="state-note">This report was written by an older server build, so its figures cannot be shown.</div>
      )}

      <GuideDrawer facts={facts} open={guide} onClose={() => setGuide(false)} />
    </div>
  );
}

/* ===================== headline ===================== */

/**
 * The window, its state, and the model's one-line reading of it.
 *
 * The bullet points are folded by default and remember how the viewer left them. On a
 * wall display the drawing below says the same thing faster; somebody who wants the
 * sentences opens them once and they stay open.
 */
function Headline({ report, following, onLatest }: {
  report: AiReport;
  following: boolean;
  onLatest: () => void;
}) {
  const [open, setOpen] = useState<boolean>(() => {
    try {
      return window.localStorage.getItem(PROSE_OPEN_KEY) === "1";
    } catch {
      return false;
    }
  });
  const toggle = (next: boolean) => {
    setOpen(next);
    try {
      window.localStorage.setItem(PROSE_OPEN_KEY, next ? "1" : "0");
    } catch {
      // Storage blocked: the fold still works for this visit.
    }
  };

  const lines = report.summary?.split(/\n+/).map((l) => l.replace(/^[-•]\s*/, "")).filter(Boolean) ?? [];
  const headline = report.headline
    ?? (report.status === "PENDING" ? "Writing the summary." : null)
    ?? (report.status === "SKIPPED" ? "Nothing was inspected in this window." : null)
    ?? "The figures were collected, but no summary was written.";

  return (
    <section className={`panel ai-summary sev-${report.severity}`}>
      <div className="ai-summary-head">
        <span className={`ai-sev sev-${report.severity}`}>{SEVERITY[report.severity].label}</span>
        <span className="ai-window">
          {fmtClockShort(report.windowStart)} – {fmtClockShort(report.windowEnd)}
        </span>
        {report.facts && <span className="ai-window-sub">{report.facts.windowMinutes} min window</span>}
        {!following && (
          <button className="ai-latest" onClick={onLatest}>Jump to latest</button>
        )}
        <span className="ai-meta">
          {report.model ?? "–"}
          {report.latencyMs != null && ` · ${(report.latencyMs / 1000).toFixed(1)}s`}
        </span>
      </div>

      <h1 className="ai-headline">{headline}</h1>

      {lines.length > 0 ? (
        <details className="ai-prose-fold" open={open}
                 onToggle={(e) => toggle((e.currentTarget as HTMLDetailsElement).open)}>
          <summary>Model summary, {lines.length} lines</summary>
          <div className="ai-prose">
            {lines.map((line, i) => <p key={i}>{line}</p>)}
          </div>
        </details>
      ) : report.status === "LLM_FAILED" ? (
        <p className="ai-prose-missing">
          No summary could be generated{report.error ? ` (${report.error})` : ""}. The figures below were collected normally.
        </p>
      ) : null}
    </section>
  );
}

/* ===================== alarms & silent inspectors ===================== */

function AlarmsCard({ report }: { report: AiReport }) {
  const facts = report.facts!;
  const empty = facts.alarms.length === 0 && facts.statuses.length === 0;
  return (
    <section className="panel ai-block">
      <h2>Equipment alarms &amp; inspectors not reporting</h2>
      {empty ? (
        <div className="state-note">No alarms, and no stopped inspectors, in this window.</div>
      ) : (
        <div className="ai-aux">
          {facts.alarms.map((a, i) => (
            <div className="ai-aux-row alarm" key={`a${i}`}>
              <span className="who">{a.line} · {a.displayName}</span>
              <span className="what">{[a.code, a.name].filter(Boolean).join(" ") || "Alarm"}</span>
              <span className="num">x{a.count}</span>
              <span className="when">{fmtClock(a.lastAt)}</span>
            </div>
          ))}
          {facts.statuses.map((s, i) => (
            <div className={`ai-aux-row status ${s.status}`} key={`s${i}`}>
              <span className="who">{s.line} · {s.displayName}</span>
              <span className="what">{s.status === "OFFLINE" ? "no heartbeat" : "no production"}</span>
              <span className="num" />
              <span className="when">
                {s.minutesSinceEvent != null && s.minutesSinceEvent > 0 && `last inspected ${s.minutesSinceEvent} min ago`}
              </span>
            </div>
          ))}
        </div>
      )}
    </section>
  );
}

/* ===================== the table view ===================== */

/**
 * Every number the charts draw, as a table.
 *
 * Counts lead and rates follow, in the order the verdict was actually decided: how many
 * cells went through, how many defects came out, what this inspector's band allowed. A
 * too-few-units row prints words where the rate would go rather than the rate greyed out -
 * somebody skimming reads the number, not the styling meant to disown it.
 */
function FindingsTable({ facts }: { facts: AiFacts }) {
  const findings = facts.findings;
  if (findings.length === 0) {
    return <div className="state-note">Every inspector stayed inside its usual range.</div>;
  }
  return (
    <>
      <div className="ai-gate-note">
        usual level = last {facts.baselineHours}h · range width = {facts.spreadDays}-day swing ·
        {" "}under {fmtInt(facts.rule.minInspected)} cells no rate is computed
      </div>
      <div className="ai-table-wrap">
        <table className="ai-table">
          <thead>
            <tr>
              <th>Where</th>
              <th>Verdict</th>
              <th className="num">Checked</th>
              <th className="num">Now</th>
              <th className="num">Usual range</th>
              <th className="num">Usual level</th>
              <th className="num">Swing</th>
              <th className="num">Rate</th>
              <th>Open since</th>
              <th>Defect types</th>
            </tr>
          </thead>
          <tbody>
            {findings.map((f) => {
              const thin = f.ratePct === null;
              return (
                <tr key={`${f.line}/${f.visionKey}/${f.judgement}`} className={`verdict-${f.verdict}`}>
                  <td className="slot">
                    <b>{f.line}</b> {f.displayName}
                    <span className="judgement">{f.judgement}</span>
                  </td>
                  <td>
                    <span className={`ai-badge v-${f.verdict}`}>
                      {VERDICT[f.verdict].glyph} {VERDICT[f.verdict].label}
                    </span>
                    {f.verdict === "SPIKE" && f.isNew && <span className="ai-new">new</span>}
                  </td>
                  <td className="num">
                    {fmtInt(f.inspected)}
                    {f.activeMinutes > 0 && <span className="sub">{f.activeMinutes} min</span>}
                  </td>
                  <td className="num strong">{fmtInt(f.count)}</td>
                  <td className="num band">
                    {f.normalHigh === null
                      ? "–"
                      : `${Math.floor(f.normalLow ?? 0)}-${Math.ceil(f.normalHigh)}`}
                  </td>
                  <td className="num dim">
                    {f.expected == null ? "–" : f.expected.toFixed(1)}
                    {f.frozen && <span className="sub" title="the baseline from before this problem started">frozen</span>}
                  </td>
                  <td className="num dim"
                      title="1.0 is the swing chance alone would give. Higher means this inspector naturally moves more.">
                    {f.spread == null ? "–" : `×${f.spread.toFixed(1)}`}
                  </td>
                  <td className="num rate">
                    {thin ? <span className="thin">too few units</span> : fmtPct(f.ratePct)}
                    {!thin && f.baselinePct != null && (
                      <span className="sub">usually {fmtPct(f.baselinePct)}</span>
                    )}
                  </td>
                  <td className="since">{f.episodeStart ? fmtSince(f.episodeStart) : "–"}</td>
                  <td className="items">
                    {f.topItems.map((it, i) => (
                      <span key={i}>{it.name}{it.side && <i>{it.side}</i>}<b>{it.count}</b></span>
                    ))}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </>
  );
}

/* ===================== states before there is anything to show ===================== */

function StatusStrip({ status, onGuide }: { status: AiReportStatus | null; onGuide?: () => void }) {
  const [message, setMessage] = useState<string | null>(null);
  if (!status) return null;

  return (
    <div className="ai-status-strip">
      <span className={`dot ${status.ollamaAvailable ? "ok" : "bad"}`} />
      <span className="model">{status.model}</span>
      {!status.ollamaAvailable && <span className="warn">cannot reach the model</span>}
      {!status.enabled && <span className="warn">automatic generation is off</span>}
      <span className="spacer" />
      <span className="next">
        every {status.intervalMinutes} min · next {fmtClock(status.nextRunAt)}
      </span>
      {onGuide && (
        <button className="ai-guide-open" onClick={onGuide}>
          <i aria-hidden="true">?</i> How to read this
        </button>
      )}
      <button
        className="ai-run"
        onClick={() =>
          runAiReport()
            .then(setMessage)
            .catch((e: unknown) => setMessage(e instanceof Error ? e.message : String(e)))
        }
      >
        Run now
      </button>
      {message && <span className="run-note">{message}</span>}
    </div>
  );
}

/** The migrations have not been run. Says which commands, because that is the whole fix. */
function SetupNote({ status }: { status: AiReportStatus }) {
  return (
    <div className="page">
      <div className="panel ai-setup">
        <h2>AI reports are not set up on this server yet</h2>
        <p>
          The tables this tab reads have not been created. Everything else on the
          dashboard is unaffected — the grid does not read them.
        </p>
        <p>On the central PC, with the server stopped:</p>
        <pre>
          {"Get-Content db\\migrate-ai-reports.sql -Raw | & $mysql -u root -p visiondash\n"}
          {"Get-Content db\\migrate-ai-episodes.sql -Raw | & $mysql -u root -p visiondash"}
        </pre>
        <p className="dim">
          Then start the server again. It will also need Ollama running with{" "}
          <code>{status.model}</code> pulled — see deploy\OLLAMA-OFFLINE.md.
        </p>
      </div>
    </div>
  );
}

function EmptyNote({ status }: { status: AiReportStatus | null }) {
  return (
    <div className="page ai-page">
      <StatusStrip status={status} />
      <div className="state-note">
        No report has been generated yet.
        {status && ` The first one is due at ${fmtClock(status.nextRunAt)}.`}
      </div>
    </div>
  );
}
