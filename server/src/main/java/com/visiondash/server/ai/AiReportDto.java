package com.visiondash.server.ai;

import java.time.Instant;

/**
 * One stored report, as the tab reads it.
 *
 * facts is served alongside the prose rather than instead of it. The summary is what
 * somebody reads walking past; the facts are what they open when the summary says
 * something they want to check, and having both on one response means checking a
 * sentence never costs a second round trip.
 *
 * summary may be null while facts is present - that is a window whose figures were
 * computed but whose model was unavailable, and the tab renders the findings itself.
 * The reverse cannot happen.
 */
public record AiReportDto(
        long id,
        Instant windowStart,
        Instant windowEnd,
        Instant generatedAt,
        /** OK, LLM_FAILED or SKIPPED. */
        String status,
        /** NORMAL, WATCH or ALERT. Decided in code, never by the model. */
        String severity,
        String headline,
        String summary,
        String model,
        Integer latencyMs,
        String error,
        /** Null only for a row written by an older build with an incompatible fact shape. */
        AiFacts facts
) {
}
