package com.api_loader.api_monitor.service;

import com.api_loader.api_monitor.dto.request.LoadTestRequest;
import com.api_loader.api_monitor.dto.response.ErrorBreakdown;
import com.api_loader.api_monitor.dto.response.LoadTestSummary;
import com.api_loader.api_monitor.dto.response.SingleRequestResult;
import com.api_loader.api_monitor.dto.response.TestRunResult;
import com.api_loader.api_monitor.dto.response.TestRunSummary;
import com.api_loader.api_monitor.exception.AccessDeniedException;
import com.api_loader.api_monitor.exception.TestRunNotFoundException;
import com.api_loader.api_monitor.model.TestResult;
import com.api_loader.api_monitor.model.TestRun;
import com.api_loader.api_monitor.model.User;
import com.api_loader.api_monitor.repository.TestResultRepository;
import com.api_loader.api_monitor.repository.TestRunRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * LoadTestService is the most complex service in the project.
 * It coordinates everything that happens during a load test:
 *
 *   1. startTest()   — fires all requests concurrently,
 *                      saves results, calculates stats
 *   2. getResult()   — loads one test result with full stats
 *   3. getHistory()  — loads all past tests for a user
 *   4. getRawResults() — loads raw per-request data for charts
 *
 * ── VIRTUAL THREADS (Java 21) ────────────────────────────
 * Normal OS threads are expensive — each uses ~1MB of memory
 * and the OS can only handle ~200-500 at once.
 * Virtual threads are cheap — they use ~few KB each and you
 * can create thousands. Perfect for firing many HTTP requests
 * at the same time.
 *
 * We use:
 *   Executors.newVirtualThreadPerTaskExecutor()
 * This creates a new virtual thread for EACH task submitted.
 *
 * ── CONCURRENCY CONTROL ──────────────────────────────────
 * The user sets concurrency=10. This means max 10 requests
 * run at the same time. We control this with a semaphore:
 *   - Semaphore(10) allows 10 threads to run simultaneously
 *   - Thread 11 waits until one of the 10 finishes
 *   - This prevents overwhelming the target server
 *
 * ── @PostConstruct ───────────────────────────────────────
 * Runs once when the app starts. We use it to fix any
 * TestRun stuck in RUNNING status from a previous crash.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoadTestService {

    private final TestRunRepository testRunRepository;
    private final TestResultRepository testResultRepository;
    private final HttpClientService httpClientService;

    // ─────────────────────────────────────────────────────
    // STARTUP: fix stuck RUNNING tests
    // ─────────────────────────────────────────────────────

    /**
     * On startup, mark any RUNNING tests as FAILED.
     *
     * WHY? If the app crashed mid-test, some TestRun rows
     * are left with status=RUNNING forever. They will never
     * complete. On next startup we mark them FAILED so the
     * history page shows an honest status.
     *
     * @PostConstruct runs after Spring finishes injecting
     * all dependencies but before the app accepts requests.
     */
    @PostConstruct
    @Transactional
    public void fixStuckRunningTests() {
        List<TestRun> stuck = testRunRepository
                .findByStatus(TestRun.TestRunStatus.RUNNING);

        if (!stuck.isEmpty()) {
            log.warn("Found {} stuck RUNNING test(s) from previous "
                    + "session — marking as FAILED", stuck.size());

            stuck.forEach(run -> {
                run.setStatus(TestRun.TestRunStatus.FAILED);
                run.setEndTime(OffsetDateTime.now());
            });

            testRunRepository.saveAll(stuck);
        }
    }

    // ─────────────────────────────────────────────────────
    // METHOD 1: startTest()
    // Called by: Dev B in POST /api/load-test/run
    // ─────────────────────────────────────────────────────

    /**
     * Starts a load test and runs it synchronously.
     *
     * NOTE FOR GROUP 2:
     *   This method currently runs synchronously and returns
     *   the full TestRunResult when done.
     *   In Group 2 we will add SseEmitter support so results
     *   stream live to the browser. The signature will change:
     *     startTest(request, user, SseEmitter emitter)
     *   We will notify Dev B when this changes.
     *
     * FLOW:
     *   Step 1 → Save TestRun with status=RUNNING
     *   Step 2 → Create virtual thread executor
     *   Step 3 → Submit all requests as tasks
     *   Step 4 → Wait for all to complete
     *   Step 5 → Save all TestResults to DB
     *   Step 6 → Calculate summary stats
     *   Step 7 → Update TestRun to COMPLETED
     *   Step 8 → Return TestRunResult
     *
     * @param request  the load test configuration
     * @param user     the logged-in user (from Dev B)
     * @return         full result including stats
     */
        @Transactional
        public UUID startTest(LoadTestRequest request, User user) {

        log.info("Starting load test for user '{}': {} {} "
                + "({} requests, concurrency {})",
                user.getUsername(), request.getMethod(),
                request.getUrl(), request.getTotalRequests(),
                request.getConcurrency());

        // ── Step 1: Create and save TestRun ───────────────
        // status=RUNNING is set by @PrePersist in TestRun
        TestRun testRun = TestRun.builder()
                .user(user)
                .url(request.getUrl())
                .method(request.getMethod())
                .totalRequests(request.getTotalRequests())
                .concurrency(request.getConcurrency())
                .timeoutSeconds(request.getTimeoutSeconds())
                .build();

        testRun = testRunRepository.save(testRun);
        final UUID testRunId = testRun.getId();

        log.info("TestRun created with id: {}", testRunId);

        try {
                // ── Step 2: Create virtual thread executor ────
                ExecutorService executor = Executors
                        .newVirtualThreadPerTaskExecutor();

                // Semaphore controls how many requests run at once
                java.util.concurrent.Semaphore semaphore =
                        new java.util.concurrent.Semaphore(
                                request.getConcurrency());

                // Thread-safe counter for request index
                AtomicInteger indexCounter = new AtomicInteger(0);

                // ── Step 3: Submit all requests as tasks ──────
                List<Future<SingleRequestResult>> futures =
                        new ArrayList<>();

                for (int i = 0; i < request.getTotalRequests(); i++) {
                Future<SingleRequestResult> future = executor.submit(() -> {
                        semaphore.acquire();
                        try {
                        return httpClientService.fire(
                                request.getUrl(),
                                request.getMethod(),
                                request.getRequestBody(),
                                request.getHeaders(),
                                request.getTimeoutSeconds());
                        } finally {
                        semaphore.release();
                        }
                });

                futures.add(future);
                }

                // ── Step 4: Wait for ALL requests to complete ─
                List<SingleRequestResult> results = new ArrayList<>();
                for (Future<SingleRequestResult> future : futures) {
                try {
                        results.add(future.get());
                } catch (Exception ex) {
                        log.warn("Task failed unexpectedly: {}",
                                ex.getMessage());
                        results.add(SingleRequestResult.builder()
                                .statusCode(0)
                                .latencyMs(0)
                                .success(false)
                                .errorMessage("Task error: "
                                        + ex.getMessage())
                                .build());
                }
                }

                // Shut down the executor
                executor.shutdown();

                log.info("All {} requests completed for test {}",
                        results.size(), testRunId);

                // ── Step 5: Save all TestResults to DB ────────
                List<TestResult> testResults = new ArrayList<>();
                for (SingleRequestResult result : results) {
                int idx = indexCounter.incrementAndGet();

                TestResult testResult = TestResult.builder()
                        .testRun(testRun)
                        .index(idx)
                        .statusCode(result.getStatusCode())
                        .latencyMs(result.getLatencyMs())
                        .success(result.isSuccess())
                        .errorMsg(result.getErrorMessage())
                        .build();

                testResults.add(testResult);
                }

                testResultRepository.saveAll(testResults);

                // ── Step 6: Calculate summary stats ───────────
                LoadTestSummary summary = calculateSummary(
                        results, testRun);

                // ── Step 7: Update TestRun to COMPLETED ───────
                testRun.setStatus(TestRun.TestRunStatus.COMPLETED);
                testRun.setEndTime(OffsetDateTime.now());
                testRunRepository.save(testRun);

                log.info("Test {} completed: {} requests, "
                        + "{}% success, avg {}ms",
                        testRunId,
                        summary.getTotalSent(),
                        String.format("%.1f", summary.getSuccessRatePercent()),
                        summary.getAvgLatencyMs());

                // ── Step 8: Return UUID only ───────────────────
                return testRunId;

        } catch (Exception ex) {
                // Something crashed before all requests completed
                log.error("Test {} failed unexpectedly: {}",
                        testRunId, ex.getMessage());

                testRun.setStatus(TestRun.TestRunStatus.FAILED);
                testRun.setEndTime(OffsetDateTime.now());
                testRunRepository.save(testRun);

                // Return UUID even on failure
                return testRunId;
        }
        }

    // ─────────────────────────────────────────────────────
    // METHOD 2: getResult()
    // Called by: Dev B in GET /api/load-test/{id}
    // ─────────────────────────────────────────────────────

    /**
     * Loads a completed test result with full stats.
     *
     * FLOW:
     *   Step 1 → find TestRun by id, throw 404 if not found
     *   Step 2 → check ownership, throw 403 if wrong user
     *   Step 3 → load all TestResults for this run
     *   Step 4 → calculate summary from raw results
     *   Step 5 → return TestRunResult
     */
    @Transactional(readOnly = true)
    public TestRunResult getResult(UUID testRunId, User user) {

        // Step 1: load the TestRun
        TestRun testRun = testRunRepository.findById(testRunId)
                .orElseThrow(() ->
                        new TestRunNotFoundException(testRunId));

        // Step 2: verify ownership
        if (!testRun.getUser().getId().equals(user.getId())) {
            log.warn("User '{}' tried to access test run '{}' "
                    + "owned by '{}'",
                    user.getUsername(), testRunId,
                    testRun.getUser().getUsername());
            throw new AccessDeniedException();
        }

        // Step 3: load raw results
        List<TestResult> testResults = testResultRepository
                .findByTestRunOrderByIndex(testRun);

        // Step 4: convert to SingleRequestResult for calculator
        List<SingleRequestResult> results = testResults.stream()
                .map(tr -> SingleRequestResult.builder()
                        .statusCode(tr.getStatusCode())
                        .latencyMs(tr.getLatencyMs())
                        .success(tr.isSuccess())
                        .errorMessage(tr.getErrorMsg())
                        .build())
                .collect(Collectors.toList());

        // Step 5: calculate and return
        LoadTestSummary summary = results.isEmpty()
                ? null
                : calculateSummary(results, testRun);

        return buildTestRunResult(testRun, summary);
    }

    // ─────────────────────────────────────────────────────
    // METHOD 3: getHistory()
    // Called by: Dev B in GET /api/load-test/history
    // ─────────────────────────────────────────────────────

    /**
     * Returns all past test runs for a user, newest first.
     * Returns lightweight TestRunSummary — not full results.
     * Returns empty list if no runs — never null.
     */
    @Transactional(readOnly = true)
    public List<TestRunSummary> getHistory(User user) {

        List<TestRun> runs = testRunRepository
                .findByUserOrderByStartTimeDesc(user);

        return runs.stream()
                .map(run -> {
                    // For history list, calculate avg and
                    // success rate from saved results
                    List<TestResult> results =
                            testResultRepository
                                    .findByTestRunOrderByIndex(run);

                    long avgLatency = 0;
                    double successRate = 0.0;

                    if (!results.isEmpty()) {
                        long successCount = results.stream()
                                .filter(TestResult::isSuccess)
                                .count();

                        successRate = (successCount * 100.0)
                                / results.size();

                        avgLatency = (long) results.stream()
                                .filter(TestResult::isSuccess)
                                .mapToLong(TestResult::getLatencyMs)
                                .average()
                                .orElse(0);
                    }

                    return TestRunSummary.builder()
                            .id(run.getId())
                            .url(run.getUrl())
                            .method(run.getMethod())
                            .totalRequests(run.getTotalRequests())
                            .concurrency(run.getConcurrency())
                            .status(run.getStatus().name())
                            .startTime(run.getStartTime())
                            .avgLatencyMs(avgLatency)
                            .successRatePercent(successRate)
                            .build();
                })
                .collect(Collectors.toList());
    }

    // ─────────────────────────────────────────────────────
    // METHOD 4: getRawResults()
    // Called by: Dev B in GET /api/load-test/{id}/results
    //            and GET /api/load-test/{id}/export (CSV)
    // ─────────────────────────────────────────────────────

    /**
     * Returns raw per-request TestResult entities.
     * Dev B serializes these directly for the chart endpoint.
     */
    @Transactional(readOnly = true)
    public List<TestResult> getRawResults(UUID testRunId, User user) {

        TestRun testRun = testRunRepository.findById(testRunId)
                .orElseThrow(() ->
                        new TestRunNotFoundException(testRunId));

        if (!testRun.getUser().getId().equals(user.getId())) {
            throw new AccessDeniedException();
        }

        return testResultRepository
                .findByTestRunOrderByIndex(testRun);
    }

    // ─────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────

    /**
     * Calculates all statistics from a list of request results.
     *
     * PERCENTILE ALGORITHM:
     *   1. Collect latencies of SUCCESSFUL requests only
     *   2. Sort ascending
     *   3. p50 = value at 50% index
     *   4. p95 = value at 95% index
     *   5. p99 = value at 99% index
     *
     * WHY only successful requests for latency?
     *   Failed requests (timeout, refused) have artificially
     *   high or misleading latency values. Including them
     *   in p99 would misrepresent the API's actual performance.
     *   We track failures separately in the errors list.
     */
    private LoadTestSummary calculateSummary(
            List<SingleRequestResult> results, TestRun testRun) {

        int totalSent = results.size();
        int successCount = 0;
        int failureCount = 0;

        // Collect successful latencies for percentile calc
        List<Long> successLatencies = new ArrayList<>();

        // Group failures by status code for error breakdown
        Map<Integer, List<SingleRequestResult>> errorGroups =
                new java.util.HashMap<>();

        for (SingleRequestResult result : results) {
            if (result.isSuccess()) {
                successCount++;
                successLatencies.add(result.getLatencyMs());
            } else {
                failureCount++;
                errorGroups
                        .computeIfAbsent(result.getStatusCode(),
                                k -> new ArrayList<>())
                        .add(result);
            }
        }

        // Sort latencies for percentile calculation
        Collections.sort(successLatencies);

        // Calculate latency stats (only from successful requests)
        long avgLatencyMs = 0;
        long minLatencyMs = 0;
        long maxLatencyMs = 0;
        long p50 = 0;
        long p95 = 0;
        long p99 = 0;

        if (!successLatencies.isEmpty()) {
            int size = successLatencies.size();

            avgLatencyMs = (long) successLatencies.stream()
                    .mapToLong(Long::longValue)
                    .average()
                    .orElse(0);

            minLatencyMs = successLatencies.get(0);
            maxLatencyMs = successLatencies.get(size - 1);

            // Percentile: index = (size * percentile)
            // Math.min guards against index out of bounds
            p50 = successLatencies.get(
                    Math.min((int)(size * 0.50), size - 1));
            p95 = successLatencies.get(
                    Math.min((int)(size * 0.95), size - 1));
            p99 = successLatencies.get(
                    Math.min((int)(size * 0.99), size - 1));
        }

        // Calculate success rate
        double successRatePercent = totalSent > 0
                ? (successCount * 100.0) / totalSent
                : 0.0;

        // Calculate requests per second
        // durationSeconds = time between start and now
        long durationSeconds = ChronoUnit.SECONDS.between(
                testRun.getStartTime(), OffsetDateTime.now());
        double requestsPerSecond = durationSeconds > 0
                ? (double) totalSent / durationSeconds
                : totalSent; // if < 1 second, rps = total

        // Build error breakdown list
        List<ErrorBreakdown> errors = errorGroups.entrySet()
                .stream()
                .map(entry -> ErrorBreakdown.builder()
                        .statusCode(entry.getKey())
                        .count(entry.getValue().size())
                        .reason(entry.getKey() == 0
                                ? "timeout or connection failed"
                                : null)
                        .build())
                .collect(Collectors.toList());

        return LoadTestSummary.builder()
                .totalSent(totalSent)
                .successCount(successCount)
                .failureCount(failureCount)
                .successRatePercent(successRatePercent)
                .avgLatencyMs(avgLatencyMs)
                .minLatencyMs(minLatencyMs)
                .maxLatencyMs(maxLatencyMs)
                .p50LatencyMs(p50)
                .p95LatencyMs(p95)
                .p99LatencyMs(p99)
                .requestsPerSecond(requestsPerSecond)
                .errors(errors)
                .build();
    }

    /**
     * Builds a TestRunResult from a TestRun entity and summary.
     * Centralised here so both startTest() and getResult()
     * produce identical response shapes.
     */
    private TestRunResult buildTestRunResult(
            TestRun testRun, LoadTestSummary summary) {

        long durationSeconds = 0;
        if (testRun.getEndTime() != null) {
            durationSeconds = ChronoUnit.SECONDS.between(
                    testRun.getStartTime(), testRun.getEndTime());
        }

        return TestRunResult.builder()
                .id(testRun.getId())
                .url(testRun.getUrl())
                .method(testRun.getMethod())
                .totalRequests(testRun.getTotalRequests())
                .concurrency(testRun.getConcurrency())
                .timeoutSeconds(testRun.getTimeoutSeconds())
                .status(testRun.getStatus().name())
                .startTime(testRun.getStartTime())
                .endTime(testRun.getEndTime())
                .durationSeconds(durationSeconds)
                .summary(summary)
                .build();
    }
}