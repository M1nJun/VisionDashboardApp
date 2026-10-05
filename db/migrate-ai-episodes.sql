-- Migration: alerts become episodes with a start time, and "usual" gets a measured width.
--
-- What changed and why
--
-- The first version asked "is this window's count unlikely under that inspector's own
-- recent rate", which is the right question with the wrong idea of chance. Example B DLNG
-- swings about three times as far between half-hour windows as pure chance allows, so the
-- test called ordinary variation significant: 166 of 221 windows came back ALERT, 153 of
-- them on the strength of one "spike" somewhere among 49 inspectors. An alert that fires
-- every half hour is not an alert.
--
-- So each inspector now carries two measured numbers rather than one:
--   level  - how many defects this much production normally yields   (last 24 hours)
--   spread - how far that count normally wanders window to window    (last 7 days)
-- and the normal band is level ± 3 x sqrt(spread x level). Measured on five days of real
-- production, alerts land at about three per twelve-hour shift, which is what the floor
-- asked for.
--
-- The second change is this table. An alert is no longer a property of one window - it is
-- an episode with a beginning, so the screen can say "open since 09-11 20:30" instead of
-- "8 windows in a row", which asked the reader to know how long a report window is.
--
-- Safe to re-run. Apply with the server stopped:
--   Stop-ScheduledTask -TaskName VisionDashboardServer
--   Get-Content db\migrate-ai-episodes.sql -Raw | & $mysql -u root -p visiondash
--   Start-ScheduledTask -TaskName VisionDashboardServer


-- ---------------------------------------------------------------------------
-- One row per alert, from the window it started in until it is released
-- ---------------------------------------------------------------------------

-- frozen_rate and frozen_spread are the whole reason this table exists rather than a
-- recomputation each window. "Normal" is measured from recent history, so an inspector
-- that stays broken teaches the baseline that broken is normal: C-1's C-NG ran at 0.2
-- expected defects per window before it failed, and 24 hours later the rolling baseline
-- expected 121 - the alert would have switched itself off while the fault was still
-- there. Freezing both at the values from just before the episode keeps the comparison
-- against the healthy inspector for as long as the fault lasts.
--
-- Windows inside an episode are also left out when that slot's level and spread are
-- measured later on, so a bad week does not widen the band it will be judged against
-- next time. Without that, C-1's C-NG measured a spread of 50x - its own failure,
-- recorded as its normal wobble, leaving it nearly deaf to the next one.
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

-- For a server that already ran an earlier copy of this file, when the confirmation
-- counter was still hardcoded at two windows.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = 'ai_episodes'
                   AND column_name = 'flag_windows') > 0,
  'DO 0',
  'ALTER TABLE ai_episodes ADD COLUMN flag_windows INT NOT NULL DEFAULT 1 AFTER clear_windows');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;


-- ---------------------------------------------------------------------------
-- Findings carry the band, not a p-value
-- ---------------------------------------------------------------------------

-- The screen now says "at this volume, usually 0-26; this window, 64". A p-value and a Wilson
-- bound said the same thing to anyone who already knew what they were; these say it to
-- everyone. Added rather than swapped in place so an older jar keeps working if this
-- migration is applied before the new one is copied across.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = 'ai_report_findings'
                   AND column_name = 'normal_low') > 0,
  'DO 0',
  'ALTER TABLE ai_report_findings
     ADD COLUMN normal_low      DOUBLE NULL,
     ADD COLUMN normal_high     DOUBLE NULL,
     ADD COLUMN spread          DOUBLE NULL,
     ADD COLUMN episode_started DATETIME NULL,
     ADD COLUMN is_new          TINYINT(1) NOT NULL DEFAULT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;


-- ---------------------------------------------------------------------------
-- Settings
-- ---------------------------------------------------------------------------
--
-- Seeded by the server on startup (SettingsService), so a default and the sentence
-- explaining it live in one place. Nothing to do here.
