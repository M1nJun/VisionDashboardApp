package com.visiondash.server.ingest;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.visiondash.server.catalog.VisionCatalog;
import com.visiondash.server.catalog.VisionType;
import com.visiondash.server.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a batch of agent events into counters, rollups and defect rows.
 *
 * Every number the dashboard shows is derived here and nowhere else, from one
 * source: a UNIT_INSPECTED event is one physical unit. Counters and the
 * time-series rollup are written from the same delta in the same transaction, so
 * the grid and the trend chart cannot disagree about how many units ran or how
 * many failed.
 */
@Service
public class IngestService {
    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    /** Fixed, not a setting: changing it would invalidate every rollup already stored.
     *  The UI re-aggregates upward from this into whatever bucket it wants to draw. */
    // One minute, because the grid can be asked for "the last 5 minutes" and a coarser
    // bucket would answer with somewhere between 5 and 10 minutes of production depending
    // on where the clock happened to be. At 47 inspectors this is around 68k rows a day,
    // which the purge job already covers.
    private static final int ROLLUP_BUCKET_MINUTES = 1;

    /** How often a continuing stall is repeated in the log, in batches. */
    private static final int STALL_REPORT_EVERY = 500;

    /** Per agent and lot, how long it has been offering sequences that are all refused.
     *  In memory on purpose: a server restart simply starts the observation again. */
    private final Map<String, Stall> stalls = new java.util.concurrent.ConcurrentHashMap<>();

    private static final class Stall {
        private long highestRefusedSeq = -1;
        private int batches;
    }
    private static final String UNKNOWN_JUDGEMENT = "UNKNOWN";
    private static final int ITEM_SUMMARY_MAX = 512;

    private final JdbcTemplate jdbc;
    private final VisionCatalog catalog;
    private final SettingsService settings;
    private final ObjectMapper mapper;

    public IngestService(JdbcTemplate jdbc, VisionCatalog catalog, SettingsService settings, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.settings = settings;
        this.mapper = mapper;
    }

    @Transactional
    public EventBatch.Result ingest(EventBatch batch) {
        VisionType visionType = catalog.require(batch.visionKey());
        String agentId = VisionCatalog.agentId(batch.line(), batch.visionKey());

        upsertAgent(agentId, batch);

        Accumulator acc = new Accumulator(agentId);
        // Keyed by lot: the vision software numbers rows against the lot, restarting at 1
        // on every lot change - including in the middle of a file - and continuing across
        // a file rollover while the lot holds.
        Map<String, Long> highWater = new HashMap<>();
        Map<String, Integer> replayedByLot = new LinkedHashMap<>();
        // Lots this agent has already finished. An agent that lost its saved position and
        // re-read the day from the top would otherwise announce this morning's first lot
        // again, and ensureLot would close the lot actually running and zero its counters.
        Map<String, Boolean> closedLots = new HashMap<>();
        List<EventBatch.Result.Rejected> rejected = new ArrayList<>();
        int accepted = 0;
        int replayed = 0;
        // What was refused, so a batch that accepts nothing can say whether this is a
        // normal re-send or an agent that will never be accepted again. "accepted=0
        // replayed=n" on its own cannot tell them apart, and that ambiguity cost a day.
        String stallLot = null;
        long stallLowestSeq = Long.MAX_VALUE;
        long stallHighestSeq = -1;

        List<AgentEvent> events = batch.eventsOrEmpty();
        for (int i = 0; i < events.size(); i++) {
            AgentEvent event = events.get(i);
            try {
                switch (event) {
                    case AgentEvent.UnitInspected unit -> {
                        if (isBlank(unit.sourceFile()) || unit.unitSeq() == null) {
                            rejected.add(new EventBatch.Result.Rejected(i, "sourceFile and unitSeq are required"));
                            continue;
                        }
                        String lotKey = lotKey(unit.lotId());
                        long last = highWater.computeIfAbsent(lotKey, key -> loadHighWater(agentId, key));
                        if (unit.unitSeq() <= last) {
                            stallLot = lotKey;
                            stallLowestSeq = Math.min(stallLowestSeq, unit.unitSeq());
                            stallHighestSeq = Math.max(stallHighestSeq, unit.unitSeq());
                            replayed++;
                            replayedByLot.merge(lotKey, 1, Integer::sum);
                            continue;
                        }
                        applyUnit(agentId, batch, visionType, unit, acc, closedLots);
                        highWater.put(lotKey, unit.unitSeq());
                        accepted++;
                    }
                    case AgentEvent.LotChanged lot -> {
                        if (isClosedLot(agentId, lotKey(lot.newLotId()), closedLots)) {
                            log.warn("{}: ignoring a move back to closed lot {}", agentId, lot.newLotId());
                            replayed++;
                            continue;
                        }
                        // Deltas gathered so far belong to the outgoing lot, so they have to
                        // land before the counters are reset and copied into history.
                        flush(acc);
                        ensureLot(agentId, lot.newLotId(), at(lot.occurredAt()), closedLots);
                        recordRaw(event, agentId);
                        accepted++;
                    }
                    case AgentEvent.VisionAlarm alarm -> {
                        if (applyAlarm(agentId, batch, alarm)) {
                            recordRaw(event, agentId);
                            accepted++;
                        } else {
                            replayed++;
                        }
                    }
                }
            } catch (IllegalArgumentException e) {
                rejected.add(new EventBatch.Result.Rejected(i, e.getMessage()));
            }
        }

        flush(acc);
        highWater.forEach((lot, seq) -> saveHighWater(agentId, lot, seq));
        replayedByLot.forEach((lot, count) -> bumpReplayed(agentId, lot, count));

        // Accepting nothing at all is either a harmless re-send or an agent that has
        // stopped counting; only repetition tells them apart, so the judgement is made
        // across batches rather than inside one.
        if (accepted == 0 && replayed > 0 && stallLot != null) {
            noteStall(agentId, stallLot, stallLowestSeq, stallHighestSeq,
                    highWater.getOrDefault(stallLot, -1L));
        } else if (accepted > 0 && stallLot != null) {
            // It got past whatever it was re-sending: nothing to watch any more.
            stalls.remove(agentId + "|" + stallLot);
        }

        return new EventBatch.Result(accepted, replayed, rejected);
    }

    // ---- units -------------------------------------------------------------

    private void applyUnit(String agentId, EventBatch batch, VisionType type,
                           AgentEvent.UnitInspected unit, Accumulator acc,
                           Map<String, Boolean> closedLots) {
        LocalDateTime at = at(unit.occurredAt());

        // Order matters: deltas gathered for the outgoing lot must reach lot_counters
        // before ensureLot reads that row to copy it into lot_history, or the closed
        // lot's totals come out short by however much this batch had accumulated.
        acc.retarget(unit.lotId(), this::flush);
        ensureLot(agentId, unit.lotId(), at, closedLots);

        String judgement = resolveJudgement(agentId, type, unit);
        boolean isDefect = !type.isOk(judgement) && !UNKNOWN_JUDGEMENT.equals(judgement);

        acc.inspected++;
        acc.addRollup(bucket(at), unit.lotId(), isDefect, judgement);

        if (UNKNOWN_JUDGEMENT.equals(judgement)) {
            acc.unknown++;
            return;
        }
        if (!isDefect) {
            return;
        }

        acc.defects++;
        acc.judgements.merge(judgement, 1L, Long::sum);
        insertOccurrence(agentId, batch, type, unit, judgement, at);
        recordRaw(unit, agentId);
    }

    /** A judgement the catalog does not define is counted as production but never as a
     *  defect - dropping the row instead would quietly shrink the denominator. */
    private String resolveJudgement(String agentId, VisionType type, AgentEvent.UnitInspected unit) {
        String raw = unit.judgement() == null ? "" : unit.judgement().trim();
        if (type.isOk(raw)) {
            return type.judgements().ok();
        }
        Optional<VisionType.Judgements.Defect> defect = type.defectFor(raw);
        if (defect.isPresent()) {
            return defect.get().code();
        }
        if (!UNKNOWN_JUDGEMENT.equalsIgnoreCase(raw)) {
            log.warn("agent {} sent judgement '{}' which {} does not define; counting as UNKNOWN",
                    agentId, raw, type.key());
        }
        return UNKNOWN_JUDGEMENT;
    }

    private void insertOccurrence(String agentId, EventBatch batch, VisionType type,
                                  AgentEvent.UnitInspected unit, String judgement, LocalDateTime at) {
        List<AgentEvent.UnitInspected.Item> items = unit.itemsOrEmpty();
        Set<String> names = new LinkedHashSet<>();
        for (AgentEvent.UnitInspected.Item item : items) {
            if (!isBlank(item.name())) {
                names.add(item.name().trim());
            }
        }
        if (names.isEmpty()) {
            names.add("UNSPECIFIED");
        }
        String summary = truncate(String.join(" + ", names.stream().sorted().toList()), ITEM_SUMMARY_MAX);

        KeyHolder keys = new GeneratedKeyHolder();
        int inserted = jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT IGNORE INTO defect_occurrences
                      (agent_id, line, vision_key, source_file, unit_seq, cell_id, model_id, lot_id,
                       judgement, item_summary, item_count, occurred_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, agentId);
            ps.setString(2, batch.line());
            ps.setString(3, type.key());
            ps.setString(4, unit.sourceFile());
            ps.setLong(5, unit.unitSeq());
            ps.setString(6, blankToNull(unit.cellId()));
            ps.setString(7, blankToNull(unit.modelId()));
            ps.setString(8, lotKey(unit.lotId()));
            ps.setString(9, judgement);
            ps.setString(10, summary);
            ps.setInt(11, names.size());
            ps.setTimestamp(12, Timestamp.valueOf(at));
            return ps;
        }, keys);

        // The high-water mark already filtered replays; this only fires if a CSV was
        // rewritten in place, in which case the first version stands.
        if (inserted == 0 || keys.getKey() == null || keys.getKey().longValue() == 0L) {
            log.debug("occurrence {}#{} already stored, skipping items/images", unit.sourceFile(), unit.unitSeq());
            return;
        }
        long occurrenceId = keys.getKey().longValue();

        insertItems(occurrenceId, items, names);
        insertImages(occurrenceId, type, unit);
    }

    private void insertItems(long occurrenceId, List<AgentEvent.UnitInspected.Item> items, Set<String> names) {
        if (items.isEmpty()) {
            jdbc.update("INSERT IGNORE INTO defect_items (occurrence_id, seq, item_name) VALUES (?, 1, 'UNSPECIFIED')",
                    occurrenceId);
            return;
        }
        List<Object[]> rows = new ArrayList<>();
        int seq = 0;
        // (name, side) rather than name alone: Example B reports the same defect name on
        // both LOWER and UPPER of one unit, and those are two distinct failure locations.
        Set<String> seen = new LinkedHashSet<>();
        for (AgentEvent.UnitInspected.Item item : items) {
            String name = isBlank(item.name()) ? "UNSPECIFIED" : item.name().trim();
            String side = isBlank(item.side()) ? "" : item.side().trim();
            if (!seen.add(name + "|" + side)) {
                continue;
            }
            rows.add(new Object[]{occurrenceId, ++seq, name, blankToNull(item.rawValue()),
                    parseNumber(item.rawValue()), side});
        }
        jdbc.batchUpdate("""
                INSERT IGNORE INTO defect_items (occurrence_id, seq, item_name, raw_value, value_num, side)
                VALUES (?,?,?,?,?,?)
                """, rows);
    }

    /** One row per image FILE, and blank paths are dropped here rather than queued -
     *  a missing overlay used to fail the whole pair and take a good main image with it. */
    private void insertImages(long occurrenceId, VisionType type, AgentEvent.UnitInspected unit) {
        List<AgentEvent.UnitInspected.Image> images = unit.imagesOrEmpty();
        if (images.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusHours(settings.getInt("image_retry_budget_hours", 6));
        Set<String> validSets = type.imageSetLabels();

        List<Object[]> rows = new ArrayList<>();
        for (AgentEvent.UnitInspected.Image image : images) {
            if (isBlank(image.path())) {
                continue;
            }
            String set = image.set() == null ? "" : image.set().trim();
            if (!validSets.contains(set)) {
                log.warn("{} sent image set '{}' which is not one of {}", type.key(), set, validSets);
                continue;
            }
            String kind = image.kind() == null ? "" : image.kind().trim().toUpperCase();
            if (!kind.equals("MAIN") && !kind.equals("OVERLAY")) {
                log.warn("{} sent image kind '{}', expected MAIN or OVERLAY", type.key(), kind);
                continue;
            }
            rows.add(new Object[]{occurrenceId, set, kind, image.path().trim(),
                    Timestamp.valueOf(now), Timestamp.valueOf(expiresAt)});
        }
        if (!rows.isEmpty()) {
            jdbc.batchUpdate("""
                    INSERT IGNORE INTO defect_images
                      (occurrence_id, set_label, kind, source_path, next_attempt_at, expires_at)
                    VALUES (?,?,?,?,?,?)
                    """, rows);
        }
    }

    // ---- lots --------------------------------------------------------------

    /**
     * Makes the counters row reflect this lot, closing the previous one into history
     * first if it differs.
     *
     * Called for LOT_CHANGED *and* for every unit, so a lot change that the agent
     * failed to announce still resets the counters instead of silently accumulating
     * the new lot on top of the old one's totals.
     */
    /**
     * Whether this agent has already run this lot to completion.
     *
     * Answered once per lot per batch: the row is only written by closeLot and removed by
     * reopenLot, both of which update the memo themselves.
     */
    private boolean isClosedLot(String agentId, String lotId, Map<String, Boolean> memo) {
        if (isBlank(lotId)) {
            return false;
        }
        return memo.computeIfAbsent(lotId, id -> !jdbc.queryForList(
                "SELECT 1 FROM lot_history WHERE agent_id = ? AND lot_id = ? LIMIT 1",
                agentId, id).isEmpty());
    }

    /**
     * Takes a lot back out of history and restores it as the live one.
     *
     * A lot only gets here when new units for it arrive - the sequence filter above has
     * already established they are past everything this lot has ever recorded, so the
     * lot was closed while it was still running. That happens when a stray lot change
     * lands out of order, and refusing those units instead left the agent counting
     * nothing at all for the rest of the shift with no way to recover.
     *
     * The counts come back with it, so the close does not cost the units it had already
     * gathered.
     */
    private boolean reopenLot(String agentId, String lotId, Map<String, Boolean> closedLots) {
        record Closed(long id, LocalDateTime startedAt, long inspected, long defects) {
        }
        List<Closed> rows = jdbc.query("""
                SELECT id, started_at, inspected_count, defect_unit_count
                FROM lot_history WHERE agent_id = ? AND lot_id = ?
                """, (rs, i) -> new Closed(rs.getLong(1),
                rs.getTimestamp(2) == null ? null : rs.getTimestamp(2).toLocalDateTime(),
                rs.getLong(3), rs.getLong(4)), agentId, lotId);
        if (rows.isEmpty()) {
            return false;
        }

        Closed closed = rows.get(0);
        jdbc.update("""
                UPDATE lot_counters
                   SET lot_id = ?, lot_started_at = ?, inspected_count = ?,
                       defect_unit_count = ?, unknown_count = 0
                 WHERE agent_id = ?
                """, lotId,
                closed.startedAt() == null ? null : Timestamp.valueOf(closed.startedAt()),
                closed.inspected(), closed.defects(), agentId);
        jdbc.update("""
                INSERT INTO lot_counter_judgement (agent_id, judgement, unit_count)
                SELECT ?, judgement, unit_count FROM lot_history_judgement WHERE lot_history_id = ?
                """, agentId, closed.id());
        // The judgement rows cascade with it.
        jdbc.update("DELETE FROM lot_history WHERE id = ?", closed.id());

        closedLots.put(lotId, false);
        log.warn("lot {} reopened on {}: new units arrived after it was closed, "
                + "so it was still running ({} units restored)", lotId, agentId, closed.inspected());
        return true;
    }

    private void ensureLot(String agentId, String lotId, LocalDateTime at,
                           Map<String, Boolean> closedLots) {
        if (isBlank(lotId)) {
            return;
        }
        List<CounterRow> rows = jdbc.query("""
                SELECT lot_id, lot_started_at, inspected_count, defect_unit_count
                FROM lot_counters WHERE agent_id = ? FOR UPDATE
                """, (rs, i) -> new CounterRow(rs.getString(1),
                rs.getTimestamp(2) == null ? null : rs.getTimestamp(2).toLocalDateTime(),
                rs.getLong(3), rs.getLong(4)), agentId);

        if (rows.isEmpty()) {
            jdbc.update("""
                    INSERT INTO lot_counters (agent_id, lot_id, lot_started_at) VALUES (?, ?, ?)
                    """, agentId, lotId, Timestamp.valueOf(at));
            return;
        }

        CounterRow current = rows.get(0);
        if (lotId.equals(current.lotId())) {
            return;
        }
        if (current.lotId() != null) {
            closeLot(agentId, current, at);
            // Anything still arriving for the lot just closed is out of order from here on.
            closedLots.put(current.lotId(), true);
        }
        jdbc.update("DELETE FROM lot_counter_judgement WHERE agent_id = ?", agentId);

        // A lot this agent has run before is only reached with units newer than anything
        // it ever recorded, so it was closed too early - put it back rather than starting
        // it again from zero.
        if (reopenLot(agentId, lotId, closedLots)) {
            log.info("lot change {}: {} -> {} (resumed)", agentId, current.lotId(), lotId);
            return;
        }

        closedLots.put(lotId, false);
        jdbc.update("""
                UPDATE lot_counters
                   SET lot_id = ?, lot_started_at = ?, inspected_count = 0,
                       defect_unit_count = 0, unknown_count = 0
                 WHERE agent_id = ?
                """, lotId, Timestamp.valueOf(at), agentId);
        log.info("lot change {}: {} -> {}", agentId, current.lotId(), lotId);
    }

    private void closeLot(String agentId, CounterRow current, LocalDateTime endedAt) {
        String modelId = jdbc.query("SELECT current_model_id FROM agents WHERE agent_id = ?",
                (rs, i) -> rs.getString(1), agentId).stream().findFirst().orElse(null);

        KeyHolder keys = new GeneratedKeyHolder();
        int inserted = jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT IGNORE INTO lot_history
                      (agent_id, lot_id, model_id, started_at, ended_at, inspected_count, defect_unit_count)
                    VALUES (?,?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, agentId);
            ps.setString(2, current.lotId());
            ps.setString(3, modelId);
            ps.setTimestamp(4, current.startedAt() == null ? null : Timestamp.valueOf(current.startedAt()));
            ps.setTimestamp(5, Timestamp.valueOf(endedAt));
            ps.setLong(6, current.inspected());
            ps.setLong(7, current.defects());
            return ps;
        }, keys);

        if (inserted == 0 || keys.getKey() == null || keys.getKey().longValue() == 0L) {
            return;
        }
        jdbc.update("""
                INSERT INTO lot_history_judgement (lot_history_id, judgement, unit_count)
                SELECT ?, judgement, unit_count FROM lot_counter_judgement WHERE agent_id = ?
                """, keys.getKey().longValue(), agentId);
    }

    // ---- alarms ------------------------------------------------------------

    private boolean applyAlarm(String agentId, EventBatch batch, AgentEvent.VisionAlarm alarm) {
        if (isBlank(alarm.eventUid())) {
            throw new IllegalArgumentException("VISION_ALARM requires eventUid");
        }
        int inserted = jdbc.update("""
                INSERT IGNORE INTO alarms
                  (event_uid, agent_id, line, vision_key, alarm_code, alarm_name, alarm_detail,
                   raw_message, alarm_at, alarm_at_raw, source_drive, source_file, raw_line)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                alarm.eventUid(), agentId, batch.line(), batch.visionKey(),
                blankToNull(alarm.code()), blankToNull(alarm.name()), blankToNull(alarm.detail()),
                blankToNull(alarm.rawMessage()),
                alarm.alarmAt() == null ? null : Timestamp.valueOf(at(alarm.alarmAt())),
                blankToNull(alarm.alarmAtRaw()), blankToNull(alarm.sourceDrive()),
                blankToNull(alarm.sourceFile()), blankToNull(alarm.rawLine()));
        return inserted > 0;
    }

    // ---- counters / rollups ------------------------------------------------

    /** Writes a whole batch's deltas in a handful of statements instead of per unit. */
    private void flush(Accumulator acc) {
        if (acc.inspected > 0 || acc.defects > 0 || acc.unknown > 0) {
            // lot_id is deliberately absent from the UPDATE clause: only ensureLot may
            // move a counters row to a different lot, and only alongside a reset.
            jdbc.update("""
                    INSERT INTO lot_counters (agent_id, lot_id, inspected_count, defect_unit_count, unknown_count)
                    VALUES (?,?,?,?,?) AS new
                    ON DUPLICATE KEY UPDATE
                      inspected_count   = lot_counters.inspected_count   + new.inspected_count,
                      defect_unit_count = lot_counters.defect_unit_count + new.defect_unit_count,
                      unknown_count     = lot_counters.unknown_count     + new.unknown_count
                    """, acc.agentId, acc.lotId, acc.inspected, acc.defects, acc.unknown);
        }
        acc.judgements.forEach((judgement, count) -> jdbc.update("""
                INSERT INTO lot_counter_judgement (agent_id, judgement, unit_count)
                VALUES (?,?,?) AS new
                ON DUPLICATE KEY UPDATE unit_count = lot_counter_judgement.unit_count + new.unit_count
                """, acc.agentId, judgement, count));

        acc.rollup.forEach((key, counts) -> jdbc.update("""
                INSERT INTO vision_rollup (agent_id, bucket_start, lot_id, inspected_count, defect_unit_count)
                VALUES (?,?,?,?,?) AS new
                ON DUPLICATE KEY UPDATE
                  inspected_count   = vision_rollup.inspected_count   + new.inspected_count,
                  defect_unit_count = vision_rollup.defect_unit_count + new.defect_unit_count
                """, acc.agentId, Timestamp.valueOf(key.bucket()), key.lotId(), counts[0], counts[1]));

        acc.rollupJudgement.forEach((key, count) -> jdbc.update("""
                INSERT INTO vision_rollup_judgement (agent_id, bucket_start, lot_id, judgement, unit_count)
                VALUES (?,?,?,?,?) AS new
                ON DUPLICATE KEY UPDATE unit_count = vision_rollup_judgement.unit_count + new.unit_count
                """, acc.agentId, Timestamp.valueOf(key.bucket()), key.lotId(), key.judgement(), count));

        acc.reset();
    }

    // ---- agent bookkeeping -------------------------------------------------

    private void upsertAgent(String agentId, EventBatch batch) {
        String lotId = lastNonBlank(batch, AgentEvent.UnitInspected::lotId);
        String modelId = lastNonBlank(batch, AgentEvent.UnitInspected::modelId);
        String sourceFile = lastNonBlank(batch, AgentEvent.UnitInspected::sourceFile);
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());

        jdbc.update("""
                INSERT INTO agents (agent_id, line, vision_key, current_lot_id, current_model_id,
                                    last_source_file, last_event_at, agent_version)
                VALUES (?,?,?,?,?,?,?,?) AS new
                ON DUPLICATE KEY UPDATE
                  current_lot_id   = COALESCE(new.current_lot_id,   agents.current_lot_id),
                  current_model_id = COALESCE(new.current_model_id, agents.current_model_id),
                  last_source_file = COALESCE(new.last_source_file, agents.last_source_file),
                  last_event_at    = new.last_event_at,
                  agent_version    = COALESCE(new.agent_version,    agents.agent_version)
                """,
                agentId, batch.line(), batch.visionKey(), lotId, modelId, sourceFile, now,
                blankToNull(batch.agentVersion()));
    }

    private String lastNonBlank(EventBatch batch, java.util.function.Function<AgentEvent.UnitInspected, String> get) {
        String found = null;
        for (AgentEvent event : batch.eventsOrEmpty()) {
            if (event instanceof AgentEvent.UnitInspected unit) {
                String value = get.apply(unit);
                if (!isBlank(value)) {
                    found = value.trim();
                }
            }
        }
        return found;
    }

    /** Empty rather than null so a unit without a lot still has one progress row of its
     *  own, instead of silently sharing the primary key with every other such unit. */
    private static String lotKey(String lotId) {
        return isBlank(lotId) ? "" : lotId.trim();
    }

    private long loadHighWater(String agentId, String lotId) {
        return jdbc.query("SELECT last_unit_seq FROM agent_lot_progress WHERE agent_id = ? AND lot_id = ?",
                (rs, i) -> rs.getLong(1), agentId, lotId).stream().findFirst().orElse(-1L);
    }

    /**
     * Reports an agent whose every unit is being refused, without touching anything.
     *
     * The read position only ever rises, so one stray high sequence bars every real unit
     * for the rest of the lot - an inspector sits at "1 inspected" for a whole shift
     * while reporting healthy. That is now prevented at the source: the agent decides
     * where a lot begins by the unit number rather than the lot name, so it no longer
     * offers the outgoing lot's numbering under the incoming lot's name.
     *
     * Correcting the position here as well was tried and removed. Working out whether a
     * refusal is a stall or an ordinary re-send needs guesswork, and guessing wrong is
     * worse than the problem: the first attempt "repaired" healthy inspectors every
     * second because they legitimately ran behind their own numbering. So this only
     * says what it sees, loudly enough to be found and rarely enough to stay readable.
     */
    private void noteStall(String agentId, String lotId, long lowestSeq, long highestSeq, long readTo) {
        String key = agentId + "|" + lotId;
        Stall stall = stalls.computeIfAbsent(key, k -> new Stall());

        if (highestSeq > stall.highestRefusedSeq) {
            stall.batches++;
        } else {
            stall.batches = 1;
        }
        stall.highestRefusedSeq = highestSeq;

        // Once when it starts, then rarely: at one batch a second an unbounded warning
        // buries every other line in the log within an hour.
        if (stall.batches == 1 || stall.batches % STALL_REPORT_EVERY == 0) {
            log.warn("{} lot {}: nothing accepted for {} batch(es) - offering unit {} but "
                            + "already read to {}. The lot is being discarded.",
                    agentId, lotId, stall.batches, lowestSeq, readTo);
        }
    }

    private void saveHighWater(String agentId, String lotId, long seq) {
        jdbc.update("""
                INSERT INTO agent_lot_progress (agent_id, lot_id, last_unit_seq)
                VALUES (?,?,?) AS new
                ON DUPLICATE KEY UPDATE
                  last_unit_seq = GREATEST(agent_lot_progress.last_unit_seq, new.last_unit_seq)
                """, agentId, lotId, seq);
    }

    /** Climbing while a line runs normally means unit_seq is not monotonic within a lot
     *  either, which would make replay filtering unsound - worth being able to see. */
    private void bumpReplayed(String agentId, String lotId, int count) {
        jdbc.update("""
                UPDATE agent_lot_progress SET replayed_count = replayed_count + ?
                 WHERE agent_id = ? AND lot_id = ?
                """, count, agentId, lotId);
    }

    private void recordRaw(AgentEvent event, String agentId) {
        try {
            jdbc.update("INSERT INTO raw_events (event_uid, event_type, agent_id, payload) VALUES (?,?,?,?)",
                    uid(event, agentId), event.type(), agentId, mapper.writeValueAsString(event));
        } catch (JacksonException e) {
            log.warn("could not store raw payload for {}: {}", event.type(), e.getMessage());
        }
    }

    /** Lets a raw payload be traced back to the row it produced. Alarms carry their own
     *  uid because a log line has no unit_seq to derive one from. */
    private String uid(AgentEvent event, String agentId) {
        return switch (event) {
            case AgentEvent.VisionAlarm alarm when !isBlank(alarm.eventUid()) -> alarm.eventUid();
            case AgentEvent.UnitInspected unit -> md5(agentId + "|" + unit.sourceFile() + "|" + unit.unitSeq());
            case AgentEvent.LotChanged lot -> md5(agentId + "|LOT|" + lot.newLotId());
            default -> md5(agentId + "|" + event.type() + "|" + System.nanoTime());
        };
    }

    private static String md5(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("MD5")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- helpers -----------------------------------------------------------

    private record CounterRow(String lotId, LocalDateTime startedAt, long inspected, long defects) {
    }

    private record RollupKey(LocalDateTime bucket, String lotId) {
    }

    private record RollupJudgementKey(LocalDateTime bucket, String lotId, String judgement) {
    }

    private static final class Accumulator {
        final String agentId;
        String lotId = "";
        long inspected;
        long defects;
        long unknown;
        final Map<String, Long> judgements = new LinkedHashMap<>();
        final Map<RollupKey, long[]> rollup = new LinkedHashMap<>();
        final Map<RollupJudgementKey, Long> rollupJudgement = new LinkedHashMap<>();

        Accumulator(String agentId) {
            this.agentId = agentId;
        }

        /** Deltas belong to exactly one lot; a unit from a different one forces a flush. */
        void retarget(String newLotId, java.util.function.Consumer<Accumulator> flush) {
            String value = newLotId == null ? "" : newLotId;
            if (!value.equals(lotId)) {
                if (inspected > 0 || defects > 0 || unknown > 0) {
                    flush.accept(this);
                }
                lotId = value;
            }
        }

        void addRollup(LocalDateTime bucket, String lot, boolean isDefect, String judgement) {
            String key = lot == null ? "" : lot;
            long[] counts = rollup.computeIfAbsent(new RollupKey(bucket, key), k -> new long[2]);
            counts[0]++;
            if (isDefect) {
                counts[1]++;
                rollupJudgement.merge(new RollupJudgementKey(bucket, key, judgement), 1L, Long::sum);
            }
        }

        void reset() {
            inspected = 0;
            defects = 0;
            unknown = 0;
            judgements.clear();
            rollup.clear();
            rollupJudgement.clear();
        }
    }

    private LocalDateTime bucket(LocalDateTime at) {
        int minute = at.getMinute() / ROLLUP_BUCKET_MINUTES * ROLLUP_BUCKET_MINUTES;
        return at.withMinute(minute).withSecond(0).withNano(0);
    }

    private LocalDateTime at(OffsetDateTime value) {
        return value == null ? LocalDateTime.now() : value.toZonedDateTime()
                .withZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDateTime();
    }

    private static Double parseNumber(String raw) {
        if (isBlank(raw)) {
            return null;
        }
        try {
            return Double.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return isBlank(s) ? null : s.trim();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
