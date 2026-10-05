-- Vision Dashboard - central DB schema
-- Target: MySQL 8.4 / InnoDB / utf8mb4
--   mysql -u root -p -e "CREATE DATABASE visiondash CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
--   mysql -u root -p visiondash < schema.sql
--
-- Two rules this schema exists to enforce structurally, rather than by
-- convention in application code:
--   1. One physical unit = one counted row, everywhere. A unit that fails
--      several inspection items is ONE defect_occurrences row with N
--      defect_items children - never N countable rows that some later query
--      has to remember to re-merge.
--   2. Judgement types (NG / DLNG / C-NG / whatever comes next) are data,
--      not columns. Adding a judgement to a vision type must not need DDL.


-- ---------------------------------------------------------------------------
-- Identity
-- ---------------------------------------------------------------------------

-- One row per logical inspector. A Example A PC runs one process but reports
-- as two agents, so agent identity is (line, vision_key), never the process.
-- The UNIQUE on (line, vision_key) is load-bearing: the dashboard grid looks
-- cells up by that pair, so two agent_ids claiming one slot (a rename, a
-- half-finished redeploy) must fail at INSERT rather than silently hide one
-- of them from the grid.
CREATE TABLE IF NOT EXISTS agents (
  agent_id          VARCHAR(64)  NOT NULL PRIMARY KEY,
  line              VARCHAR(16)  NOT NULL,
  vision_key        VARCHAR(32)  NOT NULL,
  current_lot_id    VARCHAR(64)      NULL,
  current_model_id  VARCHAR(64)      NULL,
  last_source_file  VARCHAR(255)     NULL,
  last_event_at     DATETIME(3)      NULL,
  last_heartbeat_at DATETIME(3)      NULL,
  last_heartbeat_ip VARCHAR(45)      NULL,
  agent_version     VARCHAR(32)      NULL,
  created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uq_agents_slot (line, vision_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ---------------------------------------------------------------------------
-- Exactly-once ingest
-- ---------------------------------------------------------------------------

-- An agent that restarts, or whose offset file lags behind what the server
-- already stored, replays rows it has already sent. Rather than keeping a
-- dedup row per unit forever (the old design kept a 512-byte unique key per
-- OK cell), progress is a high-water mark: anything at or below last_unit_seq
-- is a replay and is discarded before it can touch a counter.
--
-- Keyed by LOT, not by file. The vision software numbers rows against the lot:
-- NO restarts at 1 whenever the lot changes - including mid-file - and carries
-- on across a file rollover as long as the lot is unchanged. Keying this by
-- file assumed the opposite, so the first lot change left a high-water mark
-- that every subsequent row fell below, and the whole fleet went silent while
-- still reporting healthy.
--
-- replayed_count is not bookkeeping for its own sake - if it climbs while the
-- line is running normally, the "unit_seq always increases within a lot"
-- assumption is wrong somewhere and the ingest path needs to say so.
CREATE TABLE IF NOT EXISTS agent_lot_progress (
  agent_id       VARCHAR(64) NOT NULL,
  lot_id         VARCHAR(64) NOT NULL,
  last_unit_seq  BIGINT      NOT NULL,
  replayed_count BIGINT      NOT NULL DEFAULT 0,
  updated_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (agent_id, lot_id),
  CONSTRAINT fk_progress_agent FOREIGN KEY (agent_id) REFERENCES agents(agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ---------------------------------------------------------------------------
-- Current-lot counters (the grid's hot path - O(1) per cell)
-- ---------------------------------------------------------------------------

-- Reset to zero on LOT_CHANGED, in the same transaction that copies the old
-- values into lot_history.
CREATE TABLE IF NOT EXISTS lot_counters (
  agent_id          VARCHAR(64) NOT NULL PRIMARY KEY,
  lot_id            VARCHAR(64)     NULL,
  lot_started_at    DATETIME(3)     NULL,
  inspected_count   BIGINT      NOT NULL DEFAULT 0,
  defect_unit_count BIGINT      NOT NULL DEFAULT 0,
  unknown_count     BIGINT      NOT NULL DEFAULT 0,
  updated_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_counters_agent FOREIGN KEY (agent_id) REFERENCES agents(agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Per-judgement breakdown as rows, so a vision type that only ever produces NG
-- carries one row while example_b carries three - and a fourth judgement needs no
-- schema change. SUM over these always equals defect_unit_count.
CREATE TABLE IF NOT EXISTS lot_counter_judgement (
  agent_id   VARCHAR(64) NOT NULL,
  judgement  VARCHAR(16) NOT NULL,
  unit_count BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (agent_id, judgement),
  CONSTRAINT fk_counter_judgement_agent FOREIGN KEY (agent_id) REFERENCES agents(agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ---------------------------------------------------------------------------
-- Time-series rollup (the trend chart's source)
-- ---------------------------------------------------------------------------

-- Written in the same transaction as lot_counters, from the same unit-level
-- delta, so the trend chart and the grid can never disagree about how many
-- units were produced or how many failed. (They used to: the grid read
-- unit-level counters while the trend counted raw event rows, which
-- multi-item defects inflated.)
--
-- lot_id is part of the key so a bucket spanning a lot change splits in two
-- and current-lot trends stay exact. Bucket width is fixed at
-- ROLLUP_BUCKET_MINUTES in the server; the UI re-aggregates upward from it.
CREATE TABLE IF NOT EXISTS vision_rollup (
  agent_id          VARCHAR(64) NOT NULL,
  bucket_start      DATETIME    NOT NULL,
  lot_id            VARCHAR(64) NOT NULL DEFAULT '',
  inspected_count   BIGINT      NOT NULL DEFAULT 0,
  defect_unit_count BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (agent_id, bucket_start, lot_id),
  KEY idx_rollup_time (bucket_start),
  CONSTRAINT fk_rollup_agent FOREIGN KEY (agent_id) REFERENCES agents(agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS vision_rollup_judgement (
  agent_id     VARCHAR(64) NOT NULL,
  bucket_start DATETIME    NOT NULL,
  lot_id       VARCHAR(64) NOT NULL DEFAULT '',
  judgement    VARCHAR(16) NOT NULL,
  unit_count   BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (agent_id, bucket_start, lot_id, judgement),
  CONSTRAINT fk_rollup_judgement_agent FOREIGN KEY (agent_id) REFERENCES agents(agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ---------------------------------------------------------------------------
-- Lot history
-- ---------------------------------------------------------------------------

-- UNIQUE(agent_id, lot_id) is the dedup key for LOT_CHANGED: a replayed lot
-- change can't close the same lot twice and double-write history.
CREATE TABLE IF NOT EXISTS lot_history (
  id                BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
  agent_id          VARCHAR(64) NOT NULL,
  lot_id            VARCHAR(64) NOT NULL,
  model_id          VARCHAR(64)     NULL,
  started_at        DATETIME(3)     NULL,
  ended_at          DATETIME(3)     NULL,
  inspected_count   BIGINT      NOT NULL DEFAULT 0,
  defect_unit_count BIGINT      NOT NULL DEFAULT 0,
  end_reason        VARCHAR(32) NOT NULL DEFAULT 'LOT_CHANGE',
  UNIQUE KEY uq_lot_history (agent_id, lot_id),
  KEY idx_lot_history_ended (agent_id, ended_at),
  CONSTRAINT fk_lot_history_agent FOREIGN KEY (agent_id) REFERENCES agents(agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS lot_history_judgement (
  lot_history_id BIGINT      NOT NULL,
  judgement      VARCHAR(16) NOT NULL,
  unit_count     BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (lot_history_id, judgement),
  CONSTRAINT fk_lot_hist_judgement FOREIGN KEY (lot_history_id) REFERENCES lot_history(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ---------------------------------------------------------------------------
-- Defects: one occurrence = one physical unit
-- ---------------------------------------------------------------------------

-- UNIQUE(agent_id, lot_id, unit_seq) is the whole point: the database itself
-- refuses to store one physical unit twice, so no counting query ever has to
-- reconstruct "which of these rows were really the same cell".
--
-- The key is the lot, not the file. NO is numbered against the lot and restarts
-- at 1 when the lot changes, so two genuinely different units in one file can
-- share a NO - keying this by file silently discarded the second one.
-- source_file is kept for tracing back to the CSV, and nothing else.
CREATE TABLE IF NOT EXISTS defect_occurrences (
  id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  agent_id     VARCHAR(64)  NOT NULL,
  line         VARCHAR(16)  NOT NULL,
  vision_key   VARCHAR(32)  NOT NULL,
  source_file  VARCHAR(255) NOT NULL,
  unit_seq     BIGINT       NOT NULL,
  cell_id      VARCHAR(64)      NULL,
  model_id     VARCHAR(64)      NULL,
  -- Empty rather than NULL, because it is part of the unique key above and
  -- MySQL lets a UNIQUE index hold any number of NULLs - a unit arriving
  -- without a lot would slip past dedup entirely.
  lot_id       VARCHAR(64)  NOT NULL DEFAULT '',
  judgement    VARCHAR(16)  NOT NULL,
  -- Denormalised '+'-joined item names ("ALIGN_OFF + CHECK_B"), maintained at
  -- insert time. The detail page groups and filters by this constantly, and
  -- rebuilding it with GROUP_CONCAT on every poll both cost a join and hit
  -- group_concat_max_len truncation at 1024 bytes.
  item_summary VARCHAR(512)     NULL,
  item_count   INT          NOT NULL DEFAULT 0,
  occurred_at  DATETIME(3)      NULL,
  received_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uq_occurrence_unit (agent_id, lot_id, unit_seq),
  KEY idx_occ_agent_lot_time (agent_id, lot_id, received_at),
  KEY idx_occ_agent_judgement (agent_id, lot_id, judgement),
  CONSTRAINT fk_occ_agent FOREIGN KEY (agent_id) REFERENCES agents(agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- One row per failed inspection item on that unit.
--
-- raw_value is the paired column's original text ("14.16007", "NG", ...).
-- item_name is what filters and grouping use, because a raw measurement is
-- almost never the same twice; raw_value is kept anyway because it is the only
-- copy of the measurement and cannot be recovered after the fact - the example_b
-- defect-coordinate feature needs exactly this kind of value.
-- side is part of the key, and empty rather than NULL, because example_b can fail the
-- same named defect on both LOWER and UPPER of one unit and those are two distinct
-- failure locations - which is also the granularity the defect-coordinate feature
-- will need. Scan-mode vision types have no side and store '' (NULL would let MySQL
-- accept unlimited duplicates, since NULLs never compare equal in a UNIQUE index).
CREATE TABLE IF NOT EXISTS defect_items (
  id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  occurrence_id BIGINT       NOT NULL,
  seq           INT          NOT NULL,
  item_name     VARCHAR(128) NOT NULL,
  raw_value     VARCHAR(255)     NULL,
  value_num     DOUBLE           NULL,
  side          VARCHAR(16)  NOT NULL DEFAULT '',
  UNIQUE KEY uq_item (occurrence_id, item_name, side),
  KEY idx_item_name (item_name),
  CONSTRAINT fk_item_occurrence FOREIGN KEY (occurrence_id) REFERENCES defect_occurrences(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ---------------------------------------------------------------------------
-- Images
-- ---------------------------------------------------------------------------

-- Attached to the occurrence, not to an item: the image paths in a CSV row
-- describe the whole physical unit, so hanging them off items duplicated the
-- same file once per failed item.
--
-- One row per FILE (main and overlay are separate rows), so a missing overlay
-- can no longer take a perfectly good main image down with it.
--
-- state: pending -> ready, or -> unavailable when the retry budget expires.
-- next_attempt_at/expires_at drive exponential backoff against a *time* budget
-- rather than an attempt count, so a PC that reboots for two minutes no longer
-- burns through its retries and loses those images permanently. A configuration
-- problem must never produce 'unavailable' - that state means the source file
-- itself could not be read within the budget.
CREATE TABLE IF NOT EXISTS defect_images (
  id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  occurrence_id   BIGINT       NOT NULL,
  set_label       VARCHAR(16)  NOT NULL,
  kind            ENUM('MAIN','OVERLAY') NOT NULL,
  source_path     VARCHAR(500) NOT NULL,
  local_path      VARCHAR(500)     NULL,
  byte_size       BIGINT           NULL,
  state           ENUM('pending','ready','unavailable') NOT NULL DEFAULT 'pending',
  attempts        INT          NOT NULL DEFAULT 0,
  next_attempt_at DATETIME(3)      NULL,
  expires_at      DATETIME(3)      NULL,
  last_error      VARCHAR(500)     NULL,
  last_viewed_at  DATETIME(3)      NULL,
  fetched_at      DATETIME(3)      NULL,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uq_image (occurrence_id, set_label, kind),
  -- The fetch worker's queue scan: "what is due right now".
  KEY idx_image_queue (state, next_attempt_at),
  KEY idx_image_cleanup (state, last_viewed_at),
  CONSTRAINT fk_image_occurrence FOREIGN KEY (occurrence_id) REFERENCES defect_occurrences(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ---------------------------------------------------------------------------
-- Equipment alarms (BM)
-- ---------------------------------------------------------------------------

-- Example A's two agents share one physical status log, so the same alarm
-- line arrives under both agent_ids. event_uid includes agent_id, so those are
-- two distinct rows on purpose rather than one being dropped as a duplicate.
CREATE TABLE IF NOT EXISTS alarms (
  id             BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  event_uid      CHAR(32)     NOT NULL,
  agent_id       VARCHAR(64)  NOT NULL,
  line           VARCHAR(16)  NOT NULL,
  vision_key     VARCHAR(32)  NOT NULL,
  alarm_code     VARCHAR(32)      NULL,
  alarm_name     VARCHAR(128)     NULL,
  alarm_detail   VARCHAR(255)     NULL,
  raw_message    VARCHAR(500)     NULL,
  alarm_at       DATETIME(3)      NULL,
  alarm_at_raw   VARCHAR(64)      NULL,
  source_drive   VARCHAR(4)       NULL,
  source_file    VARCHAR(255)     NULL,
  raw_line       VARCHAR(1000)    NULL,
  received_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uq_alarm_uid (event_uid),
  KEY idx_alarm_agent_time (agent_id, alarm_at),
  CONSTRAINT fk_alarm_agent FOREIGN KEY (agent_id) REFERENCES agents(agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ---------------------------------------------------------------------------
-- Raw payload ledger (debugging only, retention-managed)
-- ---------------------------------------------------------------------------

-- Only defects, alarms, lot changes and parse problems land here - never the
-- OK units, which are the overwhelming majority of traffic and whose payload
-- carries nothing the counters and rollups don't already hold. This is what
-- you read when a vision type is being parsed wrong; it is not a source of
-- truth for any number on screen, and the purge job may delete from it freely.
CREATE TABLE IF NOT EXISTS raw_events (
  id          BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
  event_uid   CHAR(32)    NOT NULL,
  event_type  VARCHAR(32) NOT NULL,
  agent_id    VARCHAR(64) NOT NULL,
  payload     JSON        NOT NULL,
  received_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_raw_uid (event_uid),
  KEY idx_raw_agent_time (agent_id, received_at),
  KEY idx_raw_purge (received_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ---------------------------------------------------------------------------
-- Operator-tunable settings
-- ---------------------------------------------------------------------------

-- Threshold rows are seeded from vision-catalog.json's thresholdProfiles on
-- first startup and are the live value from then on; the catalog stays the
-- default, this table is the override. Everything else defaults below.
CREATE TABLE IF NOT EXISTS settings (
  setting_key   VARCHAR(64)  NOT NULL PRIMARY KEY,
  setting_value VARCHAR(255) NOT NULL,
  description   VARCHAR(255)     NULL,
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO settings (setting_key, setting_value, description) VALUES
  ('agent_offline_threshold_seconds', '10',  'placeholder'),
  ('agent_idle_threshold_seconds',    '300', 'placeholder'),
  ('line_downtime_planned_max_minutes','3',  'placeholder'),
  ('dashboard_poll_interval_seconds', '5',   'placeholder'),
  ('trend_bucket_minutes',            '30',  'placeholder'),
  ('daily_target_cells',              '100000', 'placeholder'),
  ('daily_target_cells_per_line',     '14286',  'placeholder'),
  ('image_local_root',                'D:\\VisionDashboardImages', 'placeholder'),
  ('image_prefetch_window_hours',     '24',  'placeholder'),
  ('image_retry_budget_hours',        '6',   'placeholder'),
  ('image_cache_retention_days',      '90',  'placeholder'),
  ('raw_event_retention_days',        '30',  'placeholder'),
  ('defect_retention_days',           '400', 'placeholder'),
  ('rollup_retention_days',           '400', 'placeholder');

-- Descriptions are what the Settings screen shows beside each box, so they are written
-- for whoever is standing there deciding whether to change a number - what it governs,
-- in what unit, and what happens if it moves. INSERT IGNORE above will not touch a row
-- that already exists, so they are set here instead and refresh on every schema run.
UPDATE settings SET description = CASE setting_key
  WHEN 'agent_offline_threshold_seconds' THEN
    'Seconds. An inspector that has not sent its heartbeat for this long turns grey (OFFLINE) on the grid. This is about the agent being reachable, not about production.'
  WHEN 'agent_idle_threshold_seconds' THEN
    'Seconds. An inspector that is alive but has not inspected anything for this long shows IDLE. Set it longer than the slowest normal gap between cells, or a healthy line will flicker.'
  WHEN 'line_downtime_planned_max_minutes' THEN
    'Minutes. When every inspector on a line is idle, the line is marked PD (planned) up to this long, and BM (breakdown) beyond it. Raise it if routine changeovers are being called breakdowns.'
  WHEN 'dashboard_poll_interval_seconds' THEN
    'Seconds between the browser re-fetching the screen. Lower feels more live and asks more of the server; 5 is comfortable for a wall display.'
  WHEN 'trend_bucket_minutes' THEN
    'Minutes per bar on the detail page trend. Smaller gives a finer chart with more bars; 30 over a whole lot gives about a bar every half hour.'
  WHEN 'daily_target_cells' THEN
    'Cells the whole process is expected to produce in a lot. Drives the Production tile and its progress bar.'
  WHEN 'daily_target_cells_per_line' THEN
    'Cells one line is expected to produce in a lot. Drives each bar in Yield. Not derived from the total - change both together if the line count changes.'
  WHEN 'image_local_root' THEN
    'Folder on this PC where defect images are cached after being pulled from the inspection PCs. Needs room for a few months of images.'
  WHEN 'image_prefetch_window_hours' THEN
    'Hours. Defects newer than this have their images pulled straight away so they open instantly; anything older is fetched only when somebody opens it.'
  WHEN 'image_retry_budget_hours' THEN
    'Hours to keep retrying an image the inspection PC would not hand over before giving up on it. Long enough to cover a PC being rebooted.'
  WHEN 'image_cache_retention_days' THEN
    'Days. Cached image files nobody has opened for this long are deleted from this PC. The inspection PCs keep their own copies for about three months.'
  WHEN 'raw_event_retention_days' THEN
    'Days to keep the raw payload log used for tracing parsing problems. Nothing on screen reads from it, so this can be short.'
  WHEN 'defect_retention_days' THEN
    'Days to keep defect records and their images before purging. This is the history behind the detail page.'
  WHEN 'rollup_retention_days' THEN
    'Days to keep the per-minute production totals the trend chart is drawn from.'
  ELSE description END
WHERE setting_key IN (
  'agent_offline_threshold_seconds','agent_idle_threshold_seconds',
  'line_downtime_planned_max_minutes','dashboard_poll_interval_seconds','trend_bucket_minutes',
  'daily_target_cells','daily_target_cells_per_line','image_local_root',
  'image_prefetch_window_hours','image_retry_budget_hours','image_cache_retention_days',
  'raw_event_retention_days','defect_retention_days','rollup_retention_days');


-- AI reports (the AI Report tab)
-- ---------------------------------------------------------------------------

-- Mirrors db/migrate-ai-reports.sql, which is what an already-deployed central PC runs.
-- Kept here as well so a fresh install gets the feature from one command.
--
-- Deliberately absent from SchemaCheck's REQUIRED list: this is the one part of the
-- dashboard that is allowed to be missing. A central PC given a new jar but not the
-- migration serves every existing screen normally and reports the AI tab as unavailable,
-- instead of refusing to start over a feature nobody was looking at.

-- facts_json is the exact evidence the model was handed, stored beside the prose it
-- produced - the difference between a summary you can audit and one you have to believe.
-- summary_text may be NULL while facts_json may not: a model that is down must cost us
-- the sentences, never the analysis.
CREATE TABLE IF NOT EXISTS ai_reports (
  id           BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
  window_start DATETIME    NOT NULL,
  window_end   DATETIME    NOT NULL,
  generated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  status       ENUM('PENDING','OK','LLM_FAILED','SKIPPED') NOT NULL DEFAULT 'PENDING',
  -- Decided in code from the findings, never by the model.
  severity     ENUM('NORMAL','WATCH','ALERT') NOT NULL DEFAULT 'NORMAL',
  headline     VARCHAR(400)     NULL,
  summary_text TEXT             NULL,
  facts_json   JSON        NOT NULL,
  model        VARCHAR(64)      NULL,
  latency_ms   INT              NULL,
  error        VARCHAR(500)     NULL,
  UNIQUE KEY uq_ai_report_window (window_start),
  KEY idx_ai_report_time (window_start)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Also inside facts_json, and stored again as rows so the tab's own table can sort and
-- filter them without parsing a JSON document per report.
--
-- expected_count is what this inspector's normal level says should have appeared in this
-- many inspections, and normal_low/normal_high are how far either side of it still counts
-- as normal. Together they are what makes a count honest: "2 where 0.6 were expected" and
-- "2 where 0.02 were expected" are the same count and the same percentage, and mean
-- entirely different things.
CREATE TABLE IF NOT EXISTS ai_report_findings (
  id             BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  report_id      BIGINT       NOT NULL,
  line           VARCHAR(16)  NOT NULL,
  vision_key     VARCHAR(32)  NOT NULL,
  judgement      VARCHAR(16)  NOT NULL,
  -- SPIKE (an open alert) / ELEVATED (outside the band, not raised) / INSUFFICIENT (too
  -- little produced to judge a rate) - see migrate-ai-episodes.sql.
  verdict        VARCHAR(16)  NOT NULL,
  inspected      BIGINT       NOT NULL DEFAULT 0,
  defect_count   BIGINT       NOT NULL DEFAULT 0,
  rate_pct       DOUBLE           NULL,
  baseline_pct   DOUBLE           NULL,
  expected_count DOUBLE           NULL,
  -- Written by the first version of this tab and read by nothing since: the band below
  -- replaced them. Kept so a server already carrying them needs no destructive migration,
  -- and so the two schema files describe the same table.
  wilson_low_pct DOUBLE           NULL,
  p_value        DOUBLE           NULL,
  streak         INT          NOT NULL DEFAULT 1,
  -- The normal band this count was judged against, in defects - what the screen prints as
  -- "usually 0-26; this window, 64".
  normal_low      DOUBLE          NULL,
  normal_high     DOUBLE          NULL,
  spread          DOUBLE          NULL,
  episode_started DATETIME        NULL,
  is_new          TINYINT(1)  NOT NULL DEFAULT 1,
  top_items      VARCHAR(400)     NULL,
  active_minutes INT          NOT NULL DEFAULT 0,
  KEY idx_finding_report (report_id),
  KEY idx_finding_slot (line, vision_key, judgement, report_id),
  CONSTRAINT fk_finding_report FOREIGN KEY (report_id) REFERENCES ai_reports(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- One row per alert, from the window it started in until it is released. Mirrors
-- db/migrate-ai-episodes.sql. See there for why the healthy baseline is frozen on the row.
CREATE TABLE IF NOT EXISTS ai_episodes (
  id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  line            VARCHAR(16)  NOT NULL,
  vision_key      VARCHAR(32)  NOT NULL,
  judgement       VARCHAR(16)  NOT NULL,
  -- The window this was first flagged in - what the screen shows as "open since ...".
  started_at      DATETIME     NOT NULL,
  -- The last window that still looked abnormal; how long the fault has been running.
  last_flagged_at DATETIME     NOT NULL,
  -- Set when the count has been back inside the normal band for long enough. A resolved
  -- episode is kept: it is the record of what happened, and of which windows to leave out
  -- of future measurements.
  resolved_at     DATETIME         NULL,
  -- Consecutive windows back inside the band; the release counter.
  clear_windows   INT          NOT NULL DEFAULT 0,
  -- Windows outside the band so far; the confirmation counter, compared against
  -- ai_report_confirm_windows. It needs no reset: a window back inside the band either
  -- deletes this row (unconfirmed) or starts counting clear_windows (confirmed).
  flag_windows    INT          NOT NULL DEFAULT 1,
  -- 0 while a single odd window is waiting to see whether the next one agrees. One window
  -- out of the band is how ordinary variation looks; two in a row is a fault. A row that
  -- never gets its second window is deleted rather than kept, so an unconfirmed blip never
  -- reaches the screen. A window far past the band (five times normal, ten extra defects)
  -- skips the wait - by then a second opinion costs half an hour and settles nothing.
  confirmed       TINYINT(1)   NOT NULL DEFAULT 0,
  -- The healthy inspector, as measured just before this started.
  frozen_rate     DOUBLE       NOT NULL,
  frozen_spread   DOUBLE       NOT NULL,
  -- Worst window seen so far, for the summary line.
  peak_count      BIGINT       NOT NULL DEFAULT 0,
  peak_expected   DOUBLE       NOT NULL DEFAULT 0,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  -- One open episode per slot and judgement. Two would double-count the same fault and
  -- race each other's frozen baseline.
  UNIQUE KEY uq_open_episode (line, vision_key, judgement, started_at),
  KEY idx_episode_open (line, vision_key, judgement, resolved_at),
  KEY idx_episode_time (started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
