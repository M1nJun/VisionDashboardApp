import { createContext, useContext, useMemo, useState, type ReactNode } from "react";

interface WindowScope {
  minutes: number | null;
  setMinutes: (minutes: number | null) => void;
}

const WindowContext = createContext<WindowScope>({ minutes: null, setMinutes: () => {} });

/**
 * How far back the grid measures, held above the page.
 *
 * The control itself sits in the app bar - it retitles the whole screen, so it belongs
 * on the same line as the title rather than taking a strip of its own underneath. That
 * puts it outside the page component that uses it, which is what this context bridges.
 */
export function WindowProvider({ children }: { children: ReactNode }) {
  const [minutes, setMinutes] = useState<number | null>(null);
  const value = useMemo(() => ({ minutes, setMinutes }), [minutes]);
  return <WindowContext.Provider value={value}>{children}</WindowContext.Provider>;
}

export function useWindowScope(): WindowScope {
  return useContext(WindowContext);
}
