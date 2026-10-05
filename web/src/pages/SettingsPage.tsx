import { useEffect, useState } from "react";
import { fetchSettings, settingsSessionValid, unlockSettings, updateSetting } from "../api";
import type { Setting } from "../types";

/**
 * Operator-tunable values, grouped by what they affect rather than listed as one flat
 * key-value table - "defect_rate_critical_pct_example_e" means nothing to someone looking
 * for the Example E limit.
 */
const GROUPS: { title: string; blurb: string; match: (key: string) => boolean }[] = [
  {
    title: "Defect rate thresholds",
    blurb: "When a grid cell turns yellow, and when it turns red. Percentages of cells inspected.",
    match: (k) => k.startsWith("defect_rate_"),
  },
  {
    title: "Live status",
    blurb: "How quickly the screen calls an inspector idle or offline, when a stopped line becomes a breakdown, and how often the page refreshes.",
    match: (k) =>
      k.includes("threshold_seconds") || k.includes("poll_interval") ||
      k.includes("bucket_minutes") || k.includes("downtime"),
  },
  {
    title: "Production targets",
    blurb: "What a lot is expected to produce. Drives the Production tile and the Yield bars.",
    match: (k) => k.startsWith("daily_target_"),
  },
  {
    title: "Images",
    blurb: "Where defect images are kept on this PC, and how hard the server works to fetch them from the inspection PCs.",
    match: (k) => k.startsWith("image_"),
  },
  {
    title: "Retention",
    blurb: "How long each kind of record is kept before the nightly purge removes it.",
    match: (k) => k.endsWith("_retention_days"),
  },
];

export default function SettingsPage() {
  // Checked against the server, not just "is a token present": a token this tab kept
  // after the server expired it would otherwise open the page and fail every save.
  const [unlocked, setUnlocked] = useState<boolean | null>(null);

  useEffect(() => {
    let cancelled = false;
    settingsSessionValid().then((ok) => {
      if (!cancelled) setUnlocked(ok);
    });
    return () => {
      cancelled = true;
    };
  }, []);

  if (unlocked === null) {
    return <div className="page"><div className="state-note">Loading…</div></div>;
  }
  if (!unlocked) {
    return <LockScreen onUnlocked={() => setUnlocked(true)} />;
  }
  return <SettingsList />;
}

/**
 * Settings decide how the whole floor is scored, so they sit behind a password.
 *
 * The password is checked by the server rather than here - a value compared in the
 * browser would be sitting in the bundle for anyone who opened the developer tools.
 * What this page keeps is a token the server issued, and only that token can write.
 */
function LockScreen({ onUnlocked }: { onUnlocked: () => void }) {
  const [password, setPassword] = useState("");
  const [state, setState] = useState<"idle" | "checking" | "wrong">("idle");

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (!password) return;
    setState("checking");
    try {
      await unlockSettings(password);
      onUnlocked();
    } catch {
      setState("wrong");
      setPassword("");
    }
  }

  return (
    <div className="page lock-page">
      <form className="panel lock-card" onSubmit={submit}>
        <div className="lock-title">Settings are locked</div>
        <p className="lock-blurb">
          These values change how every line is measured. Enter the password to unlock them
          for this browser session.
        </p>
        <input
          type="password"
          autoFocus
          value={password}
          placeholder="Password"
          aria-label="Settings password"
          onChange={(e) => {
            setPassword(e.target.value);
            setState("idle");
          }}
        />
        {state === "wrong" && <div className="lock-error">That password was not accepted.</div>}
        <button type="submit" disabled={!password || state === "checking"}>
          {state === "checking" ? "Checking…" : "Unlock"}
        </button>
      </form>
    </div>
  );
}

function SettingsList() {
  const [settings, setSettings] = useState<Setting[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetchSettings()
      .then(setSettings)
      .catch((e) => setError(e instanceof Error ? e.message : String(e)));
  }, []);

  if (error) {
    return <div className="page"><div className="state-note">Cannot load settings. {error}</div></div>;
  }
  if (!settings) {
    return <div className="page"><div className="state-note">Loading…</div></div>;
  }

  const grouped = GROUPS.map((group) => ({
    title: group.title,
    blurb: group.blurb,
    rows: settings.filter((s) => group.match(s.key)),
  }));
  const claimed = new Set(grouped.flatMap((g) => g.rows.map((r) => r.key)));
  const rest = settings.filter((s) => !claimed.has(s.key));
  if (rest.length > 0) {
    grouped.push({ title: "Other", blurb: "", rows: rest });
  }

  return (
    <div className="page">
      {/* On Settings rather than the grid: the wall display is for the line, and a
          byline on it would be one more thing between an operator and the numbers. */}
      {grouped.map((group) =>
        group.rows.length === 0 ? null : (
          <div className="panel settings-group" key={group.title}>
            <h2>{group.title}</h2>
            {group.blurb && <p className="settings-blurb">{group.blurb}</p>}
            {group.rows.map((row) => (
              <SettingRow key={row.key} setting={row} />
            ))}
          </div>
        )
      )}
      <div className="credit">
        PKG Vision Dashboard · built by Minjun Lee, ESMI Cell Inspection Team
      </div>
    </div>
  );
}

function SettingRow({ setting }: { setting: Setting }) {
  const [value, setValue] = useState(setting.value);
  const [state, setState] = useState<"clean" | "saving" | "saved" | "failed">("clean");
  const [message, setMessage] = useState<string | null>(null);

  const dirty = value !== setting.value;

  async function save() {
    setState("saving");
    try {
      await updateSetting(setting.key, value);
      setting.value = value;
      setState("saved");
      setMessage(null);
      window.setTimeout(() => setState("clean"), 1500);
    } catch (e) {
      setState("failed");
      setMessage(e instanceof Error ? e.message : String(e));
    }
  }

  return (
    <div className="setting">
      <div className="setting-key">
        <code>{setting.key}</code>
        {setting.description && <span className="setting-desc">{setting.description}</span>}
        {message && <span className="setting-error">{message}</span>}
      </div>
      <input
        value={value}
        onChange={(e) => {
          setValue(e.target.value);
          setState("clean");
        }}
        onKeyDown={(e) => {
          if (e.key === "Enter" && dirty) save();
        }}
        aria-label={setting.key}
      />
      <button type="button" onClick={save} disabled={!dirty || state === "saving"}>
        {state === "saving" ? "Saving" : state === "saved" ? "Saved" : "Save"}
      </button>
    </div>
  );
}
