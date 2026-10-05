import { useEffect, useRef } from "react";
import { fmtInt } from "../../format";
import type { AiFacts, AiFinding } from "../../types";
import { VERDICT } from "./aiVisual";

/**
 * How to read this page, in a panel that is only there when somebody asks for it.
 *
 * The report earns its numbers through a fair amount of statistics, and none of that
 * should be the reader's problem - but an engineer who wants to know where "usually 0-26"
 * came from deserves an answer without going to find the source. So the guide slides over
 * the report rather than sitting beside it permanently, and it explains the rules using
 * the rows that are on screen right now: a worked example of the reader's own shift beats
 * a generic one every time.
 */
export default function GuideDrawer({
  facts,
  open,
  onClose,
}: {
  facts: AiFacts | null;
  open: boolean;
  onClose: () => void;
}) {
  const panel = useRef<HTMLDivElement>(null);

  // Escape closes it, and focus moves inside so the keyboard is not left behind the
  // overlay - this panel covers the page it is explaining.
  useEffect(() => {
    if (!open) return;
    panel.current?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, onClose]);

  if (!open) return null;

  const rule = facts?.rule;
  // A real row from the open report, so every number below is one the reader can find on
  // the page behind this panel.
  const example = facts?.findings.find((f) => f.verdict === "SPIKE" && f.normalHigh !== null)
    ?? facts?.findings.find((f) => f.normalHigh !== null)
    ?? null;

  return (
    <div className="ai-guide-scrim" onClick={onClose}>
      <aside
        className="ai-guide"
        ref={panel}
        tabIndex={-1}
        role="dialog"
        aria-label="How to read this report"
        onClick={(e) => e.stopPropagation()}
      >
        <header>
          <h2>How to read this</h2>
          <button className="ai-guide-close" onClick={onClose} aria-label="Close">✕</button>
        </header>

        <div className="ai-guide-body">
          <section>
            <h3>What this screen does</h3>
            <p>
              Every {facts?.windowMinutes ?? 30} minutes it checks all 49 inspectors and shows
              only <b>the ones behaving unlike themselves</b>. The server works out the numbers
              and the verdicts; the model only turns the result into sentences. It never
              calculates and never invents a figure.
            </p>
          </section>

          <section>
            <h3>Where "usually {example ? bandOf(example) : "N-M"}" comes from</h3>
            <p>Two things are measured separately for each inspector.</p>
            <dl className="ai-guide-dl">
              <dt>Usual level</dt>
              <dd>
                How many defects this much production normally yields, from the
                <b> last {facts?.baselineHours ?? 24} hours</b>.
              </dd>
              <dt>Usual range</dt>
              <dd>
                How far that count wanders from one window to the next. The width comes from
                how far this inspector has actually swung over the
                <b> last {facts?.spreadDays ?? 7} days</b>.
              </dd>
            </dl>
            <p>
              So the range differs per inspector. Example B DLNG naturally swings a long way
              and gets a wide one; an inspector that almost never produces a defect gets a
              narrow one. That is why <b>the same single defect can be normal on one
              inspector and abnormal on another</b>.
            </p>
          </section>

          {example && (
            <section>
              <h3>Worked through, on this report</h3>
              <div className="ai-guide-example">
                <div className="who">
                  <b>{example.line}</b> {example.displayName} {example.judgement}
                </div>
                <ul>
                  <li><b>{fmtInt(example.inspected)} cells</b> were inspected in this window.</li>
                  <li>
                    At that volume this inspector usually produces about{" "}
                    <b>{(example.expected ?? 0).toFixed(1)}</b>, and anything up to{" "}
                    <b>{Math.ceil(example.normalHigh ?? 0)}</b> is still its usual range.
                  </li>
                  <li>
                    This window it produced <b>{fmtInt(example.count)}</b> →{" "}
                    <b>{VERDICT[example.verdict].label}</b>
                  </li>
                  {example.frozen && (
                    <li>
                      Here "usual" means <b>this inspector as it was before the problem
                      started</b>. A baseline that only looks at recent history learns that a
                      fault is normal if the fault lasts a few days.
                    </li>
                  )}
                </ul>
              </div>
            </section>
          )}

          <section>
            <h3>The three verdicts</h3>
            <div className="ai-guide-verdicts">
              <div>
                <span className="ai-vchip v-SPIKE"><span aria-hidden="true">▲</span> Alert</span>
                <p>
                  Outside the usual range,{" "}
                  {rule
                    ? `at least ${rule.minExcess} defects above usual and at least ${rule.minRatio}x it,`
                    : "by a wide enough margin,"}{" "}
                  and it held for <b>{rule?.confirmWindows ?? 2} windows in a row</b>. One odd
                  window is what ordinary variation looks like, so it is not raised on its own.
                  Anything past {rule?.fastRatio ?? 5}x the usual skips the wait and is raised
                  immediately.
                </p>
              </div>
              <div>
                <span className="ai-vchip v-ELEVATED"><span aria-hidden="true">△</span> Watch</span>
                <p>Outside the usual range but short of the alert bar. Worth seeing what the next window does.</p>
              </div>
              <div>
                <span className="ai-vchip v-INSUFFICIENT"><span aria-hidden="true">·</span> Too few units</span>
                <p>
                  Fewer than <b>{rule?.minInspected ?? 200} cells</b> were inspected, so no rate
                  can be judged. One defect in 100 cells is 1.00%, but that is not worse than 6
                  in 3,000 (0.20%) - so these items carry <b>no percentage at all</b>, only the
                  count.
                </p>
              </div>
            </div>
          </section>

          <section>
            <h3>When an alert goes away</h3>
            <p>
              It stays up for as long as the problem lasts. One quiet window does not clear it:
              the count has to come back <b>inside the usual range for {rule?.clearWindows ?? 2}
              {" "}windows in a row</b>. That is why the screen shows <b>when it started</b>
              rather than how many windows it has spanned.
            </p>
          </section>

          <section>
            <h3>Other marks</h3>
            <dl className="ai-guide-dl">
              <dt>THR</dt>
              <dd>
                A cell the main grid is colouring red. Marked here, never raised on its own -
                at 400 cells in half an hour a single defect clears the line, and almost all of
                them are exactly that.
              </dd>
              <dt>ran N of 30 min</dt>
              <dd>
                How much of the window actually had cells flowing. A line that ran four minutes
                produced four minutes of evidence, and every number under it should be read
                that much more lightly.
              </dd>
              <dt>open</dt>
              <dd>An alert carried over from an earlier window, as opposed to one raised just now.</dd>
              <dt>AL</dt>
              <dd>Equipment alarms in the same window. No causal link is claimed.</dd>
            </dl>
          </section>

          <section>
            <h3>The statistics, for anyone who wants them</h3>
            <p>
              Two numbers per inspector, and a band drawn from them. Nothing here is a
              setting somebody picked - both are measured from that inspector's own history.
            </p>
            <dl className="ai-guide-dl">
              <dt>Usual level <span className="ai-guide-f">lambda = n x (k + 0.5) / (N + 1)</span></dt>
              <dd>
                Defects per cell over the last {facts?.baselineHours ?? 24} hours, times the
                cells inspected now. The <b>+0.5</b> is Jeffreys smoothing: an inspector with
                zero defects all day would otherwise have a rate of exactly zero, and the very
                next defect would be infinitely surprising.
              </dd>
              <dt>Usual swing <span className="ai-guide-f">s = MAD( (k - lambda) / sqrt(lambda) )^2</span></dt>
              <dd>
                Every window of the last {facts?.spreadDays ?? 7} days is reduced to a
                standardised residual, and the spread of those residuals is the swing.
                <b> 1.0 means it behaves like chance.</b> The median absolute deviation is used
                rather than the standard deviation so that one genuine fault inside the window
                does not inflate the width it will be judged against next time.
              </dd>
              <dt>The band <span className="ai-guide-f">lambda +/- 3 x sqrt(s x lambda)</span></dt>
              <dd>
                Quasi-Poisson: the variance is <span className="ai-guide-f">s x lambda</span>
                {" "}rather than <span className="ai-guide-f">lambda</span>, which is what makes
                the width an inspector's own property rather than an assumption about it.
              </dd>
            </dl>
            <p>
              The two horizons differ on purpose. The level is a <b>current state</b> that has
              to follow the line when it changes; the swing is a <b>dispersion</b> that needs
              many windows to estimate and barely moves. They do not conflict, because the
              residual divides by the level that applied at that moment - the level's drift is
              taken out before the spread is read.
            </p>
          </section>
        </div>
      </aside>
    </div>
  );
}

function bandOf(f: AiFinding): string {
  return `${Math.floor(f.normalLow ?? 0)}-${Math.ceil(f.normalHigh ?? 0)}`;
}
