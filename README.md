# Vision Dashboard

A live production and defect-rate dashboard for a seven-line pouch-cell inspection process,
with a half-hourly shift report written by a local language model.

Built by Minjun Lee, ESMI Cell Inspection Team, LG Energy Solution.

**[Try the live demo &rarr;](https://m1njun.github.io/VisionDashboardApp/)** &nbsp;·&nbsp; the real
frontend on captured data, with example vocabulary in place of the process's own.

---

## The problem

Forty-two vision inspection PCs across seven production lines each write their results to a
CSV file on their own local disk. Each machine knows its own numbers and nothing else, so
there was no way to see the process as a whole — answering "which inspector is having a bad
shift?" meant walking the floor and reading screens one at a time.

This system reads those CSVs where they are written, forwards them to one central PC, and
turns them into a single display readable from across the factory: every inspector's defect
rate at a glance, current-lot output per line, one click into any inspector for its trend,
its defect list and the photographs behind each one — and a written summary of what changed
in the last half hour.

The factory network is offline — no CDN, no package registry, no cloud — so everything
ships as one folder carried to the central PC on a USB stick, language model included.

```
  42 inspection PCs                        central PC
┌──────────────────────┐              ┌──────────────────────────────────┐
│  VisionAgent (C#)    │  HTTP batch  │  Spring Boot (one process)       │
│  Windows service     │ ───────────► │    ├ ingest (events, heartbeats) │
│  tails CSV + alarms  │              │    ├ image fetch worker          │
│                      │  UDP beat    │    ├ AI report (Ollama, local)   │
└──────────────────────┘ ───────────► │    ├ aggregation + REST API      │
                                      │    └ serves the dashboard SPA    │
                                      └───────────────┬──────────────────┘
                                              MySQL 8.4 (visiondash)
```

Three programs, one contract between them: a .NET 8 Windows service on each inspection PC,
one Spring Boot process on the central PC that also serves the UI, and a React frontend
compiled into that process's jar.

---

## API

Everything is under the `/dashboard` context path.

| Endpoint | Purpose |
|---|---|
| `POST /api/ingest/events` | a batch of agent events |
| `GET /api/catalog` | vision types, lines, thresholds, poll interval. **The frontend keeps no copy of its own** |
| `GET /api/grid` | 49 cells, per-line output, fleet totals |
| `GET /api/vision/{line}/{visionKey}` | detail: trend, NG events, images, alarms |
| `GET /api/vision/{line}/{visionKey}/defects/{id}` | one defect: its items and image paths |
| `GET /api/images/{id}` | a defect image, fetched on the spot if not cached |
| `GET /api/ai/status`, `GET /api/ai/reports` | whether the report can run, and the recent ones with the facts behind them |
| `POST /api/ai/run` | re-runs the window that just closed (needs the unlock token) |
| `GET /api/settings`, `PUT /api/settings/{key}` | operator-tunable values (PUT needs the unlock token) |
| `GET /api/build` | a fingerprint the page polls, so a deployed change reaches an open browser |

Screens: `/dashboard/` (grid), `/dashboard/ai` (AI report),
`/dashboard/vision/{line}/{visionKey}` (detail), `/dashboard/settings`.