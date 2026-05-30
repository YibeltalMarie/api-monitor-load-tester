package com.api_loader.api_monitor.controller;

import com.api_loader.api_monitor.dto.request.AddEndpointRequest;
import com.api_loader.api_monitor.dto.response.EndpointHistoryResponse;
import com.api_loader.api_monitor.dto.response.EndpointStatusResponse;
import com.api_loader.api_monitor.model.MonitoredEndpoint;
import com.api_loader.api_monitor.model.User;
import com.api_loader.api_monitor.service.MonitorService;
import com.api_loader.api_monitor.service.UserService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/monitor")
public class MonitorController {

    private final MonitorService monitorService;
    private final UserService    userService;

    public MonitorController(MonitorService monitorService,
                             UserService userService) {
        this.monitorService = monitorService;
        this.userService    = userService;
    }

    // ── GET /api/monitor ────────────────────────────────────────────
    @GetMapping
    public ResponseEntity<List<EndpointStatusResponse>> getEndpoints(
            Authentication authentication) {

        User user = getUser(authentication);
        return ResponseEntity.ok(monitorService.getEndpoints(user));
    }

    // ── POST /api/monitor ───────────────────────────────────────────
    @PostMapping
    public ResponseEntity<EndpointStatusResponse> addEndpoint(
            @Valid @RequestBody AddEndpointRequest request,
            Authentication authentication) {

        User user = getUser(authentication);
        MonitoredEndpoint saved =
                monitorService.addEndpoint(request, user);

        return ResponseEntity.status(201).body(
            EndpointStatusResponse.builder()
                .id(saved.getId())
                .url(saved.getUrl())
                .method(saved.getMethod())
                .intervalSeconds(saved.getIntervalSeconds())
                .expectedStatusCode(saved.getExpectedStatusCode())
                .enabled(saved.isEnabled())
                .up(null)
                .lastCheckedAt(null)
                .lastLatencyMs(null)
                .lastStatusCode(null)
                .lastErrorMsg(null)
                .currentStatus("UNKNOWN")
                .uptimePercent24h(null)
                .build()
        );
    }

    // ── DELETE /api/monitor/{id} ────────────────────────────────────
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteEndpoint(
            @PathVariable Long id,
            Authentication authentication) {

        User user = getUser(authentication);
        monitorService.deleteEndpoint(id, user);
        return ResponseEntity.noContent().build();
    }

    // ── PATCH /api/monitor/{id}/toggle ──────────────────────────────
    @PatchMapping("/{id}/toggle")
    public ResponseEntity<EndpointStatusResponse> toggleEndpoint(
            @PathVariable Long id,
            Authentication authentication) {

        User user = getUser(authentication);
        return ResponseEntity.ok(
                monitorService.toggleEndpoint(id, user));
    }

    // ── GET /api/monitor/{id}/results?hours=24 ──────────────────────
    @GetMapping("/{id}/results")
    public ResponseEntity<EndpointHistoryResponse> getHistory(
            @PathVariable Long id,
            @RequestParam(defaultValue = "24") int hours,
            Authentication authentication) {

        User user = getUser(authentication);
        return ResponseEntity.ok(
                monitorService.getHistory(id, user, hours));
    }

    // ── GET /api/monitor/stream ─────────────────────────────────────
    //
    // WHAT THIS IS:
    //   SSE stream endpoint. The browser connects once and
    //   receives real-time updates whenever a monitor check
    //   completes — pushed directly from the scheduler.
    //
    // HOW IT WORKS NOW (broadcaster pattern):
    //   1. Browser opens EventSource('/api/monitor/stream')
    //   2. This method calls monitorService.registerEmitter(user)
    //   3. MonitorService adds the emitter to a ConcurrentHashMap
    //      keyed by userId
    //   4. Sends current status immediately on connect
    //   5. Every time the scheduler runs checkEndpoint() and a
    //      real ping happens → MonitorService calls pushToEmitters()
    //      which sends the updated status list to all open tabs
    //      for that user instantly
    //   6. Browser receives event → re-renders the status board
    //
    // WHAT DEV C DOES:
    //   const source = new EventSource('/api/monitor/stream');
    //   source.addEventListener('status', (e) => {
    //     const endpoints = JSON.parse(e.data);
    //     renderStatusBoard(endpoints);
    //   });
    //   source.onerror = () => source.close();
    //
    // WHY this is better than the polling loop approach:
    //   Old: browser waited up to 15s for any update
    //   New: browser gets the update within milliseconds
    //        of the check completing
    @GetMapping(value = "/stream",
                produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamStatus(Authentication authentication) {

        User user = getUser(authentication);

        // MonitorService handles everything:
        // - creates the emitter with 10-min timeout
        // - registers cleanup callbacks
        // - sends current status immediately
        // - adds emitter to broadcaster map
        return monitorService.registerEmitter(user);
    }

    // ── Helper ──────────────────────────────────────────────────────
    private User getUser(Authentication authentication) {
        return userService.findByUsername(authentication.getName());
    }
}