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
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
// Add to imports
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
/**
 * LoadTestService — Group 2 update.
 *
 * WHAT CHANGED FROM GROUP 1:
 *   1. startTest() now accepts a SseEmitter parameter
 *   2. startTest() is now @Async — runs in background thread
 *   3. @Transactional removed from startTest() — replaced
 *      with small targeted @Transactional helper methods
 *   4. emitter.send() called after each request (live update)
 *   5. emitter.send() called with "done" event at the end
 *   6. emitter.complete() closes the stream cleanly
 *   7. emitter.onTimeout() and onError() handled gracefully
 *
 * WHY THESE CHANGES:
 *   Group 1: browser waited for ALL requests to finish
 *            before seeing any results. No live feedback.
 *   Group 2: browser receives one event per completed
 *            request in real time. The result page shows
 *            a live chart that updates as requests finish.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoadTestService {

    private final TestRunRepository testRunRepository;
    private final TestResultRepository testResultRepository;
    private final HttpClientService httpClientService;
    // Add as a field (inject via constructor — Lombok @RequiredArgsConstructor handles this)
    private final ObjectMapper objectMapper;


/**
 * Reconstructs a LoadTestRequest from a saved TestRun entity.
 *
 * WHY THIS EXISTS:
 *   When GET /{id}/stream arrives, we only have the testRunId.
 *   We load the TestRun from DB and rebuild the original request
 *   so startTest() has everything it needs to fire the HTTP requests.
 *
 * WHY headers need Jackson deserialization:
 *   Headers were stored as a JSON string in the DB column
 *   (e.g. '{"Authorization":"Bearer abc123"}').
 *   We convert it back to Map<String, String> here.
 *   If deserialization fails we log a warning and proceed
 *   with null headers — better than crashing the whole stream.
 */
    public LoadTestRequest buildRequestFromTestRun(TestRun testRun) {

        Map<String, String> headers = null;

        if (testRun.getHeaders() != null) {
                try {
                headers = objectMapper.readValue(
                        testRun.getHeaders(),
                        new TypeReference<Map<String, String>>() {}
                );
                } catch (Exception ex) {
                log.warn("Could not deserialize headers for test run {}: {}",
                        testRun.getId(), ex.getMessage());
                }
        }

        LoadTestRequest request = new LoadTestRequest();
        request.setUrl(testRun.getUrl());
        request.setMethod(testRun.getMethod());
        request.setTotalRequests(testRun.getTotalRequests());
        request.setConcurrency(testRun.getConcurrency());
        request.setTimeoutSeconds(testRun.getTimeoutSeconds());
        request.setRequestBody(testRun.getRequestBody());
        request.setHeaders(headers);

        return request;
    }


    // ─────────────────────────────────────────────────────
    // STARTUP: fix stuck RUNNING tests
    // (unchanged from Group 1)
    // ─────────────────────────────────────────────────────

    @PostConstruct
    @Transactional
    public void fixStuckRunningTests() {
        List<TestRun> stuck = testRunRepository
                .findByStatus(TestRun.TestRunStatus.RUNNING);

        if (!stuck.isEmpty()) {
            log.warn("Found {} stuck RUNNING test(s) — marking FAILED",
                    stuck.size());
            stuck.forEach(run -> {
                run.setStatus(TestRun.TestRunStatus.FAILED);
                run.setEndTime(OffsetDateTime.now());
            });
            testRunRepository.saveAll(stuck);
        }
    }

    // ─────────────────────────────────────────────────────
    // STEP 1 — saveNewTestRun() helper
    // ─────────────────────────────────────────────────────

    /**
     * WHAT IT IS:
     *   A small private method that creates and saves the
     *   TestRun entity with status=RUNNING.
     *
     * WHY IT EXISTS (new in Group 2):
     *   In Group 1, startTest() had @Transactional on the
     *   whole method. That kept a DB connection open for the
     *   entire duration of the test (could be 60+ seconds).
     *   With @Async running many tests simultaneously, this
     *   exhausts the connection pool and crashes the app.
     *
     *   Solution: remove @Transactional from startTest() and
     *   instead use small targeted @Transactional methods that
     *   open a connection, do one thing, and close immediately.
     *
     *   This method opens a connection, inserts one row into
     *   test_run, and closes the connection. Takes ~5ms.
     */
     @Transactional
     public TestRun saveNewTestRun(LoadTestRequest request, User user) {

        String headersJson = null;
        if (request.getHeaders() != null) {
                try {
                headersJson = objectMapper.writeValueAsString(request.getHeaders());
                } catch (Exception ex) {
                log.warn("Could not serialize headers: {}", ex.getMessage());
                }
        }

        TestRun testRun = TestRun.builder()
                .user(user)
                .url(request.getUrl())
                .method(request.getMethod())
                .totalRequests(request.getTotalRequests())
                .concurrency(request.getConcurrency())
                .timeoutSeconds(request.getTimeoutSeconds())
                .requestBody(request.getRequestBody())   // NEW
                .headers(headersJson)                    // NEW
                .build();

        return testRunRepository.save(testRun);
    }

    // ─────────────────────────────────────────────────────
    // STEP 2 — saveTestResults() helper
    // ─────────────────────────────────────────────────────

    /**
     * WHAT IT IS:
     *   Saves a batch of TestResult rows to the database.
     *
     * WHY IT EXISTS:
     *   Same reason as saveNewTestRun() — we want a short,
     *   targeted transaction. This opens a connection, batch
     *   inserts all TestResult rows, closes immediately.
     *
     *   Called ONCE after all requests finish — not after
     *   each individual request (that would be too slow).
     */
    @Transactional
    protected void saveTestResults(List<TestResult> results) {
        testResultRepository.saveAll(results);
    }

    // ─────────────────────────────────────────────────────
    // STEP 3 — updateTestRunStatus() helper
    // ─────────────────────────────────────────────────────

    /**
     * WHAT IT IS:
     *   Updates the TestRun status to COMPLETED or FAILED
     *   and sets the endTime.
     *
     * WHY IT EXISTS:
     *   Another small targeted transaction. Opens connection,
     *   updates one row in test_run, closes immediately.
     *   Called at the very end of the test.
     */
    @Transactional
    protected void updateTestRunStatus(
            TestRun testRun, TestRun.TestRunStatus status) {

        testRun.setStatus(status);
        testRun.setEndTime(OffsetDateTime.now());
        testRunRepository.save(testRun);
    }

    // ─────────────────────────────────────────────────────
    // STEP 4 — startTest() — the main method, now @Async
    // ─────────────────────────────────────────────────────

    /**
     * WHAT IT IS:
     *   Starts a load test. Fires all HTTP requests using
     *   virtual threads, streams each result live via SSE,
     *   saves everything to DB, sends final summary.
     *
     * WHAT CHANGED FROM GROUP 1:
     *   - Now accepts SseEmitter parameter
     *   - Now annotated @Async — runs in a background thread
     *   - No longer has @Transactional on the whole method
     *   - Calls emitter.send() after each request
     *   - Calls emitter.send() with "done" event at finish
     *   - Calls emitter.complete() to close the stream
     *   - Handles emitter timeout and error callbacks
     *
     * WHY @Async:
     *   Without @Async, when Dev B calls startTest(), the
     *   HTTP request from the browser would hang until the
     *   entire test finishes. The browser would timeout.
     *
     *   With @Async, Spring runs this method in a separate
     *   thread from a thread pool. Dev B's controller returns
     *   the SseEmitter to the browser IMMEDIATELY. The browser
     *   starts listening on the SSE stream. Meanwhile this
     *   method runs in the background pushing events.
     *
     *   FLOW WITH @Async:
     *     Browser → POST /api/load-test/run
     *     Dev B calls saveNewTestRun() → gets UUID
     *     Dev B calls startTest() → returns immediately (@Async)
     *     Dev B returns { testRunId } to browser right away
     *     Background: startTest() runs, fires requests,
     *                 pushes SSE events to emitter
     *     Browser connects to SSE stream and receives events
     *
     * WHY void return type:
     *   @Async methods must return void or Future<>.
     *   We return void because Dev B already has the UUID
     *   from saveNewTestRun() before calling this method.
     *
     * @param request   load test configuration from user
     * @param user      logged-in user passed in by Dev B
     * @param testRun   already-saved TestRun entity
     *                  (Dev B saves it first, passes it here)
     * @param emitter   SSE emitter created by Dev B,
     *                  we push events into it
     */
    @Async
    public void startTest(
            LoadTestRequest request,
            User user,
            TestRun testRun,
            SseEmitter emitter) {

        final UUID testRunId = testRun.getId();

        log.info("Starting async load test {} for user '{}': "
                + "{} {} ({} requests, concurrency {})",
                testRunId, user.getUsername(),
                request.getMethod(), request.getUrl(),
                request.getTotalRequests(),
                request.getConcurrency());

        // ── STEP 5: Handle emitter timeout and error ──────
        //
        // WHAT THIS IS:
        //   Callbacks that fire if something goes wrong with
        //   the SSE connection BEFORE the test finishes.
        //
        // onTimeout() fires when the emitter's timeout expires.
        //   Default timeout is 5 minutes (set by Dev B).
        //   If a test takes longer than 5 minutes, this fires.
        //   We mark the test FAILED so it doesn't stay RUNNING.
        //
        // onError() fires if the browser disconnects mid-test.
        //   e.g. user closes the tab, network drops.
        //   We mark the test FAILED and log what happened.
        //
        // WHY we must handle these:
        //   Without handling, if the browser disconnects the
        //   test would keep running forever in the background,
        //   firing HTTP requests and wasting resources.
        //   With handling, we detect the disconnect and stop.

        emitter.onTimeout(() -> {
            log.warn("SSE emitter timed out for test {}",
                    testRunId);
            updateTestRunStatus(testRun,
                    TestRun.TestRunStatus.FAILED);
        });

        emitter.onError(ex -> {
            log.warn("SSE emitter error for test {}: {}",
                    testRunId, ex.getMessage());
            updateTestRunStatus(testRun,
                    TestRun.TestRunStatus.FAILED);
        });

        try {
            // ── STEP 6: Create virtual thread executor ────
            //
            // WHAT THIS IS:
            //   Same as Group 1 — creates a pool of virtual
            //   threads to run requests concurrently.
            //   Semaphore limits max concurrent requests.

            ExecutorService executor = Executors
                    .newVirtualThreadPerTaskExecutor();

            Semaphore semaphore = new Semaphore(
                    request.getConcurrency());

            AtomicInteger indexCounter = new AtomicInteger(0);

            // ── STEP 7: Submit all requests as tasks ──────
            // Same as Group 1 — unchanged

            List<Future<SingleRequestResult>> futures =
                    new ArrayList<>();

            for (int i = 0; i < request.getTotalRequests(); i++) {
                Future<SingleRequestResult> future =
                        executor.submit(() -> {
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

            // ── STEP 8: Collect results + send SSE events ─
            //
            // WHAT IS NEW HERE (Group 2 core change):
            //   In Group 1 we collected ALL results first,
            //   THEN saved them all at once.
            //
            //   In Group 2 we still collect all results BUT
            //   after EACH result comes in we immediately call
            //   emitter.send() to push it to the browser.
            //
            // HOW emitter.send() WORKS:
            //   emitter.send(data) serializes the object to
            //   JSON and pushes it down the open SSE connection
            //   to the browser as:
            //     data: {"index":1,"latencyMs":120,...}
            //
            //   The browser's EventSource fires onmessage()
            //   and Dev C's JavaScript updates the live chart.
            //
            // WHY we wrap in try/catch per send:
            //   If the browser disconnected mid-test, send()
            //   throws an IOException. We catch it and stop
            //   the test cleanly instead of crashing.

            List<TestResult> testResults = new ArrayList<>();

            for (Future<SingleRequestResult> future : futures) {
                SingleRequestResult result;

                try {
                    result = future.get();
                } catch (Exception ex) {
                    log.warn("Task failed: {}", ex.getMessage());
                    result = SingleRequestResult.builder()
                            .statusCode(0)
                            .latencyMs(0)
                            .success(false)
                            .errorMessage("Task error: "
                                    + ex.getMessage())
                            .build();
                }

                int idx = indexCounter.incrementAndGet();

                // Build TestResult entity for DB
                TestResult testResult = TestResult.builder()
                        .testRun(testRun)
                        .index(idx)
                        .statusCode(result.getStatusCode())
                        .latencyMs(result.getLatencyMs())
                        .success(result.isSuccess())
                        .errorMsg(result.getErrorMessage())
                        .build();

                testResults.add(testResult);

                // ── Send live SSE event to browser ────────
                //
                // WHAT THIS IS:
                //   After each request completes, we build a
                //   small Map of key data and send it to the
                //   browser immediately via the SSE stream.
                //
                // WHAT DEV C RECEIVES:
                //   data: {
                //     "index": 1,
                //     "latencyMs": 120,
                //     "statusCode": 200,
                //     "success": true
                //   }
                //
                // Dev C's live-results.js onmessage() receives
                // this and pushes a point onto the Chart.js chart.

                try {
                    Map<String, Object> event = new HashMap<>();
                    event.put("index", idx);
                    event.put("latencyMs", result.getLatencyMs());
                    event.put("statusCode", result.getStatusCode());
                    event.put("success", result.isSuccess());

                    emitter.send(
                        SseEmitter.event()
                            .data(event));

                } catch (IOException ex) {
                    // Browser disconnected — stop the test
                    log.warn("Browser disconnected during test "
                            + "{} at request {}", testRunId, idx);
                    executor.shutdownNow();
                    updateTestRunStatus(testRun,
                            TestRun.TestRunStatus.FAILED);
                    return;
                }
            }

            executor.shutdown();

            // ── STEP 9: Save all TestResults to DB ────────
            //
            // WHAT THIS IS:
            //   Batch insert all TestResult rows at once.
            //   Uses our small targeted @Transactional helper.
            //   Same as Group 1 but now called via helper method.

            saveTestResults(testResults);

            // ── STEP 10: Calculate summary stats ──────────
            // Same calculation as Group 1 — unchanged

            List<SingleRequestResult> allResults = testResults
                    .stream()
                    .map(tr -> SingleRequestResult.builder()
                            .statusCode(tr.getStatusCode())
                            .latencyMs(tr.getLatencyMs())
                            .success(tr.isSuccess())
                            .errorMessage(tr.getErrorMsg())
                            .build())
                    .collect(Collectors.toList());

            LoadTestSummary summary = calculateSummary(
                    allResults, testRun);

            // ── STEP 11: Update TestRun to COMPLETED ──────
            updateTestRunStatus(testRun,
                    TestRun.TestRunStatus.COMPLETED);

            log.info("Test {} completed: {} requests, "
                    + "{}% success, avg {}ms",
                    testRunId,
                    summary.getTotalSent(),
                    String.format("%.1f",
                            summary.getSuccessRatePercent()),
                    summary.getAvgLatencyMs());

            // ── STEP 12: Send "done" SSE event ────────────
            //
            // WHAT THIS IS:
            //   A special SSE event with event name "done".
            //   Contains the full summary statistics.
            //
            // HOW IT DIFFERS FROM regular events:
            //   Regular events: event name = "message" (default)
            //                   Dev C handles with onmessage()
            //   Done event:     event name = "done" (custom)
            //                   Dev C handles with:
            //                   source.addEventListener('done', ...)
            //
            // WHAT DEV C RECEIVES:
            //   event: done
            //   data: {
            //     "totalSent": 100,
            //     "successCount": 97,
            //     "failureCount": 3,
            //     "successRatePercent": 97.0,
            //     "avgLatencyMs": 142,
            //     "p50LatencyMs": 128,
            //     "p95LatencyMs": 430,
            //     "p99LatencyMs": 790
            //   }
            //
            // Dev C uses this to:
            //   1. Hide the loading spinner
            //   2. Show the final summary bar
            //   3. Close the EventSource connection

            try {
                emitter.send(
                    SseEmitter.event()
                        .name("done")
                        .data(summary));

            } catch (IOException ex) {
                log.warn("Could not send done event for test {}: {}",
                        testRunId, ex.getMessage());
            }

            // ── STEP 13: emitter.complete() ───────────────
            //
            // WHAT THIS IS:
            //   Closes the SSE connection from the server side.
            //
            // WHY WE MUST CALL THIS:
            //   Without complete(), the browser keeps the SSE
            //   connection open forever waiting for more events.
            //   complete() tells the browser "stream is done,
            //   no more events are coming, you can close."
            //
            // WHAT HAPPENS IN THE BROWSER:
            //   The EventSource fires onerror() when the server
            //   closes the connection. Dev C checks if the test
            //   is done and handles it gracefully (does not show
            //   an error message — closing after done is normal).

            emitter.complete();

        } catch (Exception ex) {
            // ── Unexpected crash ──────────────────────────
            //
            // WHAT THIS IS:
            //   Catches any unexpected exception not already
            //   handled above (e.g. DB connection failure).
            //
            // WHAT WE DO:
            //   1. Log the error
            //   2. Mark TestRun as FAILED in DB
            //   3. Try to send an error event to the browser
            //      so Dev C can show an error message
            //   4. Close the emitter

            log.error("Test {} failed unexpectedly: {}",
                    testRunId, ex.getMessage(), ex);

            updateTestRunStatus(testRun,
                    TestRun.TestRunStatus.FAILED);

            try {
                emitter.send(
                    SseEmitter.event()
                        .name("error")
                        .data(Map.of(
                            "message", "Test failed: "
                                + ex.getMessage())));
                emitter.complete();
            } catch (IOException ioEx) {
                emitter.completeWithError(ioEx);
            }
        }
    }

    // ─────────────────────────────────────────────────────
    // getResult(), getHistory(), getRawResults()
    // Unchanged from Group 1
    // ─────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public TestRunResult getResult(UUID testRunId, User user) {

        TestRun testRun = testRunRepository.findById(testRunId)
                .orElseThrow(() ->
                        new TestRunNotFoundException(testRunId));

        if (!testRun.getUser().getId().equals(user.getId())) {
            log.warn("User '{}' tried to access test '{}' "
                    + "owned by '{}'",
                    user.getUsername(), testRunId,
                    testRun.getUser().getUsername());
            throw new AccessDeniedException();
        }

        List<TestResult> testResults = testResultRepository
                .findByTestRunOrderByIndex(testRun);

        List<SingleRequestResult> results = testResults.stream()
                .map(tr -> SingleRequestResult.builder()
                        .statusCode(tr.getStatusCode())
                        .latencyMs(tr.getLatencyMs())
                        .success(tr.isSuccess())
                        .errorMessage(tr.getErrorMsg())
                        .build())
                .collect(Collectors.toList());

        // in getResult()
        LoadTestSummary summary = results.isEmpty()
                ? emptyLoadTestSummary()      // return zeroed summary, never null
                : calculateSummary(results, testRun);

        return buildTestRunResult(testRun, summary);
    }

    private LoadTestSummary emptyLoadTestSummary() {
        return LoadTestSummary.builder()
                .totalSent(0)
                .successCount(0)
                .failureCount(0)
                .successRatePercent(0.0)
                .avgLatencyMs(0)
                .minLatencyMs(0)
                .maxLatencyMs(0)
                .p50LatencyMs(0)
                .p95LatencyMs(0)
                .p99LatencyMs(0)
                .requestsPerSecond(0.0)
                .errors(List.of())
                .build();
    }


    @Transactional(readOnly = true)
    public List<TestRunSummary> getHistory(User user) {

        List<TestRun> runs = testRunRepository
                .findByUserOrderByStartTimeDesc(user);

        return runs.stream()
                .map(run -> {
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

    @Transactional(readOnly = true)
    public List<TestResult> getRawResults(
            UUID testRunId, User user) {

        TestRun testRun = testRunRepository.findById(testRunId)
                .orElseThrow(() ->
                        new TestRunNotFoundException(testRunId));

        if (!testRun.getUser().getId().equals(user.getId())) {
            throw new AccessDeniedException();
        }

        return testResultRepository
                .findByTestRunOrderByIndex(testRun);
    }

    @Transactional(readOnly = true)
    public TestRun getTestRun(UUID testRunId, User user) {
        return testRunRepository.findByIdAndUser(testRunId, user)
                .orElseThrow(() -> new TestRunNotFoundException(testRunId));
    }

    // ─────────────────────────────────────────────────────
    // PRIVATE HELPERS — unchanged from Group 1
    // ─────────────────────────────────────────────────────

    private LoadTestSummary calculateSummary(
            List<SingleRequestResult> results, TestRun testRun) {

        int totalSent = results.size();
        int successCount = 0;
        int failureCount = 0;
        List<Long> successLatencies = new ArrayList<>();
        Map<Integer, List<SingleRequestResult>> errorGroups =
                new HashMap<>();

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

        Collections.sort(successLatencies);

        long avgLatencyMs = 0;
        long minLatencyMs = 0;
        long maxLatencyMs = 0;
        long p50 = 0, p95 = 0, p99 = 0;

        if (!successLatencies.isEmpty()) {
            int size = successLatencies.size();
            avgLatencyMs = (long) successLatencies.stream()
                    .mapToLong(Long::longValue)
                    .average().orElse(0);
            minLatencyMs = successLatencies.get(0);
            maxLatencyMs = successLatencies.get(size - 1);
            p50 = successLatencies.get(
                    Math.min((int)(size * 0.50), size - 1));
            p95 = successLatencies.get(
                    Math.min((int)(size * 0.95), size - 1));
            p99 = successLatencies.get(
                    Math.min((int)(size * 0.99), size - 1));
        }

        double successRatePercent = totalSent > 0
                ? (successCount * 100.0) / totalSent : 0.0;

        long durationSeconds = ChronoUnit.SECONDS.between(
                testRun.getStartTime(), OffsetDateTime.now());
        double requestsPerSecond = durationSeconds > 0
                ? (double) totalSent / durationSeconds
                : totalSent;

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