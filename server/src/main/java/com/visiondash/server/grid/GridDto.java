package com.visiondash.server.grid;

import java.time.Instant;
import java.util.List;

/**
 * What the grid screen needs, in one response.
 *
 * Judgement counts are a list rather than fixed ngCount/dlngCount/cngCount fields: a
 * Example B cell carries three entries and an Example E cell one, and the frontend renders
 * whatever arrives. Adding a judgement to a vision type needs no change here, in the
 * database, or in the UI.
 */
public record GridDto(List<Cell> cells, List<LineProduction> lines, Totals totals,
                     Integer windowMinutes) {

    /**
     * status: NOT_DEPLOYED | OFFLINE | IDLE | RUNNING
     * colorLevel: GREY when offline or undeployed, otherwise GREEN/YELLOW/RED from the
     *   one judgement the catalog marks drivesColor - never a mix of signals in one cell.
     */
    public record Cell(
            String line,
            String visionKey,
            String displayName,
            String shortName,
            String status,
            String colorLevel,
            String agentId,
            String lotId,
            String modelId,
            Long inspectedCount,
            Long defectUnitCount,
            Double defectRatePct,
            List<JudgementCount> judgements,
            /** True when this cell counted nothing inside the requested window. The rate
             *  is then null rather than 0%: an inspector that produced nothing in the last
             *  five minutes has no rate, and ranking it as perfect would be a lie. */
            boolean windowEmpty,
            Integer alarmCount,
            Instant lastEventAt,
            Instant lastHeartbeatAt,
            boolean productionReference
    ) {
    }

    public record JudgementCount(String code, long count, Double ratePct, boolean drivesColor) {
    }

    /**
     * Every inspector on a line sees the same cells, so a line's output is one reference
     * inspector's count - not the sum of all of them, which counted each unit once per
     * station.
     *
     * estimated is true when the preferred reference was offline and a later candidate
     * stood in; upstream stations see slightly more units, so the number is a stand-in
     * and the UI says so rather than presenting it as measured.
     */
    public record LineProduction(String line, Long production, String sourceVisionKey, boolean estimated,
                                 long targetCells) {
    }

    public record Totals(
            long production,
            long defectUnits,
            Double defectRatePct,
            List<FleetRate> rates,
            long targetCells,
            int running,
            int idle,
            int offline,
            int notDeployed
    ) {
    }

    /**
     * A headline rate the catalog declares, e.g. NG across Example B and Example C, or DLNG
     * across the two Example B inspectors.
     *
     * Both numbers travel with the rate so the UI can show what it was computed from;
     * ratePct is null rather than 0 when nothing has been produced, because "no data"
     * and "nothing failed" must not look the same.
     */
    public record FleetRate(String key, String label, long units, Double ratePct) {
    }
}
