-- Migration: the AI Report tab - a ten-minute narrated summary of the floor.
--
-- Two tables and a handful of settings. Nothing the existing dashboard reads is touched,
-- and nothing here is on the ingest path: a central PC that has not run this migration
-- still starts and still serves the grid, it just reports the feature as unavailable.
-- That is deliberate - SchemaCheck does NOT list these tables, so an operator who copies
-- a new jar across without running the migration gets a working dashboard and one dark
-- tab, rather than a server that refuses to boot.
--
-- Safe to re-run. Apply with the server stopped:
--   Stop-ScheduledTask -TaskName VisionDashboardServer
--   Get-Content db\migrate-ai-reports.sql -Raw | & $mysql -u root -p visiondash
--   Start-ScheduledTask -TaskName VisionDashboardServer


-- ---------------------------------------------------------------------------
-- One row per ten-minute window
-- ---------------------------------------------------------------------------

-- facts_json is the exact evidence the model was handed, stored verbatim beside the
-- prose it produced. This is the difference between a summary you can audit and one you
-- have to believe: when somebody on the floor disputes a sentence, the numbers behind
-- that sentence are on the same row, and they were computed in SQL, not by the model.
--
-- summary_text is allowed to be NULL while facts_json is not. An LLM that is down, slow
-- or talking nonsense must not cost us the window's analysis - the tab falls back to
-- rendering the findings directly, which is the deterministic half and the half that
-- actually carries the numbers.
--
-- UNIQUE(window_start) is the dedup key. A scheduler that fires twice across a restart,
-- or an operator re-running a window by hand, must not leave two reports for 10:20 that
-- disagree with each other.
CREATE TABLE IF NOT EXISTS ai_reports (
  id           BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
  window_start DATETIME    NOT NULL,
  window_end   DATETIME    NOT NULL,
  generated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  -- PENDING     - the figures are stored and the model is still writing. On a card
  --               this size that lasts tens of seconds, which is long enough for
  --               somebody to open the tab during it, so it is a state of its own
  --               rather than something indistinguishable from a finished report.
  -- OK          - the model answered and summary_text holds its prose.
  -- LLM_FAILED  - the facts are good, the model was unreachable/slow/empty. Renderable.
  -- SKIPPED     - nothing ran in the window worth reporting on (whole floor stopped).
  status       ENUM('PENDING','OK','LLM_FAILED','SKIPPED') NOT NULL DEFAULT 'PENDING',
  -- Decided from the findings in code, never by the model. The tab colours by this, and
  -- a colour that came out of a language model is not a colour anyone should trust.
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


-- ---------------------------------------------------------------------------
-- The evidence, as rows
-- ---------------------------------------------------------------------------

-- Also inside facts_json, and stored again here on purpose. Two things need these as
-- rows rather than as JSON:
--
--   1. The streak. "Has this same inspector and judgement been elevated in the previous
--      windows too" is the single strongest signal for telling a real drift from one
--      unlucky ten minutes, and as rows it is one indexed query instead of parsing every
--      recent report's JSON.
--   2. The tab renders findings as a table with its own filters and sorting, which JSON
--      extraction in MySQL would make needlessly slow and awkward.
--
-- expected_count is what the baseline says SHOULD have appeared in this many inspections
-- (n x baseline rate). It is stored because it is the number that makes a rate honest:
-- "2 where 0.6 were expected" and "2 where 0.02 were expected" are the same 2 and the
-- same percentage, and they mean completely different things.
--
-- p_value and wilson_low_pct are the small-sample guard rails. A window with almost no
-- production produces a huge apparent rate and a wilson_low_pct barely above zero, which
-- is exactly how the report knows not to call it a spike.
CREATE TABLE IF NOT EXISTS ai_report_findings (
  id             BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  report_id      BIGINT       NOT NULL,
  line           VARCHAR(16)  NOT NULL,
  vision_key     VARCHAR(32)  NOT NULL,
  judgement      VARCHAR(16)  NOT NULL,
  -- SPIKE        - significantly more than the baseline predicts, on enough production.
  -- ELEVATED     - above the baseline, but only at the weaker confidence level.
  -- THRESHOLD    - over the operator's own warn/crit line, reported regardless of
  --                statistics so the report can never contradict the grid's colour.
  -- INSUFFICIENT - too little produced to say anything about a rate. Counts only.
  verdict        VARCHAR(16)  NOT NULL,
  inspected      BIGINT       NOT NULL DEFAULT 0,
  defect_count   BIGINT       NOT NULL DEFAULT 0,
  rate_pct       DOUBLE           NULL,
  baseline_pct   DOUBLE           NULL,
  expected_count DOUBLE           NULL,
  wilson_low_pct DOUBLE           NULL,
  p_value        DOUBLE           NULL,
  -- 1 for a first sighting, N when the same slot and judgement has been flagged in the
  -- N most recent consecutive reports.
  streak         INT          NOT NULL DEFAULT 1,
  -- '+'-joined defect item names, most frequent first, e.g. "SEPA(LOWER) x8 + PIN x2".
  top_items      VARCHAR(400)     NULL,
  active_minutes INT          NOT NULL DEFAULT 0,
  KEY idx_finding_report (report_id),
  -- The streak lookup: newest findings for one slot and judgement.
  KEY idx_finding_slot (line, vision_key, judgement, report_id),
  CONSTRAINT fk_finding_report FOREIGN KEY (report_id) REFERENCES ai_reports(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ---------------------------------------------------------------------------
-- Settings
-- ---------------------------------------------------------------------------
--
-- Not seeded here. The server writes its own AI settings rows on startup
-- (SettingsService), the same way it already seeds the threshold rows from the catalog,
-- so a default and its explanation are written down in exactly one place and a fresh
-- install and a migrated one cannot end up with different ones.
--
-- Nothing to do: start the server and the rows appear on the Settings screen under
-- "AI report".


-- ---------------------------------------------------------------------------
-- Two indexes the report needs and the dashboard never did
-- ---------------------------------------------------------------------------

-- Every existing query into these two tables starts from one agent, so both of their
-- indexes lead with agent_id and neither can serve "everything that happened between
-- 10:20 and 10:30" - which is the only question the report ever asks. Without these,
-- that window is a full scan of both tables, every ten minutes, forever.
--
-- Cheap to carry: defect_occurrences holds only defects, never the OK units that are
-- almost all of production, and alarms is smaller still. Added INPLACE/LOCK=NONE so
-- ingest keeps running while they build.
--
-- MySQL has no CREATE INDEX IF NOT EXISTS and re-running this file has to stay safe,
-- so each one is added only when information_schema says it is absent.
SET @ddl := IF(
  (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'defect_occurrences'
       AND index_name = 'idx_occ_occurred') > 0,
  'DO 0',
  'ALTER TABLE defect_occurrences ADD KEY idx_occ_occurred (occurred_at), ALGORITHM=INPLACE, LOCK=NONE');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := IF(
  (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'alarms'
       AND index_name = 'idx_alarm_time') > 0,
  'DO 0',
  'ALTER TABLE alarms ADD KEY idx_alarm_time (alarm_at), ALGORITHM=INPLACE, LOCK=NONE');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;


-- ---------------------------------------------------------------------------
-- PENDING, for a report whose figures are stored and whose prose is still coming
-- ---------------------------------------------------------------------------

-- Re-run safe, and needed on any central PC that ran an earlier copy of this file. The
-- facts are written before the model is called, so between the two there is a real
-- interval - tens of seconds on this hardware - in which a report exists and its summary
-- does not. Without a state for it, the tab could not tell that apart from a model that
-- had failed, and told an operator the summary had failed while it was being written.
ALTER TABLE ai_reports
  MODIFY COLUMN status ENUM('PENDING','OK','LLM_FAILED','SKIPPED') NOT NULL DEFAULT 'PENDING';
