/**
 * How far back the rates on screen are measured over.
 *
 * The lot is the default because it is what the line is judged on, but a lot runs for
 * hours and an inspector that started failing ten minutes ago is invisible inside it.
 * The short windows are the question "who is bad right now".
 */
export const WINDOWS: { label: string; minutes: number | null }[] = [
  { label: "5m", minutes: 5 },
  { label: "10m", minutes: 10 },
  { label: "30m", minutes: 30 },
  { label: "1h", minutes: 60 },
  { label: "2h", minutes: 120 },
  { label: "LOT", minutes: null },
];

export function windowLabel(minutes: number | null): string {
  return WINDOWS.find((w) => w.minutes === minutes)?.label ?? "LOT";
}

export default function WindowFilter({
  value,
  onChange,
}: {
  value: number | null;
  onChange: (minutes: number | null) => void;
}) {
  return (
    <div className="window-filter" role="group" aria-label="Measurement window">
      <span className="window-label">Window</span>
      {WINDOWS.map((w) => (
        <button
          key={w.label}
          type="button"
          className={value === w.minutes ? "on" : ""}
          onClick={() => onChange(w.minutes)}
        >
          {w.label}
        </button>
      ))}
    </div>
  );
}
