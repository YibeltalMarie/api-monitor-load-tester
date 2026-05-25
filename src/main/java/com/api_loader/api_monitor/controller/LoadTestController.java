package com.api_loader.api_monitor.controller;

import com.api_loader.api_monitor.dto.request.LoadTestRequest;
// import com.api_loader.api_monitor.dto.response.TestRequestResult;
import com.api_loader.api_monitor.dto.response.TestRunResult;
import com.api_loader.api_monitor.dto.response.TestRunSummary;
import com.api_loader.api_monitor.model.TestResult;
import com.api_loader.api_monitor.model.TestRun;
import com.api_loader.api_monitor.model.User;

import com.api_loader.api_monitor.service.LoadTestService;
import com.api_loader.api_monitor.service.UserService;

import jakarta.validation.Valid;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/load-test")
public class LoadTestController {

    private final LoadTestService loadTestService;
    private final UserService userService;

    public LoadTestController(LoadTestService loadTestService,
                              UserService userService) {
        this.loadTestService = loadTestService;
        this.userService = userService;
    }

    // ── Start a test ────────────────────────────────────────────────────────
    @PostMapping("/run")
    public ResponseEntity<Map<String, UUID>> startTest(
            @Valid @RequestBody LoadTestRequest request,
            Authentication authentication) {

        User user = getUser(authentication);
        TestRun testRun = loadTestService.saveNewTestRun(request, user);  // CHANGED

        return ResponseEntity.ok(Map.of("testRunId", testRun.getId()));
    }



    // ── Get full result of one test 
    @GetMapping("/{id}")
    public ResponseEntity<TestRunResult> getResult(
            @PathVariable UUID id,
            Authentication authentication) {

        User user = getUser(authentication);
        TestRunResult result = loadTestService.getResult(id, user);
        return ResponseEntity.ok(result);
    }

    // ── Get history of all past tests ───────────────────────────────────────
    @GetMapping("/history")
    public ResponseEntity<List<TestRunSummary>> getHistory(
            Authentication authentication) {

        User user = getUser(authentication);
        List<TestRunSummary> history = loadTestService.getHistory(user);
        return ResponseEntity.ok(history);
    }

    // ── Get raw results for charts ──────────────────────────────────────────
    // Returns the list of individual request results for a test run.
    // Dev C feeds this into Chart.js for the scatter/line chart on
    // load-test-result.html — one point per request.
    @GetMapping("/{id}/results")
    public ResponseEntity<List<Map<String, Object>>> getRawResults(
            @PathVariable UUID id,
            Authentication authentication) {

        User user = getUser(authentication);
        List<TestResult> results = loadTestService.getRawResults(id, user);

        // Map to plain objects — no JPA relations, no lazy loading issues
        List<Map<String, Object>> response = results.stream()
                .map(r -> {
                    Map<String, Object> item = new java.util.LinkedHashMap<>();
                    item.put("index",      r.getIndex());
                    item.put("latencyMs",  r.getLatencyMs());
                    item.put("statusCode", r.getStatusCode());
                    item.put("success",    r.isSuccess());
                    item.put("errorMsg",   r.getErrorMsg());
                    return item;
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(response);
    }

    // ── Live results SSE stream ─────────────────────────────────────────────
    // Opens a Server-Sent Events stream for a running test.
    // Dev C connects using EventSource — the browser keeps this connection
    // open and receives one event per completed request in real time.
    //
    // FLOW:
    //   1. Dev C opens EventSource("/api/load-test/{id}/stream")
    //   2. This method creates a SseEmitter and passes it to Dev A's service
    //   3. Dev A's service pushes one event per completed request
    //   4. When the test finishes Dev A sends a "done" event and closes
    //   5. Dev C receives "done", closes EventSource, shows final summary
    //
    // TIMEOUT:
    //   5 minutes — if a test runs longer than this the stream closes.
    //   The test itself keeps running but the live page stops updating.
    //   Increase this if you expect very long tests.

        

    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamTest(
            @PathVariable UUID id,
            Authentication authentication) {

        User user = getUser(authentication);
        TestRun testRun = loadTestService.getTestRun(id, user);
        LoadTestRequest request = loadTestService.buildRequestFromTestRun(testRun);

        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L);

        emitter.onTimeout(() -> emitter.complete());
        emitter.onError((ex) -> emitter.complete());

        loadTestService.startTest(request, user, testRun, emitter);

        return emitter;
    }

    // ── Helper — get User entity from Spring Security Authentication ─────────
    private User getUser(Authentication authentication) {
        return userService.findByUsername(authentication.getName());
    }
}