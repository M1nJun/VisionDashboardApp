import { useEffect, useRef, useState } from "react";
import { NavLink, Route, Routes, useLocation } from "react-router-dom";
import { fetchBuildId, fetchCatalog } from "./api";
import Logo from "./components/Logo";
import WindowFilter from "./components/WindowFilter";
import AlertPopup from "./components/ai/AlertPopup";
import { CatalogContext } from "./hooks/useCatalog";
import { useNewAlerts } from "./hooks/useNewAlerts";
import { WindowProvider, useWindowScope } from "./hooks/useWindow";
import AiReportPage from "./pages/AiReportPage";
import DetailPage from "./pages/DetailPage";
import GridPage from "./pages/GridPage";
import SettingsPage from "./pages/SettingsPage";
import type { Catalog } from "./types";

export default function App() {
  const [catalog, setCatalog] = useState<Catalog | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetchCatalog()
      .then(setCatalog)
      .catch((e) => setError(e instanceof Error ? e.message : String(e)));
  }, []);

  useBuildWatch();
  // Above the routes rather than inside the AI tab: the floor watches the grid, and an
  // alert nobody is on the right tab to see is not an alert. On the AI tab itself it
  // stays quiet - the report is already open, and covering it to announce it is noise.
  const { alerts, dismiss } = useNewAlerts(
    useLocation().pathname === "/ai",
    import.meta.env.MODE === "demo" ? 8_000 : undefined,
  );

  if (error) {
    return (
      <div className="app">
        <AppBar />
        <div className="page">
          <div className="state-note">Cannot reach the dashboard server. {error}</div>
        </div>
      </div>
    );
  }

  if (!catalog) {
    return (
      <div className="app">
        <AppBar />
        <div className="page">
          <div className="state-note">Loading…</div>
        </div>
      </div>
    );
  }

  return (
    <CatalogContext.Provider value={catalog}>
      <WindowProvider>
        <div className="app">
          <AppBar />
          <Routes>
            <Route path="/" element={<GridPage />} />
            <Route path="/ai" element={<AiReportPage />} />
            <Route path="/vision/:line/:visionKey" element={<DetailPage />} />
            <Route path="/settings" element={<SettingsPage />} />
          </Routes>
          {alerts && <AlertPopup alerts={alerts} onClose={dismiss} />}
        </div>
      </WindowProvider>
    </CatalogContext.Provider>
  );
}

function AppBar() {
  const { minutes, setMinutes } = useWindowScope();
  // Only on the grid: it scopes the grid's rates, and nothing on the other screens.
  const onGrid = useLocation().pathname === "/";

  return (
    <header className="app-bar">
      <Logo className="app-logo" />
      <span className="app-title">PKG Vision Dashboard</span>
      {/* Said plainly and permanently: this build answers from a recording of a seeded
          plant, and nobody should read a number on it as a real one. */}
      {import.meta.env.MODE === "demo" && (
        <span className="demo-tag" title="Example data captured from a seeded server. Inspection stages, defect codes and line names are examples.">
          Demo
        </span>
      )}
      {onGrid && (
        <div className="app-bar-window">
          <WindowFilter value={minutes} onChange={setMinutes} />
        </div>
      )}
      <nav>
        <NavLink to="/ai" className={({ isActive }) => (isActive ? "active" : "")}>
          AI Report
        </NavLink>
        <NavLink to="/" end className={({ isActive }) => (isActive ? "active" : "")}>
          Grid
        </NavLink>
        <NavLink to="/settings" className={({ isActive }) => (isActive ? "active" : "")}>
          Settings
        </NavLink>
      </nav>
      <Clock />
    </header>
  );
}

/**
 * Reloads the page when the server it is talking to has been redeployed.
 *
 * The catalog is read once, at mount. A dashboard left open across a deployment kept
 * drawing the topology it started with - lines that no longer exist - while polling the
 * new server for everything else, and reading fields the new API had stopped sending.
 * Nothing threw, so the screen was merely wrong, and on a wall nobody is sitting at
 * there is no one to think of pressing refresh.
 *
 * A failed check is ignored on purpose: the server being briefly unreachable during a
 * restart is the normal case, and reloading then would only show an error page.
 */
function useBuildWatch() {
  const seen = useRef<string | null>(null);

  useEffect(() => {
    let stopped = false;

    const check = async () => {
      try {
        const id = await fetchBuildId();
        if (stopped) return;
        if (seen.current === null) {
          seen.current = id;
        } else if (seen.current !== id) {
          window.location.reload();
        }
      } catch {
        // Server restarting, or too old to have this endpoint. Try again later.
      }
    };

    void check();
    const timer = window.setInterval(check, 30_000);
    return () => {
      stopped = true;
      window.clearInterval(timer);
    };
  }, []);
}

/** A wall display that has quietly frozen looks identical to one that is up to date;
 *  a ticking clock is the cheapest way to tell them apart from across the floor. */
function Clock() {
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const id = window.setInterval(() => setNow(new Date()), 1000);
    return () => window.clearInterval(id);
  }, []);
  return <span className="app-clock">{now.toLocaleTimeString("en-GB", { hour12: false })}</span>;
}
