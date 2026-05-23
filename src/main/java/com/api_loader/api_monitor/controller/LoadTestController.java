package com.api_loader.api_monitor.controller;

import com.api_loader.api_monitor.dto.LoadTestRequest;
import com.api_loader.api_monitor.dto.TestRunResult;
import com.api_loader.api_monitor.dto.TestRunSummary;
import com.api_loader.api_monitor.model.User;
import com.api_loader.api_monitor.service.LoadTestService;
import com.api_loader.api_monitor.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    // ── Start a test
    @PostMapping("/run")
    public ResponseEntity<Map<String, UUID>> startTest(
            @Valid @RequestBody LoadTestRequest request,
            Authentication authentication) {

        User user = getUser(authentication);
        UUID testRunId = loadTestService.startTest(request, user);

        // Returns { "testRunId": "a1b2c3d4-..." }
        // Dev C reads this and navigates to /load-test/{id}/live
        return ResponseEntity.ok(Map.of("testRunId", testRunId));
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

    // ── Get history of all past tests 
    @GetMapping("/history")
    public ResponseEntity<List<TestRunSummary>> getHistory(
            Authentication authentication) {

        User user = getUser(authentication);
        List<TestRunSummary> history = loadTestService.getHistory(user);
        return ResponseEntity.ok(history);
    }

    // ── Helper — get User entity from Spring Security Authentication 
    // Authentication gives us the username string.
    // We load the full User entity from UserService so we can pass it to Dev A.
    private User getUser(Authentication authentication) {
        return (User) userService.loadUserByUsername(authentication.getName());
    }
}