package com.api_loader.api_monitor.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * The full statistical summary of a completed load test.
 *
 * This is the most data-rich DTO in the project. It is:
 *   1. Nested inside TestRunResult (returned by getResult())
 *   2. Calculated by LoadTestService after all requests finish
 *   3. Serialized to JSON by Dev B and rendered by Dev C
 *      as the stats table and charts on the result page
 *
 * HOW THE STATS ARE CALCULATED (Dev A does this):
 *
 *   Given a list of all TestResult rows for a test run:
 *
 *   successCount     = count where success == true
 *   failureCount     = count where success == false
 *   successRate      = (successCount / totalSent) * 100
 *
 *   For latency stats — use ONLY successful requests:
 *     sort latencies ascending
 *     avg = sum / count
 *     min = first element
 *     max = last element
 *     p50 = element at index (size * 0.50)
 *     p95 = element at index (size * 0.95)
 *     p99 = element at index (size * 0.99)
 *
 *   requestsPerSecond = totalSent / durationSeconds
 *
 *   errors = group failed requests by statusCode,
 *            count each group → List<ErrorBreakdown>
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoadTestSummary {

    /** Total requests that were fired */
    private int totalSent;

    /** Requests that got the expected response */
    private int successCount;

    /** Requests that failed for any reason */
    private int failureCount;

    /** (successCount / totalSent) * 100 */
    private double successRatePercent;

    /** Average latency across successful requests */
    private long avgLatencyMs;

    /** Fastest successful request */
    private long minLatencyMs;

    /** Slowest successful request */
    private long maxLatencyMs;

    /**
     * Median — 50% of requests were faster than this.
     * The "typical" experience for a user.
     */
    private long p50LatencyMs;

    /**
     * 95th percentile — 95% of requests were faster.
     * Catches the "slow tail" — unlucky users.
     * This is the most watched metric in production.
     */
    private long p95LatencyMs;

    /**
     * 99th percentile — 99% of requests were faster.
     * Your absolute worst-case. If p99 = 3000ms,
     * 1 in 100 users waited 3 full seconds.
     */
    private long p99LatencyMs;

    /** How many requests per second the API handled */
    private double requestsPerSecond;

    /**
     * Error breakdown grouped by status code.
     * Empty list if all requests succeeded.
     * Never null — LoadTestService always sets this.
     */
    private List<ErrorBreakdown> errors;
}