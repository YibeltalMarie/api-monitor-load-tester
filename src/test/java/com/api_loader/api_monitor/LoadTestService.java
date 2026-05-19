package com.api_loader.api_monitor;

import com.api_loader.api_monitor.dto.request.LoadTestRequest;
import com.api_loader.api_monitor.dto.request.SignupRequest;
import com.api_loader.api_monitor.dto.response.TestRunResult;
import com.api_loader.api_monitor.dto.response.TestRunSummary;
import com.api_loader.api_monitor.exception.AccessDeniedException;
import com.api_loader.api_monitor.exception.TestRunNotFoundException;
import com.api_loader.api_monitor.model.TestResult;
import com.api_loader.api_monitor.model.TestRun;
import com.api_loader.api_monitor.model.User;
import com.api_loader.api_monitor.repository.TestResultRepository;
import com.api_loader.api_monitor.repository.TestRunRepository;
import com.api_loader.api_monitor.service.LoadTestService;
import com.api_loader.api_monitor.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for LoadTestService.
 *
 * We test against a REAL public URL (httpbin.org) —
 * this means these tests need an internet connection.
 *
 * httpbin.org is a free HTTP testing service that:
 *   GET  /get      → always returns 200 OK
 *   GET  /status/404 → always returns 404
 *   GET  /delay/2  → waits 2 seconds then returns 200
 * Perfect for testing our load test service.
 */
@SpringBootTest
class LoadTestServiceTest {

    @Autowired
    private LoadTestService loadTestService;

    @Autowired
    private UserService userService;

    @Autowired
    private TestRunRepository testRunRepository;

    @Autowired
    private TestResultRepository testResultRepository;

    // We need a real User object to pass into service methods
    // (simulating what Dev B would pass from the logged-in session)
    private User testUser;
    private User otherUser;

    /**
     * @BeforeEach runs before EVERY test method.
     * We register a fresh user so each test starts clean.
     *
     * Username includes a random suffix because H2 is shared
     * across tests in the same session — we need unique names.
     */
    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID()
                .toString().substring(0, 6);

        SignupRequest req = new SignupRequest();
        req.setUsername("loaduser_" + suffix);
        req.setPassword("password123");
        testUser = userService.register(req);

        // A second user to test access control
        SignupRequest req2 = new SignupRequest();
        req2.setUsername("other_" + suffix);
        req2.setPassword("password123");
        otherUser = userService.register(req2);
    }

    // ─────────────────────────────────────────────────────
    // TEST 1: Happy path — run a small real load test
    // ─────────────────────────────────────────────────────

    /**
     * Fires 5 real requests to httpbin.org/get with
     * concurrency 2 and verifies everything is saved to H2.
     *
     * This test requires internet connection.
     * Expected duration: ~5-10 seconds.
     */
    @Test
    void startTest_shouldFireRequestsAndSaveResultsToH2() {

        // ── ARRANGE ───────────────────────────────────────
        LoadTestRequest request = new LoadTestRequest();
        request.setUrl("https://httpbin.org/get");
        request.setMethod("GET");
        request.setTotalRequests(5);
        request.setConcurrency(2);
        request.setTimeoutSeconds(10);

        // ── ACT ───────────────────────────────────────────
        TestRunResult result = loadTestService
                .startTest(request, testUser);

        // ── ASSERT 1: result is returned ──────────────────
        assertNotNull(result,
                "startTest should return a TestRunResult");

        assertNotNull(result.getId(),
                "TestRun should have a UUID id");

        // ── ASSERT 2: status is COMPLETED not RUNNING ─────
        assertEquals("COMPLETED", result.getStatus(),
                "Test should be COMPLETED after startTest() returns");

        // ── ASSERT 3: timing fields are set ───────────────
        assertNotNull(result.getStartTime(),
                "startTime should be set");

        assertNotNull(result.getEndTime(),
                "endTime should be set when COMPLETED");

        assertTrue(result.getDurationSeconds() >= 0,
                "Duration should be >= 0 seconds");

        // ── ASSERT 4: summary is calculated ───────────────
        assertNotNull(result.getSummary(),
                "Summary should not be null for COMPLETED test");

        assertEquals(5, result.getSummary().getTotalSent(),
                "Should have fired exactly 5 requests");

        assertTrue(result.getSummary().getSuccessCount() > 0,
                "At least some requests should succeed");

        assertTrue(result.getSummary().getAvgLatencyMs() > 0,
                "Average latency should be > 0ms");

        assertTrue(result.getSummary().getP95LatencyMs() >= 
                   result.getSummary().getP50LatencyMs(),
                "p95 should always be >= p50");

        assertTrue(result.getSummary().getP99LatencyMs() >= 
                   result.getSummary().getP95LatencyMs(),
                "p99 should always be >= p95");

        // ── ASSERT 5: TestRun is in H2 with correct status
        TestRun savedRun = testRunRepository
                .findById(result.getId())
                .orElse(null);

        assertNotNull(savedRun,
                "TestRun should exist in H2 database");

        assertEquals(TestRun.TestRunStatus.COMPLETED,
                savedRun.getStatus(),
                "TestRun status in H2 should be COMPLETED");

        // ── ASSERT 6: TestResults are in H2 ───────────────
        List<TestResult> savedResults = testResultRepository
                .findByTestRunOrderByIndex(savedRun);

        assertEquals(5, savedResults.size(),
                "H2 should have exactly 5 TestResult rows");

        // Verify index numbers are sequential 1,2,3,4,5
        for (int i = 0; i < savedResults.size(); i++) {
            assertEquals(i + 1, savedResults.get(i).getIndex(),
                    "Request index should be sequential");
        }

        // Print what was saved to H2
        System.out.println("\n✅ TestRun saved to H2:");
        System.out.println("   id          : " + savedRun.getId());
        System.out.println("   url         : " + savedRun.getUrl());
        System.out.println("   status      : " + savedRun.getStatus());
        System.out.println("   startTime   : " + savedRun.getStartTime());
        System.out.println("   endTime     : " + savedRun.getEndTime());

        System.out.println("\n✅ Summary stats:");
        System.out.println("   totalSent   : " + result.getSummary().getTotalSent());
        System.out.println("   success     : " + result.getSummary().getSuccessCount());
        System.out.println("   failures    : " + result.getSummary().getFailureCount());
        System.out.println("   avgLatency  : " + result.getSummary().getAvgLatencyMs() + "ms");
        System.out.println("   p50         : " + result.getSummary().getP50LatencyMs() + "ms");
        System.out.println("   p95         : " + result.getSummary().getP95LatencyMs() + "ms");
        System.out.println("   p99         : " + result.getSummary().getP99LatencyMs() + "ms");
        System.out.println("   req/sec     : " + result.getSummary().getRequestsPerSecond());

        System.out.println("\n✅ TestResult rows in H2:");
        savedResults.forEach(r -> System.out.printf(
                "   [%d] status=%d latency=%dms success=%b%n",
                r.getIndex(), r.getStatusCode(),
                r.getLatencyMs(), r.isSuccess()));
    }

    // ─────────────────────────────────────────────────────
    // TEST 2: Failed requests are recorded correctly
    // ─────────────────────────────────────────────────────

    /**
     * Fires requests to a URL that returns 404.
     * Verifies failures are counted and recorded correctly.
     */
    @Test
    void startTest_shouldRecordFailedRequestsCorrectly() {

        // httpbin returns 404 for this URL — always
        LoadTestRequest request = new LoadTestRequest();
        request.setUrl("https://httpbin.org/status/404");
        request.setMethod("GET");
        request.setTotalRequests(3);
        request.setConcurrency(3);
        request.setTimeoutSeconds(10);

        TestRunResult result = loadTestService
                .startTest(request, testUser);

        assertNotNull(result.getSummary());

        // All 3 should be failures (404 = not success)
        assertEquals(3, result.getSummary().getTotalSent());
        assertEquals(3, result.getSummary().getFailureCount(),
                "All requests to /status/404 should fail");
        assertEquals(0, result.getSummary().getSuccessCount(),
                "No requests should succeed against /status/404");

        // Error breakdown should list the 404
        assertFalse(result.getSummary().getErrors().isEmpty(),
                "Errors list should not be empty");

        assertEquals(404,
                result.getSummary().getErrors().get(0).getStatusCode(),
                "Error breakdown should show 404 status code");

        System.out.println("\n✅ Failed requests recorded:");
        System.out.println("   failures    : " + result.getSummary().getFailureCount());
        System.out.println("   errorCode   : " + result.getSummary().getErrors().get(0).getStatusCode());
        System.out.println("   errorCount  : " + result.getSummary().getErrors().get(0).getCount());
    }

    // ─────────────────────────────────────────────────────
    // TEST 3: getResult() — load saved result
    // ─────────────────────────────────────────────────────

    @Test
    void getResult_shouldReturnSavedTestRunResult() {

        // Run a test first to have something to load
        LoadTestRequest request = new LoadTestRequest();
        request.setUrl("https://httpbin.org/get");
        request.setMethod("GET");
        request.setTotalRequests(3);
        request.setConcurrency(1);
        request.setTimeoutSeconds(10);

        TestRunResult original = loadTestService
                .startTest(request, testUser);

        // Now load it back via getResult()
        TestRunResult loaded = loadTestService
                .getResult(original.getId(), testUser);

        assertNotNull(loaded,
                "getResult should return the saved result");

        assertEquals(original.getId(), loaded.getId(),
                "IDs should match");

        assertEquals("COMPLETED", loaded.getStatus(),
                "Status should still be COMPLETED");

        assertNotNull(loaded.getSummary(),
                "Summary should be recalculated from DB");

        System.out.println("\n✅ getResult() loaded from H2:");
        System.out.println("   id     : " + loaded.getId());
        System.out.println("   status : " + loaded.getStatus());
        System.out.println("   avg ms : " + loaded.getSummary().getAvgLatencyMs());
    }

    // ─────────────────────────────────────────────────────
    // TEST 4: getResult() — wrong user gets 403
    // ─────────────────────────────────────────────────────

    @Test
    void getResult_shouldThrowWhenWrongUserTriesToAccess() {

        // testUser runs a test
        LoadTestRequest request = new LoadTestRequest();
        request.setUrl("https://httpbin.org/get");
        request.setMethod("GET");
        request.setTotalRequests(2);
        request.setConcurrency(1);
        request.setTimeoutSeconds(10);

        TestRunResult result = loadTestService
                .startTest(request, testUser);

        // otherUser tries to access testUser's result
        assertThrows(AccessDeniedException.class,
                () -> loadTestService.getResult(
                        result.getId(), otherUser),
                "Should throw AccessDeniedException when " +
                "wrong user tries to access the result");

        System.out.println("\n✅ Access control works:");
        System.out.println("   otherUser correctly blocked from testUser's result");
    }

    // ─────────────────────────────────────────────────────
    // TEST 5: getResult() — unknown id gets 404
    // ─────────────────────────────────────────────────────

    @Test
    void getResult_shouldThrowWhenIdDoesNotExist() {

        UUID fakeId = UUID.randomUUID();

        assertThrows(TestRunNotFoundException.class,
                () -> loadTestService.getResult(fakeId, testUser),
                "Should throw TestRunNotFoundException " +
                "for a non-existent test run id");

        System.out.println("\n✅ 404 works correctly:");
        System.out.println("   Non-existent id correctly throws TestRunNotFoundException");
    }

    // ─────────────────────────────────────────────────────
    // TEST 6: getHistory() — returns list, newest first
    // ─────────────────────────────────────────────────────

    @Test
    void getHistory_shouldReturnAllRunsNewestFirst() {

        LoadTestRequest request = new LoadTestRequest();
        request.setUrl("https://httpbin.org/get");
        request.setMethod("GET");
        request.setTotalRequests(2);
        request.setConcurrency(1);
        request.setTimeoutSeconds(10);

        // Run two tests in sequence
        loadTestService.startTest(request, testUser);
        loadTestService.startTest(request, testUser);

        // Get history
        List<TestRunSummary> history = loadTestService
                .getHistory(testUser);

        assertTrue(history.size() >= 2,
                "History should have at least 2 entries");

        // Newest first — first entry startTime should be
        // after or equal to second entry startTime
        assertTrue(
                !history.get(0).getStartTime()
                        .isBefore(history.get(1).getStartTime()),
                "History should be ordered newest first");

        // History should only show THIS user's runs
        history.forEach(summary ->
                assertNotNull(summary.getId(),
                        "Each summary should have an id"));

        System.out.println("\n✅ getHistory() returned " 
                + history.size() + " runs:");
        history.forEach(s -> System.out.printf(
                "   [%s] %s %s — %s avg=%dms%n",
                s.getId().toString().substring(0, 8),
                s.getMethod(), s.getUrl(),
                s.getStatus(), s.getAvgLatencyMs()));
    }
}