package com.api_loader.api_monitor.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Represents one TYPE of error that occurred during a load test.
 *
 * WHY a separate class for this?
 *   During a load test of 100 requests, you might get:
 *     - 3 requests that returned 503 Service Unavailable
 *     - 2 requests that timed out (statusCode = 0)
 *   Instead of listing all 5 failures individually, we GROUP
 *   them by type and count them. Much cleaner for the UI.
 *
 * EXAMPLE: if LoadTestSummary.errors contains:
 *   [ {statusCode: 503, count: 3, reason: null},
 *     {statusCode: 0,   count: 2, reason: "timeout"} ]
 *
 * Dev C reads this and renders an error breakdown table
 * on the result page.
 *
 * ── WHY @Getter only, no @Setter? ────────────────────────
 *   This is a response DTO — it only travels OUT from the
 *   server to the browser. Once built, it should not change.
 *   @Builder lets us construct it, @Getter lets Dev B
 *   serialize it to JSON. No setters needed.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErrorBreakdown {

    /**
     * The HTTP status code of this error group.
     * 0 means no response at all — connection refused
     * or request timed out before server responded.
     *
     * Examples:
     *   200 → success (never in errors list)
     *   429 → Too Many Requests (rate limited)
     *   500 → Internal Server Error
     *   503 → Service Unavailable
     *   0   → timeout or connection refused
     */
    private int statusCode;

    /**
     * How many requests got this exact status code.
     */
    private int count;

    /**
     * Human-readable reason — only set for statusCode = 0
     * cases where there is no HTTP status to explain what
     * went wrong.
     *
     * Examples:
     *   "timeout"            → request exceeded timeoutSeconds
     *   "connection refused" → server not reachable at all
     *   null                 → for normal HTTP error codes
     *                          (reason is self-explanatory)
     */
    private String reason;
}