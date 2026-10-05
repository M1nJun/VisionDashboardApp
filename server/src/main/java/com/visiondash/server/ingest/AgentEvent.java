package com.visiondash.server.ingest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The three things an agent can report. See contracts/events.md.
 *
 * There is deliberately no delta field anywhere: one UNIT_INSPECTED is one
 * physical unit, and every count the dashboard shows is derived from that by the
 * server. The previous contract sent one event per failed inspection item and
 * relied on a "only the first one counts" convention, which held only for as long
 * as every reader remembered it - and the trend chart did not.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "type", visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = AgentEvent.UnitInspected.class, name = "UNIT_INSPECTED"),
        @JsonSubTypes.Type(value = AgentEvent.LotChanged.class, name = "LOT_CHANGED"),
        @JsonSubTypes.Type(value = AgentEvent.VisionAlarm.class, name = "VISION_ALARM")
})
public sealed interface AgentEvent {

    String type();

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UnitInspected(
            String type,
            String sourceFile,
            Long unitSeq,
            String lotId,
            String modelId,
            String cellId,
            String judgement,
            OffsetDateTime occurredAt,
            List<Item> items,
            List<Image> images,
            List<String> warnings) implements AgentEvent {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Item(String name, String rawValue, String side) {
        }

        /** One row per FILE - main and overlay arrive separately so one missing path
         *  cannot discard the other. */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Image(String set, String kind, String path) {
        }

        public List<Item> itemsOrEmpty() {
            return items == null ? List.of() : items;
        }

        public List<Image> imagesOrEmpty() {
            return images == null ? List.of() : images;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LotChanged(
            String type,
            String sourceFile,
            String oldLotId,
            String newLotId,
            Long detectedAtUnitSeq,
            OffsetDateTime occurredAt) implements AgentEvent {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record VisionAlarm(
            String type,
            String eventUid,
            String sourceDrive,
            String sourceFile,
            String code,
            String name,
            String detail,
            String rawMessage,
            String rawLine,
            OffsetDateTime alarmAt,
            String alarmAtRaw) implements AgentEvent {
    }
}
