-- Migration: replay filtering and unit identity move from the CSV file to the LOT.
--
-- Why: the vision software numbers rows against the lot. NO restarts at 1 on every lot
-- change - including in the middle of a file - and carries on across a file rollover for
-- as long as the lot holds. The original design keyed both the replay high-water mark and
-- the unit's unique identity by file, which broke in two ways:
--
--   1. After the first lot change of the day, every new row had a NO below the file's
--      high-water mark, so the whole fleet was silently discarded as "already seen".
--      Agents kept reporting healthy and the dashboard sat at zero.
--   2. Two genuinely different units in one file could share a NO across a lot boundary,
--      and the second was quietly dropped by the unique key.
--
-- Safe to re-run. Apply with the server stopped:
--   Stop-ScheduledTask -TaskName VisionDashboardServer
--   Get-Content db\migrate-lot-scoped-progress.sql -Raw | & $mysql -u root -p visiondash
--   Start-ScheduledTask -TaskName VisionDashboardServer

-- ---------------------------------------------------------------------------
-- 1. Progress is per lot, not per file.
-- ---------------------------------------------------------------------------

-- Nothing in the old table is worth keeping: every row in it is a high-water mark that
-- was measured against the wrong thing, and the marks are exactly what stopped ingest.
DROP TABLE IF EXISTS agent_file_progress;

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
-- 2. A unit is identified by (agent, lot, NO).
-- ---------------------------------------------------------------------------

-- Empty rather than NULL: lot_id joins the unique key below, and MySQL lets a UNIQUE
-- index hold any number of NULLs, so a unit without a lot would slip past dedup entirely.
UPDATE defect_occurrences SET lot_id = '' WHERE lot_id IS NULL;
ALTER TABLE defect_occurrences MODIFY lot_id VARCHAR(64) NOT NULL DEFAULT '';

-- Any duplicate under the new key is a row the old key let through twice. Keep the
-- earliest of each - it is the one whose images and items were actually fetched.
DELETE o FROM defect_occurrences o
JOIN (
    SELECT agent_id, lot_id, unit_seq, MIN(id) AS keep_id
    FROM defect_occurrences
    GROUP BY agent_id, lot_id, unit_seq
    HAVING COUNT(*) > 1
) dupes
  ON  o.agent_id = dupes.agent_id
  AND o.lot_id   = dupes.lot_id
  AND o.unit_seq = dupes.unit_seq
  AND o.id      <> dupes.keep_id;

-- MySQL has no DROP INDEX IF EXISTS, so this is conditional the long way round.
SET @had_old_key := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'defect_occurrences'
      AND index_name = 'uq_occurrence_unit');
SET @sql := IF(@had_old_key > 0,
    'ALTER TABLE defect_occurrences DROP INDEX uq_occurrence_unit',
    'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

ALTER TABLE defect_occurrences
  ADD UNIQUE KEY uq_occurrence_unit (agent_id, lot_id, unit_seq);

-- ---------------------------------------------------------------------------
-- 3. Report
-- ---------------------------------------------------------------------------

SELECT 'agent_lot_progress rows' AS what, COUNT(*) AS value FROM agent_lot_progress
UNION ALL
SELECT 'defect_occurrences rows', COUNT(*) FROM defect_occurrences
UNION ALL
SELECT 'distinct (agent, lot, NO)', COUNT(*) FROM (
    SELECT 1 FROM defect_occurrences GROUP BY agent_id, lot_id, unit_seq) t;
