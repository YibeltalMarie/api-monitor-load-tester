package com.api_loader.api_monitor.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The FULL result of one completed load test.
 *
 * This is the "detail view" DTO — returned when the user
 * opens a specific test result page. It contains everything:
 * the test configuration, timing, AND the full stats summary.
 *
 * STRUCTURE:
 *   TestRunResult
 *     ├── test config (url, method, totalRequests...)
 *     ├── timing (startTime, endTime, durationSeconds)
 *     └── summary (LoadTestSummary)
 *                   ├── avg/min/max latency
 *                   ├── p50/p95/p99
 *                   ├── successRate
 *                   └── errors (List<ErrorBreakdown>)
 *
 * USED BY:
 *   LoadTestService.getResult() → returns this
 *   Dev B → GET /api/load-test/{id}
 *   Dev C → renders the full result page with charts
 *
 * WHY separate from the TestRun entity?
 *   The TestRun entity is our database row — it has JPA
 *   annotations, lazy-loaded relationships, Hibernate
 *   metadata. We never send raw entities to the browser.
 *   This DTO is a clean, flat, JSON-serializable shape
 *   with exactly what Dev C needs — nothing more.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestRunResult {

    /** The test run's unique ID — Dev C uses this in URLs */
    private UUID id;

    private String url;
    private String method;
    private int totalRequests;
    private int concurrency;
    private int timeoutSeconds;

    /**
     * RUNNING / COMPLETED / FAILED
     * If RUNNING, Dev B redirects to the live page instead
     * of showing this result page.
     */
    private String status;

    private OffsetDateTime startTime;

    /** Null if test is still RUNNING */
    private OffsetDateTime endTime;

    /**
     * Total seconds from start to finish.
     * Calculated as: ChronoUnit.SECONDS.between(startTime, endTime)
     * 0 if endTime is null (still running).
     */
    private long durationSeconds;

    /**
     * The full statistical breakdown.
     * Null if test FAILED before any results were saved.
     * Dev C checks for null before rendering charts.
     */
    private LoadTestSummary summary;
}