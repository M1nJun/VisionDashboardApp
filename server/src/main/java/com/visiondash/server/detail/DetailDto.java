package com.visiondash.server.detail;

import com.visiondash.server.grid.GridDto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One inspector's detail page. Every list here is scoped to the current lot, the same
 * lot the grid cell's numbers come from - a page where the headline rate is scoped and
 * the lists below it are not looks like the two disagree.
 */
public record DetailDto(
        GridDto.Cell cell,
        List<DefectGroup> topDefects,
        /** Every NG this inspector recorded in the lot, newest first, as light rows. The
         *  images and measurements for one of them are fetched on demand - carrying them
         *  for hundreds of rows would put megabytes on a five-second poll. */
        List<Event> events,
        /** How many NG the lot actually holds, so a truncated list can say so. */
        long eventTotal,
        List<Alarm> alarms,
        List<TrendPoint> trend
) {

    /** One label per physical unit ("CHECK_A + CHECK_B"), counted once - never split
     *  into its individual items, which would suggest more units failed than did.
     *  Doubles as the defect-name filter on the event table. */
    public record DefectGroup(String label, String judgement, long count) {
    }

    public record Event(
            long id,
            Instant occurredAt,
            String cellId,
            long unitSeq,
            String judgement,
            String label
    ) {
    }

    public record Occurrence(
            long id,
            String judgement,
            String label,
            String cellId,
            long unitSeq,
            Instant occurredAt,
            List<Item> items,
            List<Image> images
    ) {
    }

    public record Item(String name, String rawValue, Double value, String side) {
    }

    /** url is null until the file has actually been fetched; state says why. */
    public record Image(long id, String set, String kind, String state, String url) {
    }

    public record Alarm(String code, String name, String detail, Instant at) {
    }

    public record TrendPoint(
            Instant bucketStart, long inspected, long defectUnits,
            Double defectRatePct, Map<String, Long> judgements, Map<String, Double> judgementRatePct
    ) {
    }
}
