package com.api_loader.api_monitor.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;

/**
 * One row on the monitor status board.
 *
 * FLOW:
 *   MonitorService.getEndpoints() builds one of these per endpoint.
 *   It combines the MonitoredEndpoint config with the latest
 *   MonitorResult so Dev C gets everything needed in one object.
 *
 * FIELDS EXPLAINED:
 *   id              — endpoint ID, used by Dev C for delete/toggle calls
 *   url             — displayed in the status board row
 *   method          — displayed next to the URL
 *   intervalSeconds — shown as "checks every Xs"
 *   enabled         — drives the toggle switch state on the UI
 *   up              — true = green UP badge, false = red DOWN badge
 *                     null = never checked yet (grey PENDING badge)
 *   lastCheckedAt   — "Last checked 2 minutes ago"
 *   lastLatencyMs   — shown as response time on the row
 *   lastStatusCode  — shown next to latency
 *   lastErrorMsg    — shown in tooltip or detail when down
 */
@Getter
@Builder
public class EndpointStatusResponse {

    // ── Endpoint config ──────────────────────────────────────────────
    private Long id;
    private String url;
    private String method;
    private int intervalSeconds;
    private int expectedStatusCode;
    private boolean enabled;

    // ── Latest check result ──────────────────────────────────────────
    // All nullable — null means the endpoint has never been checked yet
    private Boolean up;
    private OffsetDateTime lastCheckedAt;
    private Long lastLatencyMs;
    private Integer lastStatusCode;
    private String lastErrorMsg;
}