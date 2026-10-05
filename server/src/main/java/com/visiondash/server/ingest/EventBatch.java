package com.visiondash.server.ingest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * One POST /api/ingest/events body. Identity lives on the envelope so it is stated
 * once per batch and cannot vary between events that came from the same agent.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventBatch(
        String agentId,
        String line,
        String visionKey,
        String agentVersion,
        OffsetDateTime sentAt,
        List<AgentEvent> events) {

    public static final int MAX_EVENTS = 500;

    public List<AgentEvent> eventsOrEmpty() {
        return events == null ? List.of() : events;
    }

    /** What the agent reports vs what the server computes - a mismatch means a
     *  hand-edited personality file, which is worth rejecting loudly rather than
     *  storing under an id no grid cell will ever look up. */
    public record Result(int accepted, int replayed, List<Rejected> rejected) {
        public record Rejected(int index, String reason) {
        }
    }
}
