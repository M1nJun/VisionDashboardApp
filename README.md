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

## The parts worth explaining

### One vocabulary, declared once

There is exactly one identifier for an inspection stage: **`vision_key`**
(`EXAMPLE_B_ANODE`, `EXAMPLE_C`, …), and `agent_id` is always `<line>_<vision_key>`.

Display names, judgement kinds, thresholds, image sets and the CSV parsing rules all come
from [`contracts/vision-catalog.json`](contracts/vision-catalog.json), which the agent, the
server and the frontend each read. **No code branches on vision type.** Adding an
inspection stage — or changing how one parses its CSV — is a catalog edit that all three
programs pick up without a code change.
[`contracts/topology.json`](contracts/topology.json) is the matching statement of physical
fact, and `contracts/validate.py` refuses a pair that disagrees.

### Getting a CSV to the server exactly once

The agent tails each inspector's CSV from a saved byte offset. Two rules make that
reliable:

- **The offset advances only after the server has accepted everything read from it.**
  Delivery is at-least-once by design: re-sending is harmless, losing a row is not.
- **The server filters replays** with a monotonic high-water mark of `unit_seq` per lot,
  so a re-send costs a comparison and nothing else.

Events go in batches of 500 over HTTP. A separate UDP heartbeat says the agent is alive
even when the line is stopped, so "not producing" and "not running" are distinguishable on
screen.

A cell that fails four inspection items is one cell, not four: the failed items travel
inside the event and land in `defect_items`, so counting is a row count with no rule
anywhere about which item counts. Units carry the time from the CSV's own `Time` column,
not the moment the agent read the row, so a backlog read in one pass still lands spread
across the hours it actually happened in.

### Counting production without counting it seven times

Every inspector on a line sees the *same* cells in sequence, so summing what they inspected
would count each product seven times.

- **A line's output** is one *reference inspector's* count, chosen by the catalog's
  `productionRefPriority`. When the first choice is offline and the second stands in, the
  response is marked `estimated: true` — an upstream station counts cells a later one would
  reject. With neither available the line reports `production: null` and drops out of the
  total.
- **Fleet defect rate** is every inspector's defect cells over that total: defects per
  hundred cells produced. **A grid cell's own rate** is measured against that inspector's
  own inspected count.

### Images, on a budget

Defect images live on the inspection PCs. Anything newer than 24 hours is pulled by a
worker every five seconds; older images are fetched **when somebody opens one**. That keeps
the central disk from filling with photographs nobody looks at without ever answering "too
old, sorry".

Retries run on a **time budget** with exponential backoff from 20 seconds to 10 minutes, so
a PC that reboots — or a vision that writes its CSV row before its image — costs a delay
rather than the image. Copies run six at a time so one unresponsive PC cannot hold up the
queue behind it.

### The AI report, and what "unusual" had to mean

A separate tab writes a plain-language summary of the floor every 30 minutes, using a local
model (`gemma3:12b` through Ollama) on the same PC. **Every number in it is computed in SQL
and Java before the model is called**, and the model is handed the finished figures as a
labelled text block with instructions not to calculate. A model that is down costs the
sentences, never the analysis — the figures are already stored, and the tab renders from
them.

The hard part was not the model. It was deciding what counts as unusual.

Comparing percentages does not work: one defect in 100 cells reads as 1.00% and outranks
six in 3,000 (0.20%), so every window where a line dipped put the quietest inspector at the
top. Counting against a Poisson expectation does not work either — real equipment
overdisperses, swinging about three times as far as chance allows, so a fixed statistical
test fires constantly. On five days of production that test called 166 of 221 windows an
alert, 153 of them on one inspector among 49 having a single odd half hour.

So each inspector is judged against **its own measured band**, in defects rather than
percentages:

| | what it measures | over |
|---|---|---|
| usual level | defects this much production normally yields | last 24 hours |
| usual swing | how far that count wanders window to window | last 7 days |

The band is `level ± 3 × √(swing × level)`. The two horizons differ on purpose: the level
is a *current state* that moves when the line changes, while the swing is a *dispersion*
that needs many windows to estimate and barely moves. They do not conflict, because the
swing is measured from residuals `(k − λ) / √λ` computed against a rolling 24-hour level —
the level's drift is divided out before the spread is read.

An alert is an **episode**, not a property of one window. When one is raised, the
inspector's pre-fault level and swing are frozen onto it and used for every later
comparison, and those windows are excluded when bands are re-measured. Without that a
rolling baseline learns that a broken inspector is normal: one station's expected count
would have drifted from 0.9 to 121 defects within a day, switching its own alert off while
the fault was still running. Confirmation takes two consecutive windows outside the band
and release takes two back inside, so the screen reports **when** something started rather
than how many windows it has spanned.

Tuned this way, alerts land at about three per twelve-hour shift.

---

## What this repository does not carry

The plant this was built for is not public, so nothing specific to it is committed.

- **`contracts/` holds an example pair.** Same structure, same seven-stage shape — with
  example inspection stages (`EXAMPLE_B_CATHODE`), example defect codes, example line
  designations (`A-1` … `C-3`) and example addresses. The real pair lives in a private
  folder and is pointed at when packaging a deployment. Everything else follows from the
  catalog, so the tests, the seeder and the demo all speak the example vocabulary and pass
  against it.
- **No production figures.** The targets in `db/schema.sql` are round example numbers, and
  every figure in the demo comes from `seed-demo.py`, which draws a fresh random set on
  each run. The AI report's example findings are written by hand, not captured.
- **No database credentials.** They reach the scripts and the server through the
  environment. (The *settings* lock — the password that guards editing thresholds from the
  UI — does ship with a default, overridable by `visiondash.settings.password`.)

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

---

## Layout

```
contracts/   vision-catalog.json, topology.json, events.md — the single source
             of truth, read by all three programs
db/          schema.sql and the migrations
agent/       .NET 8 Windows service: CSV tailing, parsing, batched transport
server/      Spring Boot 4.1 on Java 21 — ingest, grid, detail, images, ai, settings
web/         React 19 + TypeScript, built by Vite into the server's resources
deploy/      PowerShell driven by topology.json, plus RUNBOOK.md
```

Each of `agent/`, `server/` and `deploy/` carries its own `*-test.py`. None of them mock
anything: they drive the real agent executable, post over real HTTP and assert against real
SQL.

---

## Running it locally

Java 21, Node 20+, the .NET 8 SDK, MySQL 8.4 and Maven.

```bash
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS visiondash CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
mysql -u root -p visiondash < db/schema.sql
export VISIONDASH_DB_PASSWORD=your-mysql-root-password

cd web && npm install && npm run build      # the SPA compiles into the server's resources
cd ../server && mvn -o -DskipTests package
DB_USER=root DB_PASSWORD=$VISIONDASH_DB_PASSWORD java -jar target/server-1.0.0-SNAPSHOT.jar
```

The dashboard is then at <http://localhost:8080/dashboard/>. With no inspectors running the
grid is empty, so `python server/seed-demo.py` fills it with a shift's worth of data
(`--clear` puts it back). Full installation: [`deploy/RUNBOOK.md`](deploy/RUNBOOK.md).

> Spring Boot 4.1 uses Jackson **3** (`tools.jackson.*`). Jackson 2's
> `com.fasterxml.jackson.databind` imports will not compile; only the annotations remain
> under `com.fasterxml.jackson.annotation`.

The public demo is the same frontend built with `--mode demo`, which aliases `src/api.ts`
to `src/demo/` — every screen and interaction is the real component reading the real
response shapes. `.github/workflows/demo.yml` builds and publishes it on push to `main`.
