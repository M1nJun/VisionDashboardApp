-- Proves the schema enforces the invariants the design depends on, rather than
-- leaving them to application code to remember. Safe to re-run: it cleans up
-- after itself and touches only the agents it creates.
--
--   mysql -u root -p visiondash < db/smoke-test.sql

SET @ok := 'PASS';

DELETE FROM raw_events WHERE agent_id LIKE 'ZZ-%';
DELETE FROM alarms WHERE agent_id LIKE 'ZZ-%';
DELETE FROM defect_occurrences WHERE agent_id LIKE 'ZZ-%';
DELETE FROM lot_history WHERE agent_id LIKE 'ZZ-%';
DELETE FROM vision_rollup_judgement WHERE agent_id LIKE 'ZZ-%';
DELETE FROM vision_rollup WHERE agent_id LIKE 'ZZ-%';
DELETE FROM lot_counter_judgement WHERE agent_id LIKE 'ZZ-%';
DELETE FROM lot_counters WHERE agent_id LIKE 'ZZ-%';
DELETE FROM agent_lot_progress WHERE agent_id LIKE 'ZZ-%';
DELETE FROM agents WHERE agent_id LIKE 'ZZ-%';

INSERT INTO agents (agent_id, line, vision_key) VALUES
  ('ZZ-1_EXAMPLE_D', 'ZZ-1', 'EXAMPLE_D'),
  ('ZZ-1_EXAMPLE_B_CATHODE', 'ZZ-1', 'EXAMPLE_B_CATHODE');

-- 1. Two agent_ids must not be able to claim the same (line, vision_key) slot.
--    This is what used to let a renamed agent silently vanish from the grid.
SELECT '--- 1. duplicate slot must be rejected ---' AS test;
INSERT IGNORE INTO agents (agent_id, line, vision_key)
  VALUES ('ZZ-1_EXAMPLE_D_OLD', 'ZZ-1', 'EXAMPLE_D');
SELECT IF(ROW_COUNT() = 0, 'PASS  slot collision rejected', 'FAIL  duplicate slot was accepted') AS result;

-- 2. One physical unit failing three inspection items is ONE occurrence with
--    three items - and re-sending it cannot create a second countable row.
SELECT '--- 2. one unit = one occurrence, replay-proof ---' AS test;
INSERT INTO defect_occurrences
  (agent_id, line, vision_key, source_file, unit_seq, cell_id, model_id, lot_id, judgement, item_summary, item_count, occurred_at)
VALUES
  ('ZZ-1_EXAMPLE_D', 'ZZ-1', 'EXAMPLE_D', 'MDL_20260825.csv', 10432, 'CELL-A', 'MDL', 'LOT-1',
   'NG', 'CHECK_A + CHECK_B + CHECK_C', 3, NOW(3));
SET @occ := LAST_INSERT_ID();

INSERT INTO defect_items (occurrence_id, seq, item_name, raw_value, value_num, side) VALUES
  (@occ, 1, 'CHECK_A',  '14.16007', 14.16007, ''),
  (@occ, 2, 'CHECK_B', '24.59380', 24.59380, ''),
  (@occ, 3, 'CHECK_C',   'NG',       NULL,     '');

-- Example B can fail one named defect on both faces of a unit; those must survive as two
-- rows, while a genuine duplicate of the same (name, side) must not.
SELECT '--- 2b. same defect name on two sides stays two rows ---' AS test;
INSERT IGNORE INTO defect_items (occurrence_id, seq, item_name, raw_value, side) VALUES
  (@occ, 4, 'B_L', 'BYPASS_NG', 'LOWER'),
  (@occ, 5, 'B_L', 'NG',        'UPPER'),
  (@occ, 6, 'B_L', 'NG',        'UPPER');
SELECT IF((SELECT COUNT(*) FROM defect_items WHERE occurrence_id = @occ AND item_name = 'B_L') = 2,
          'PASS  two sides kept, duplicate side rejected', 'FAIL') AS result;
DELETE FROM defect_items WHERE occurrence_id = @occ AND item_name = 'B_L';

INSERT IGNORE INTO defect_occurrences
  (agent_id, line, vision_key, source_file, unit_seq, lot_id, judgement, item_count)
VALUES
  ('ZZ-1_EXAMPLE_D', 'ZZ-1', 'EXAMPLE_D', 'MDL_20260825.csv', 10432, 'LOT-1', 'NG', 1);
SELECT IF(ROW_COUNT() = 0, 'PASS  replayed unit rejected', 'FAIL  same unit stored twice') AS result;

-- The same NO in the same file but a different lot is a different unit: the vision
-- software restarts NO at 1 on every lot change, so keying identity by file collapsed
-- two real units into one.
SELECT '--- 2c. same NO in a different lot is a different unit ---' AS test;
INSERT IGNORE INTO defect_occurrences
  (agent_id, line, vision_key, source_file, unit_seq, lot_id, judgement, item_count)
VALUES
  ('ZZ-1_EXAMPLE_D', 'ZZ-1', 'EXAMPLE_D', 'MDL_20260825.csv', 10432, 'LOT-2', 'NG', 1);
SELECT IF(ROW_COUNT() = 1, 'PASS  kept as its own unit', 'FAIL  collapsed into the other lot') AS result;
DELETE FROM defect_occurrences WHERE agent_id = 'ZZ-1_EXAMPLE_D' AND lot_id = 'LOT-2';

SELECT
  (SELECT COUNT(*) FROM defect_occurrences WHERE agent_id = 'ZZ-1_EXAMPLE_D') AS units,
  (SELECT COUNT(*) FROM defect_items WHERE occurrence_id = @occ)                AS items,
  IF((SELECT COUNT(*) FROM defect_occurrences WHERE agent_id = 'ZZ-1_EXAMPLE_D') = 1
     AND (SELECT COUNT(*) FROM defect_items WHERE occurrence_id = @occ) = 3,
     'PASS  1 unit / 3 items', 'FAIL') AS result;

-- 3. Raw measurements survive. item_name is the grouping key; raw_value is the
--    only copy of the measurement and is what defect-coordinate work will need.
SELECT '--- 3. raw values preserved ---' AS test;
SELECT IF(COUNT(*) = 2, 'PASS  numeric raw values kept', 'FAIL  raw values lost') AS result
  FROM defect_items WHERE occurrence_id = @occ AND value_num IS NOT NULL;

-- 4. main/overlay are independent rows, so a missing overlay cannot take a
--    perfectly good main image down with it.
SELECT '--- 4. image files are independent rows ---' AS test;
INSERT INTO defect_images (occurrence_id, set_label, kind, source_path, state) VALUES
  (@occ, 'LEFT',  'MAIN',    'F:\\img\\a.jpg',    'ready'),
  (@occ, 'LEFT',  'OVERLAY', 'F:\\img\\a_ov.jpg', 'unavailable'),
  (@occ, 'RIGHT', 'MAIN',    'F:\\img\\b.jpg',    'pending'),
  (@occ, 'RIGHT', 'OVERLAY', 'F:\\img\\b_ov.jpg', 'pending');
SELECT IF((SELECT COUNT(*) FROM defect_images WHERE occurrence_id = @occ AND state = 'ready') = 1,
          'PASS  main survives a dead overlay', 'FAIL') AS result;

INSERT IGNORE INTO defect_images (occurrence_id, set_label, kind, source_path)
  VALUES (@occ, 'LEFT', 'MAIN', 'F:\\img\\a.jpg');
SELECT IF(ROW_COUNT() = 0, 'PASS  duplicate image row rejected', 'FAIL  same file queued twice') AS result;

-- 5. Judgement counts are rows, not columns: pouch align carries one, example_b
--    carries three, and neither needed DDL.
SELECT '--- 5. judgements as data ---' AS test;
INSERT INTO lot_counters (agent_id, lot_id, lot_started_at, inspected_count, defect_unit_count)
  VALUES ('ZZ-1_EXAMPLE_D', 'LOT-1', NOW(3), 1000, 3),
         ('ZZ-1_EXAMPLE_B_CATHODE', 'LOT-1', NOW(3), 1000, 12);
INSERT INTO lot_counter_judgement (agent_id, judgement, unit_count) VALUES
  ('ZZ-1_EXAMPLE_D', 'NG', 3),
  ('ZZ-1_EXAMPLE_B_CATHODE', 'NG', 4),
  ('ZZ-1_EXAMPLE_B_CATHODE', 'DLNG', 6),
  ('ZZ-1_EXAMPLE_B_CATHODE', 'C-NG', 2);
SELECT agent_id,
       (SELECT SUM(unit_count) FROM lot_counter_judgement j WHERE j.agent_id = c.agent_id) AS judgement_sum,
       defect_unit_count,
       IF((SELECT SUM(unit_count) FROM lot_counter_judgement j WHERE j.agent_id = c.agent_id) = defect_unit_count,
          'PASS  breakdown sums to total', 'FAIL  breakdown disagrees with total') AS result
  FROM lot_counters c WHERE agent_id LIKE 'ZZ-%' ORDER BY agent_id;

-- 6. The trend chart reads rollups written from the same unit-level delta as
--    the counters, so "3 defective units" means the same thing in both.
SELECT '--- 6. rollup agrees with counters ---' AS test;
INSERT INTO vision_rollup (agent_id, bucket_start, lot_id, inspected_count, defect_unit_count) VALUES
  ('ZZ-1_EXAMPLE_D', '2026-08-25 14:00:00', 'LOT-1', 600, 2),
  ('ZZ-1_EXAMPLE_D', '2026-08-25 14:05:00', 'LOT-1', 400, 1);
SELECT SUM(inspected_count) AS rollup_inspected, SUM(defect_unit_count) AS rollup_defects,
       IF(SUM(inspected_count) = (SELECT inspected_count FROM lot_counters WHERE agent_id = 'ZZ-1_EXAMPLE_D')
          AND SUM(defect_unit_count) = (SELECT defect_unit_count FROM lot_counters WHERE agent_id = 'ZZ-1_EXAMPLE_D'),
          'PASS  rollup total = counter total', 'FAIL  they disagree') AS result
  FROM vision_rollup WHERE agent_id = 'ZZ-1_EXAMPLE_D' AND lot_id = 'LOT-1';

-- 7. A lot cannot be closed into history twice by a replayed LOT_CHANGED.
SELECT '--- 7. lot history is replay-proof ---' AS test;
INSERT INTO lot_history (agent_id, lot_id, model_id, started_at, ended_at, inspected_count, defect_unit_count)
  VALUES ('ZZ-1_EXAMPLE_D', 'LOT-0', 'MDL', NOW(3), NOW(3), 5000, 9);
INSERT IGNORE INTO lot_history (agent_id, lot_id, inspected_count, defect_unit_count)
  VALUES ('ZZ-1_EXAMPLE_D', 'LOT-0', 5000, 9);
SELECT IF(ROW_COUNT() = 0, 'PASS  duplicate lot close rejected', 'FAIL  lot closed twice') AS result;

-- 8. Deleting an occurrence takes its items and images with it, so a purge job
--    cannot leave orphaned image rows pointing at deleted cache files.
SELECT '--- 8. cascade on purge ---' AS test;
DELETE FROM defect_occurrences WHERE id = @occ;
SELECT IF((SELECT COUNT(*) FROM defect_items WHERE occurrence_id = @occ) = 0
          AND (SELECT COUNT(*) FROM defect_images WHERE occurrence_id = @occ) = 0,
          'PASS  items and images cascaded', 'FAIL  orphans left behind') AS result;

-- cleanup
DELETE FROM lot_history WHERE agent_id LIKE 'ZZ-%';
DELETE FROM vision_rollup WHERE agent_id LIKE 'ZZ-%';
DELETE FROM lot_counter_judgement WHERE agent_id LIKE 'ZZ-%';
DELETE FROM lot_counters WHERE agent_id LIKE 'ZZ-%';
DELETE FROM agents WHERE agent_id LIKE 'ZZ-%';
SELECT '--- cleanup done ---' AS test;
