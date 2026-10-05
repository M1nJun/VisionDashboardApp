import { useEffect, useRef } from "react";
import { useNavigate } from "react-router-dom";
import { fmtInt } from "../../format";
import type { AiFacts, AiFinding } from "../../types";
import type { NewAlerts } from "../../hooks/useNewAlerts";
import { fmtClockShort, fmtSince } from "./aiVisual";

const MAX_CARDS = 3;

/**
 * A newly raised alert, in front of whoever is looking at the dashboard.
 *
 * The AI REPORT tab already says all of this, and says it better - but only to somebody
 * who is on that tab. The floor watches GRID. So this carries the one thing that cannot
 * wait for somebody to go looking: an inspector that was normal an hour ago is not normal
 * now, and here is the arithmetic that says so.
 *
 * Deliberately small. It sits over the middle of the screen rather than filling it,
 * because the thing behind it is the dashboard somebody is working from, and an alert
 * that hides the floor to tell you about the floor has defeated itself.
 */
export default function AlertPopup({ alerts, onClose }: {
  alerts: NewAlerts;
  onClose: () => void;
}) {
  const panel = useRef<HTMLDivElement>(null);
  const navigate = useNavigate();
  const facts = alerts.report.facts;

  useEffect(() => {
    panel.current?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  if (!facts) return null;

  const shown = alerts.findings.slice(0, MAX_CARDS);
  const hidden = alerts.findings.length - shown.length;

  return (
    <div className="ai-pop-scrim" onClick={onClose}>
      <section
        className="ai-pop"
        ref={panel}
        tabIndex={-1}
        role="alertdialog"
        aria-label={`${alerts.findings.length} new alert(s)`}
        onClick={(e) => e.stopPropagation()}
      >
        <header className="ai-pop-head">
          <span className="ai-pop-tag">{alerts.findings.length} new alert{alerts.findings.length > 1 ? "s" : ""}</span>
          <span className="ai-pop-window">
            {fmtClockShort(facts.windowStart)} – {fmtClockShort(facts.windowEnd)}
          </span>
          <button className="ai-pop-x" onClick={onClose} aria-label="Close">✕</button>
        </header>

        <div className="ai-pop-body">
          {shown.map((f) => (
            <AlertCard key={`${f.line}/${f.visionKey}/${f.judgement}`} finding={f} facts={facts} />
          ))}
          {hidden > 0 && <p className="ai-pop-more">{hidden} more were raised in the same window.</p>}
        </div>

        <footer className="ai-pop-foot">
          <button
            className="ai-pop-go"
            onClick={() => {
              navigate("/ai");
              onClose();
            }}
          >
            Open the AI report
          </button>
          <button className="ai-pop-dismiss" onClick={onClose}>Dismiss</button>
        </footer>
      </section>
    </div>
  );
}

/**
 * One alert, read left to right: how much was checked, what came out, what normally
 * comes out. That is the order the verdict was decided in, and reading it in that order
 * is what stops "64" from being just a big number.
 */
function AlertCard({ finding: f, facts }: { finding: AiFinding; facts: AiFacts }) {
  const high = f.normalHigh ?? 0;
  const low = f.normalLow ?? 0;
  // Headroom past whichever is further out, so the count's dot is never at the very edge.
  const max = Math.max(f.count, high) * 1.25 || 1;
  const pct = (v: number) => `${Math.min(100, (v / max) * 100)}%`;
  const ratio = f.expected && f.expected > 0 ? f.count / f.expected : null;
  const active = facts.windowMinutes > 0 ? f.activeMinutes / facts.windowMinutes : 0;

  return (
    <article className="ai-pop-card">
      <div className="ai-pop-who">
        <span className="ai-pop-verdict" aria-hidden="true">▲</span>
        <b>{f.line}</b> {f.displayName}
        <span className="judgement">{f.judgement}</span>
      </div>
      {f.episodeStart && (
        <div className="ai-pop-since">since {fmtSince(f.episodeStart)}</div>
      )}

      <div className="ai-pop-flow">
        <span className="step">
          <b>{fmtInt(f.inspected)}<i>cells</i></b>
          <em>checked</em>
        </span>
        <span className="arrow" aria-hidden="true">→</span>
        <span className="step is-now">
          <b>{fmtInt(f.count)}</b>
          <em>now</em>
        </span>
        <span className="vs">vs</span>
        <span className="step">
          <b><i className="pre">usually</i>{Math.floor(low)}-{Math.ceil(high)}</b>
          <em>{ratio === null ? "usual range" : `x${ratio.toFixed(1)} the usual`}</em>
        </span>
      </div>

      {/* The band and where this window landed on it - the whole verdict in one line. */}
      <div className="ai-pop-track" role="img"
           aria-label={`usual range ${Math.floor(low)} to ${Math.ceil(high)}, this window ${f.count}`}>
        <span className="band" style={{ left: pct(low), width: pct(high - low) }} />
        <span className="dot" style={{ left: pct(f.count) }} />
      </div>

      <div className="ai-pop-meta">
        <span className="run">
          <span className="bar"><i style={{ width: `${Math.round(active * 100)}%` }} /></span>
          ran {f.activeMinutes}/{facts.windowMinutes} min
        </span>
        {f.topItems.length > 0 && (
          <span className="items">
            {f.topItems.slice(0, 3).map((it, i) => (
              <em key={i}>
                {it.name}{it.side && <span>({it.side})</span>} <b>{it.count}</b>
              </em>
            ))}
          </span>
        )}
      </div>
    </article>
  );
}
