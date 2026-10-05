import { createContext, useContext } from "react";
import type { Catalog } from "../types";

/**
 * The catalog is fetched once at start-up and is a hard dependency: without it the UI
 * does not know which inspectors exist, what judgements they produce, or what counts as
 * a bad rate. Making it a context rather than a per-page fetch means no component can
 * quietly fall back to a guess.
 */
export const CatalogContext = createContext<Catalog | null>(null);

export function useCatalog(): Catalog {
  const catalog = useContext(CatalogContext);
  if (!catalog) {
    throw new Error("useCatalog used outside a loaded CatalogContext");
  }
  return catalog;
}
