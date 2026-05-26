package com.api_loader.api_monitor.controller;

import com.api_loader.api_monitor.dto.request.AddEndpointRequest;
import com.api_loader.api_monitor.dto.response.EndpointHistoryResponse;
import com.api_loader.api_monitor.dto.response.EndpointStatusResponse;
import com.api_loader.api_monitor.model.MonitoredEndpoint;
import com.api_loader.api_monitor.model.User;
import com.api_loader.api_monitor.service.MonitorService;
import com.api_loader.api_monitor.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * MonitorController — REST endpoints for the API monitor feature.
 *
 * BASE URL: /api/monitor
 *
 * All endpoints require authentication — Spring Security
 * blocks unauthenticated requests before they reach here.
 *
 * ERROR HANDLING:
 *   This controller does NOT use try/catch.
 *   All exceptions (EndpointNotFoundException, AccessDeniedException,
 *   validation errors) bubble up to GlobalExceptionHandler
 *   which maps them to the correct HTTP status and JSON shape.
 */
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

    // ── GET /api/monitor ────────────────────────────────────────────────────
    // Returns all endpoints for the logged-in user with latest UP/DOWN status.
    // Dev C uses this to render the status board on monitor-list.html.
    @GetMapping
    public ResponseEntity<List<EndpointStatusResponse>> getEndpoints(
            Authentication authentication) {

        User user = getUser(authentication);
        List<EndpointStatusResponse> endpoints = monitorService.getEndpoints(user);
        return ResponseEntity.ok(endpoints);
    }

    // ── POST /api/monitor ───────────────────────────────────────────────────
    // Adds a new monitored endpoint.
    // @Valid triggers AddEndpointRequest validation — 400 if invalid.
    // Returns 201 Created with the new endpoint's ID.

    @PostMapping
    public ResponseEntity<EndpointStatusResponse> addEndpoint(
            @Valid @RequestBody AddEndpointRequest request,
            Authentication authentication) {

        User user = getUser(authentication);
        MonitoredEndpoint saved = monitorService.addEndpoint(request, user);

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
                .build()
        );
    }
    // ── DELETE /api/monitor/{id} ────────────────────────────────────────────
    // Deletes an endpoint and all its MonitorResult history.
    // 404 if not found. 403 if it belongs to another user.
    // Returns 204 No Content on success — no body needed.
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteEndpoint(
            @PathVariable Long id,
            Authentication authentication) {

        User user = getUser(authentication);
        monitorService.deleteEndpoint(id, user);
        return ResponseEntity.noContent().build();
    }

    // ── PATCH /api/monitor/{id}/toggle ──────────────────────────────────────
    // Flips enabled on/off for an endpoint.
    // Returns the updated EndpointStatusResponse so Dev C can
    // immediately update the toggle switch state on the UI.
    @PatchMapping("/{id}/toggle")
    public ResponseEntity<EndpointStatusResponse> toggleEndpoint(
            @PathVariable Long id,
            Authentication authentication) {

        User user = getUser(authentication);
        EndpointStatusResponse updated = monitorService.toggleEndpoint(id, user);
        return ResponseEntity.ok(updated);
    }

    // ── GET /api/monitor/{id}/results?hours=24 ──────────────────────────────
    // Returns uptime stats + raw result list for the detail page chart.
    // hours param defaults to 24 if not provided.
    // Dev C uses uptimePercent for the stat card,
    // and results list for the Chart.js latency line chart.
    @GetMapping("/{id}/results")
    public ResponseEntity<EndpointHistoryResponse> getHistory(
            @PathVariable Long id,
            @RequestParam(defaultValue = "24") int hours,
            Authentication authentication) {

        User user = getUser(authentication);
        EndpointHistoryResponse history = monitorService.getHistory(id, user, hours);
        return ResponseEntity.ok(history);
    }

    // ── Helper ──────────────────────────────────────────────────────────────
    private User getUser(Authentication authentication) {
        return userService.findByUsername(authentication.getName());
    }
}