import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

/** Declared rather than pulled in from @types/node: the config needs one optional
 *  override and nothing else Node offers. */
declare const process: { env: Record<string, string | undefined> };

/**
 * Two builds from one source.
 *
 * The default build ships the SPA inside the server jar: one process on the central PC
 * serves both the API and the pages, so there is no second thing to start, port to
 * open, or version to keep in step. Building writes straight into the jar's static
 * resources - after any frontend change the server must be repackaged to carry it.
 *
 * `--mode demo` builds a static site for GitHub Pages, where there is no server to talk
 * to. It swaps `src/api.ts` for `src/demo/`, which answers from data captured off a
 * real seeded server. That swap is an alias rather than a branch inside the app, so no
 * screen, hook or component knows which build it is in - and the default build never
 * carries the fixtures.
 */
export default defineConfig(({ mode }) => {
  const demo = mode === "demo";

  return {
    plugins: [react()],
    // Stamped into the bundle so a running page can tell whether it is the one that is
    // currently deployed - see the version check in src/demo/index.ts.
    define: {
      __BUILD_ID__: JSON.stringify(process.env.VITE_BUILD_ID || "dev"),
    },
    // The repository name Pages serves the demo under; VITE_BASE overrides it.
    base: demo ? process.env.VITE_BASE || "/VisionDashboard/" : "/dashboard/",
    resolve: {
      alias: demo
        ? [
            {
              // Both "./api" (App) and "../api" (pages, components). A leading slash
              // makes the replacement resolve from the project root.
              find: /^\.\.?\/api$/,
              replacement: "/src/demo/index.ts",
            },
          ]
        : [],
    },
    build: {
      outDir: demo ? "../dist/demo" : "../server/src/main/resources/static",
      emptyOutDir: true,
      // Everything is bundled and hashed, fonts included. The factory network is
      // offline, so a runtime request to any CDN would simply never resolve.
      assetsInlineLimit: 0,
    },
    server: {
      proxy: {
        "/dashboard/api": "http://127.0.0.1:8080",
      },
    },
  };
});
