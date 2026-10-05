import { useEffect, useMemo, useRef, useState } from "react";
import { fetchOccurrence } from "../api";
import { judgeClass } from "./NgEventsTable";
import type { NgEvent, Occurrence } from "../types";

/**
 * One failed unit, opened from the event table.
 *
 * A unit that failed several checks is one entry here, not several - the label carries
 * every item it failed and the image sets are the ones belonging to that unit. Splitting
 * them made the same photo appear repeatedly and made the list look longer than the
 * number of units actually affected.
 *
 * The images and measurements are fetched when the viewer opens rather than shipped with
 * the table, which polls every few seconds and would otherwise carry megabytes of image
 * metadata for rows nobody looks at.
 */
export default function DefectViewer({
  line,
  visionKey,
  events,
  index,
  onIndex,
  onClose,
}: {
  line: string;
  visionKey: string;
  events: NgEvent[];
  index: number;
  onIndex: (next: number) => void;
  onClose: () => void;
}) {
  const [occurrence, setOccurrence] = useState<Occurrence | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [showOverlay, setShowOverlay] = useState(true);
  // Zoom and pan belong to a picture, not to the window. A unit photographed from two
  // sides is two separate examinations - zooming into the upper weld should not drag
  // the lower one along with it.
  const [views, setViews] = useState<Record<string, View>>({});
  const drag = useRef<{ set: string; x: number; y: number } | null>(null);
  const surface = useRef<HTMLDivElement>(null);

  const viewOf = (set: string): View => views[set] ?? FLAT;
  const setView = (set: string, next: (from: View) => View) =>
    setViews((all) => ({ ...all, [set]: next(all[set] ?? FLAT) }));

  /**
   * Keeps the picture covering its frame.
   *
   * The image is centred and scaled about its middle, so at zoom z it overhangs the
   * frame by (z-1)/2 on each side - that overhang is exactly how far it may be pushed
   * before an edge comes into view. Unclamped, a drag could shove the whole photo out
   * of the pane and leave the operator staring at an empty box with no way back but
   * Reset view.
   */
  const clamp = (set: string, next: { x: number; y: number }, z: number) => {
    const frame = surface.current
      ?.querySelector(`[data-set="${CSS.escape(set)}"] .canvas-frame`);
    if (!frame) return next;
    const box = frame.getBoundingClientRect();
    const maxX = Math.max(0, (box.width * (z - 1)) / 2);
    const maxY = Math.max(0, (box.height * (z - 1)) / 2);
    return {
      x: Math.min(maxX, Math.max(-maxX, next.x)),
      y: Math.min(maxY, Math.max(-maxY, next.y)),
    };
  };

  const event = events[index];

  useEffect(() => {
    if (!event) return;
    let cancelled = false;
    setOccurrence(null);
    setError(null);
    fetchOccurrence(line, visionKey, event.id)
      .then((o) => {
        if (!cancelled) setOccurrence(o);
      })
      .catch((e) => {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e));
      });
    return () => {
      cancelled = true;
    };
  }, [line, visionKey, event?.id]);

  const reset = () => setViews({});
  useEffect(reset, [event?.id]);
  const untouched = Object.values(views).every(
    (v) => v.zoom === 1 && v.offset.x === 0 && v.offset.y === 0
  );

  /**
   * The wheel zooms the picture.
   *
   * Registered here rather than as an onWheel prop because React attaches wheel
   * listeners passively, and a passive listener may not call preventDefault - so the
   * modal would scroll underneath the zoom. This one is explicitly non-passive.
   */
  useEffect(() => {
    const pane = surface.current;
    if (!pane) return;
    const onWheel = (e: WheelEvent) => {
      const set = setUnder(e.target);
      if (set === null) return;
      e.preventDefault();
      setView(set, (from) => {
        const zoom = Math.min(8, Math.max(1, from.zoom * (e.deltaY < 0 ? 1.12 : 1 / 1.12)));
        // Zooming out shrinks the room to pan, so the offset has to come back with it
        // or the picture slides off as it gets smaller.
        return { zoom, offset: clamp(set, from.offset, zoom) };
      });
    };
    pane.addEventListener("wheel", onWheel, { passive: false });
    return () => pane.removeEventListener("wheel", onWheel);
  }, [occurrence]);

  // Escape closes and the arrows step, so the viewer can be worked without the mouse
  // leaving the image.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
      if (e.key === "ArrowLeft" && index > 0) onIndex(index - 1);
      if (e.key === "ArrowRight" && index < events.length - 1) onIndex(index + 1);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [index, events.length, onClose, onIndex]);

  // Grouped so a unit with LOWER and UPPER (or LEFT and RIGHT) shows both side by side,
  // each with its overlay stacked on top rather than as a separate tile.
  const sets = useMemo(() => {
    const map = new Map<string, { main?: Occurrence["images"][number]; overlay?: Occurrence["images"][number] }>();
    for (const image of occurrence?.images ?? []) {
      const entry = map.get(image.set) ?? {};
      if (image.kind === "MAIN") entry.main = image;
      else entry.overlay = image;
      map.set(image.set, entry);
    }
    return [...map.entries()];
  }, [occurrence]);

  // The window is sized to what it has to hold. A vision type that photographs one face
  // was given the two-image window and half of it stood empty, with the picture no
  // bigger for it; a narrower window puts the same picture on a frame of its own.
  const solo = sets.length === 1;

  if (!event) return null;

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div
        className={`modal viewer-modal${solo ? " solo" : ""}`}
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
      >
        <div className="modal-head">
          <div className="modal-title">
            <span className="viewer-cell">{event.cellId ?? "–"}</span>
            <span className={`judge-tag ${judgeClass(event.judgement)}`}>{event.judgement}</span>
            <span className="viewer-defect">{event.label}</span>
          </div>

          {/* The time is the first thing anyone cross-references against the line's own
              logs, so it is set out as a heading rather than buried in a subtitle. */}
          <div className="viewer-when">
            <span className="when-time">{clock(event.occurredAt)}</span>
            <span className="when-date">{day(event.occurredAt)}</span>
          </div>

          <div className="modal-actions">
            <button type="button" onClick={() => onIndex(index - 1)} disabled={index === 0}>
              ◀
            </button>
            <span className="viewer-pos">
              {index + 1} / {events.length}
            </span>
            <button
              type="button"
              onClick={() => onIndex(index + 1)}
              disabled={index >= events.length - 1}
            >
              ▶
            </button>
            {/* A pressed-state button rather than a checkbox: this is the control an
                operator reaches for most, and a 13px tick box next to grey text was
                both hard to hit and hard to read the state of. */}
            <button
              type="button"
              className={`viewer-toggle${showOverlay ? " on" : ""}`}
              aria-pressed={showOverlay}
              onClick={() => setShowOverlay((on) => !on)}
            >
              <span className="toggle-track"><span className="toggle-knob" /></span>
              Overlay
            </button>
            <button type="button" className="modal-close" onClick={onClose}>
              <span aria-hidden="true">×</span> Close
            </button>
          </div>
        </div>

        <div className="modal-body">
          {error && <div className="state-note">Could not load this defect. {error}</div>}
          {!error && !occurrence && <div className="state-note">Loading…</div>}

          {occurrence && sets.length === 0 && (
            <div className="state-note">This defect carries no images.</div>
          )}

          {/* Zoom is done on the picture, so the control that undoes it lives with the
              picture rather than across the window in the title bar. */}
          {occurrence && sets.length > 0 && (
            <div className="viewer-tools">
              <button type="button" onClick={reset} disabled={untouched}>
                Reset view
              </button>
            </div>
          )}

          {occurrence && sets.length > 0 && (
            <div
              className={solo ? "viewer-canvases solo" : "viewer-canvases"}
              ref={surface}
              onPointerDown={(e) => {
                const set = setUnder(e.target);
                if (set === null) return;
                const from = viewOf(set).offset;
                drag.current = { set, x: e.clientX - from.x, y: e.clientY - from.y };
                (e.target as HTMLElement).setPointerCapture?.(e.pointerId);
              }}
              onPointerMove={(e) => {
                const held = drag.current;
                if (!held) return;
                setView(held.set, (from) => ({
                  zoom: from.zoom,
                  offset: clamp(held.set, { x: e.clientX - held.x, y: e.clientY - held.y }, from.zoom),
                }));
              }}
              onPointerUp={() => {
                drag.current = null;
              }}
            >
              {sets.map(([label, pair]) => {
                const view = viewOf(label);
                return (
                  <figure key={label} className="canvas" data-set={label}>
                    <figcaption>
                      {label}
                      {view.zoom > 1 && <span className="canvas-zoom">{view.zoom.toFixed(1)}x</span>}
                    </figcaption>
                    <div className="canvas-frame">
                      <Layer image={pair.main} zoom={view.zoom} offset={view.offset} />
                      {showOverlay && (
                        <Layer image={pair.overlay} zoom={view.zoom} offset={view.offset} overlay />
                      )}
                    </div>
                  </figure>
                );
              })}

            </div>
          )}

          {occurrence && occurrence.items.length > 0 && (
            <div className="viewer-items">
              {occurrence.items.map((item) => (
                <span key={`${item.name}|${item.side ?? ""}`} className="viewer-item">
                  {item.name}
                  {item.side ? <em>{item.side}</em> : null}
                  {item.rawValue ? <b>{item.rawValue}</b> : null}
                </span>
              ))}
            </div>
          )}
        </div>

        {/* Every control this window has, named and explained. As a single grey line of
            prose it was the one part of the viewer nobody read. */}
        <div className="viewer-guide">
          <div className="guide-title">User Guide</div>
          <dl className="guide-items">
            <div><dt><kbd>Scroll</kbd></dt><dd>Zoom the image under the pointer</dd></div>
            <div><dt><kbd>Drag</kbd></dt><dd>Move a zoomed image in its frame</dd></div>
            <div><dt><kbd>←</kbd><kbd>→</kbd></dt><dd>Previous / next defect</dd></div>
            <div><dt>Overlay</dt><dd>Show or hide the inspection markings</dd></div>
            <div><dt>Reset view</dt><dd>Back to the whole image</dd></div>
            <div><dt><kbd>Esc</kbd></dt><dd>Close this window</dd></div>
          </dl>
        </div>
      </div>
    </div>
  );
}

/** How one picture is being looked at. */
type View = { zoom: number; offset: { x: number; y: number } };

const FLAT: View = { zoom: 1, offset: { x: 0, y: 0 } };

/** Which image set an event landed on, or null if it missed them all. */
function setUnder(target: EventTarget | null): string | null {
  const figure = (target as HTMLElement | null)?.closest?.("[data-set]");
  return figure?.getAttribute("data-set") ?? null;
}

function Layer({
  image,
  zoom,
  offset,
  overlay = false,
}: {
  image: Occurrence["images"][number] | undefined;
  zoom: number;
  offset: { x: number; y: number };
  overlay?: boolean;
}) {
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    setFailed(false);
  }, [image?.url]);

  if (!image) {
    return overlay ? null : <div className="canvas-state">No image path recorded</div>;
  }
  if (!image.url) {
    return overlay ? null : <div className="canvas-state">Image unavailable</div>;
  }
  if (failed) {
    // Requesting a pending image is what asks the server to pull it, so a failure here
    // means the inspection PC did not answer - not that the image is gone for good.
    return overlay ? null : (
      <div className="canvas-state">Still fetching from the inspection PC…</div>
    );
  }
  return (
    <img
      className={overlay ? "canvas-img overlay" : "canvas-img"}
      src={image.url}
      alt={overlay ? "Defect overlay" : "Defect image"}
      draggable={false}
      onError={() => setFailed(true)}
      style={{ transform: `translate(${offset.x}px, ${offset.y}px) scale(${zoom})` }}
    />
  );
}

function clock(iso: string | null): string {
  if (!iso) return "–";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "–" : d.toLocaleTimeString("en-GB", { hour12: false });
}

function day(iso: string | null): string {
  if (!iso) return "";
  const d = new Date(iso);
  return Number.isNaN(d.getTime())
    ? ""
    : d.toLocaleDateString("en-GB", { month: "short", day: "2-digit" });
}
