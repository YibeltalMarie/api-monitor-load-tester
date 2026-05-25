package com.api_loader.api_monitor.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Full history response for the monitor detail page.
 *
 * FLOW:
 *   Dev C calls GET /api/monitor/{id}/results?hours=24
 *   Dev B calls MonitorService.getHistory(endpointId, user, hours)
 *   Dev A builds this object and returns it
 *   Dev C uses uptimePercent for the stat card,
 *   and results list for the Chart.js latency line chart
 *
 * UPTIME % CALCULATION:
 *   uptimePercent = (upCount / totalChecks) * 100
 *   If totalChecks is 0 (never checked) → uptimePercent = null
 */
@Getter
@Builder
public class EndpointHistoryResponse {

    // ── Endpoint info ────────────────────────────────────────────────
    private Long endpointId;
    private String url;
    private String method;

    // ── Summary stats for the time window ───────────────────────────
    private int hoursWindow;        // the hours param that was requested e.g. 24
    private long totalChecks;       // total MonitorResult rows in window
    private long upCount;           // how many were isUp = true
    private long downCount;         // how many were isUp = false
    private Double uptimePercent;   // null if never checked, 0.0–100.0 otherwise

    // ── Raw results for Chart.js line chart ─────────────────────────
    private List<CheckPointResult> results;

    /**
     * One data point on the latency line chart.
     * Each MonitorResult maps to one of these.
     */
    @Getter
    @Builder
    public static class CheckPointResult {
        private OffsetDateTime checkedAt;
        private Boolean isUp;
        private Long latencyMs;
        private Integer statusCode;
        private String errorMsg;
    }
}