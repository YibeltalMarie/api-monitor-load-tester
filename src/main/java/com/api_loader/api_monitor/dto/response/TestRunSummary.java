package com.api_loader.api_monitor.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A lightweight summary of one test run.
 *
 * WHY not just return the full TestRunResult everywhere?
 *   The history page shows a TABLE of many test runs.
 *   Loading full stats + all TestResults for every row
 *   would be extremely slow and wasteful.
 *
 *   TestRunSummary is the "table row" version — just enough
 *   data to render the history table. When the user clicks
 *   a row, THEN we load the full TestRunResult for that one.
 *
 * USED BY:
 *   LoadTestService.getHistory() → returns List<TestRunSummary>
 *   Dev B → GET /api/load-test/history
 *   Dev C → renders the history table, each row links to
 *           /load-test/{id}/result
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestRunSummary {

    private UUID id;
    private String url;
    private String method;
    private int totalRequests;
    private int concurrency;

    /**
     * RUNNING / COMPLETED / FAILED
     * Dev C shows a colored badge based on this value.
     */
    private String status;

    private OffsetDateTime startTime;

    /**
     * Pre-calculated average latency.
     * Calculated by LoadTestService when building this DTO
     * from the TestRun + its TestResults.
     * 0 if test failed before any results were recorded.
     */
    private long avgLatencyMs;

    /**
     * Pre-calculated success rate percentage (0.0 to 100.0).
     * 0.0 if test failed before any results were recorded.
     */
    private double successRatePercent;
}