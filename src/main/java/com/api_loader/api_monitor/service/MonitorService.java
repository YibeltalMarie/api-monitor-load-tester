package com.api_loader.api_monitor.service;

import com.api_loader.api_monitor.dto.request.AddEndpointRequest;
import com.api_loader.api_monitor.dto.response.EndpointHistoryResponse;
import com.api_loader.api_monitor.dto.response.EndpointStatusResponse;
import com.api_loader.api_monitor.dto.response.SingleRequestResult;
import com.api_loader.api_monitor.exception.EndpointNotFoundException;
import com.api_loader.api_monitor.model.MonitorResult;
import com.api_loader.api_monitor.model.MonitoredEndpoint;
import com.api_loader.api_monitor.model.User;
import com.api_loader.api_monitor.repository.MonitorResultRepository;
import com.api_loader.api_monitor.repository.MonitoredEndpointRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MonitorService {

    private final MonitoredEndpointRepository endpointRepository;
    private final MonitorResultRepository     monitorResultRepository;
    private final HttpClientService           httpClientService;

    // ─────────────────────────────────────────────────────────────────
    // SSE BROADCASTER
    // ─────────────────────────────────────────────────────────────────

    /**
     * WHAT THIS IS:
     *   A map of userId → list of active SseEmitters.
     *
     *   When a browser opens the monitor stream, we register
     *   its emitter here under the user's ID.
     *   When the scheduler completes a check for that user's
     *   endpoints, we push the updated status to all their
     *   active emitters instantly.
     *
     * WHY ConcurrentHashMap and CopyOnWriteArrayList:
     *   Multiple threads read and write this map at the same time:
     *     - The HTTP request thread adds emitters (browser connects)
     *     - The scheduler thread reads and writes emitters (pushes data)
     *     - onCompletion callbacks remove emitters (browser disconnects)
     *   These thread-safe collections prevent race conditions.
     *
     * WHY keyed by UUID (userId) not username:
     *   UUID is the primary key — guaranteed unique.
     *   If two browsers are open for the same user, both get updates.
     */
    private final Map<UUID, CopyOnWriteArrayList<SseEmitter>>
            userEmitters = new ConcurrentHashMap<>();

    /**
     * Registers a new SseEmitter for a user.
     *
     * FLOW:
     *   1. Browser calls GET /api/monitor/stream
     *   2. MonitorController calls this method
     *   3. We create a list for this user if one doesn't exist
     *   4. Add the emitter to the list
     *   5. Register cleanup callbacks so the emitter removes
     *      itself when the browser disconnects or times out
     *   6. Send current status immediately on connect
     *   7. Return the emitter to the controller
     *      (Spring keeps the HTTP connection open via the emitter)
     *
     * @param user     the logged-in user
     * @return         the emitter — controller returns this to Spring
     */
    public SseEmitter registerEmitter(User user) {

        // 10-minute timeout — browser EventSource reconnects automatically
        SseEmitter emitter = new SseEmitter(10 * 60 * 1000L);

        UUID userId = user.getId();

        // Add this emitter to the user's emitter list
        userEmitters
                .computeIfAbsent(userId,
                        k -> new CopyOnWriteArrayList<>())
                .add(emitter);

        log.debug("SSE emitter registered for user '{}' (total: {})",
                user.getUsername(),
                userEmitters.get(userId).size());

        // ── Cleanup callbacks ──────────────────────────────────────────
        // These run automatically when the browser disconnects,
        // the emitter times out, or an error occurs.
        // Without these, dead emitters accumulate in the map forever.

        Runnable cleanup = () -> {
            CopyOnWriteArrayList<SseEmitter> list =
                    userEmitters.get(userId);
            if (list != null) {
                list.remove(emitter);
                log.debug("SSE emitter removed for user '{}' (remaining: {})",
                        user.getUsername(), list.size());
            }
        };

        emitter.onCompletion(cleanup);  // browser closed connection
        emitter.onTimeout(cleanup);     // 10-min timeout expired
        emitter.onError(ex -> cleanup.run()); // connection error

        // ── Send current status immediately on connect ─────────────────
        // The browser sees fresh data instantly without waiting
        // for the next scheduler tick.
        try {
            List<EndpointStatusResponse> current =
                    getEndpoints(user);
            emitter.send(SseEmitter.event()
                    .name("status")
                    .data(current));
        } catch (IOException ex) {
            log.warn("Could not send initial status to user '{}': {}",
                    user.getUsername(), ex.getMessage());
            emitter.completeWithError(ex);
        }

        return emitter;
    }

    /**
     * Pushes updated status to all active emitters for a user.
     *
     * WHAT THIS IS:
     *   Called from checkEndpoint() after every successful ping.
     *   If the user has any open browser tabs watching the
     *   monitor page, they all receive the update instantly.
     *
     * WHY we push the full endpoint list not just the one that changed:
     *   Simpler for Dev C — one event handler re-renders the whole
     *   status board. No need to find and update just one row.
     *
     * WHY we remove emitters that fail to send:
     *   If send() throws IOException, the browser has disconnected
     *   but the cleanup callback hasn't fired yet (race condition).
     *   We remove it manually to keep the list clean.
     *
     * @param user      the user whose emitters to push to
     * @param endpoints the fresh status list to send
     */
    private void pushToEmitters(
            User user, List<EndpointStatusResponse> endpoints) {

        CopyOnWriteArrayList<SseEmitter> emitters =
                userEmitters.get(user.getId());

        if (emitters == null || emitters.isEmpty()) {
            return; // no browsers watching — nothing to push
        }

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("status")
                        .data(endpoints));
            } catch (IOException ex) {
                // Browser disconnected — remove this dead emitter
                log.debug("Removing dead emitter for user '{}'",
                        user.getUsername());
                emitters.remove(emitter);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // addEndpoint()
    // ─────────────────────────────────────────────────────────────────

    @Transactional
    public MonitoredEndpoint addEndpoint(
            AddEndpointRequest request, User user) {

        MonitoredEndpoint endpoint = MonitoredEndpoint.builder()
                .user(user)
                .url(request.getUrl())
                .method(request.getMethod().toUpperCase())
                .intervalSeconds(request.getIntervalSeconds())
                .expectedStatusCode(request.getExpectedStatusCode())
                .build();

        MonitoredEndpoint saved = endpointRepository.save(endpoint);

        log.info("User '{}' added endpoint {} ({})",
                user.getUsername(), saved.getId(), saved.getUrl());

        return saved;
    }

    // ─────────────────────────────────────────────────────────────────
    // getEndpoints()
    // ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<EndpointStatusResponse> getEndpoints(User user) {

        return endpointRepository.findByUser(user)
                .stream()
                .map(this::buildStatusResponse)
                .collect(Collectors.toList());
    }

    // ─────────────────────────────────────────────────────────────────
    // deleteEndpoint()
    // ─────────────────────────────────────────────────────────────────

    @Transactional
    public void deleteEndpoint(Long endpointId, User user) {

        MonitoredEndpoint endpoint = endpointRepository
                .findByIdAndUser(endpointId, user)
                .orElseThrow(() ->
                        new EndpointNotFoundException(endpointId));

        monitorResultRepository.deleteByEndpoint(endpoint);
        endpointRepository.delete(endpoint);

        log.info("User '{}' deleted endpoint {} ({})",
                user.getUsername(), endpointId, endpoint.getUrl());
    }

    // ─────────────────────────────────────────────────────────────────
    // toggleEndpoint()
    // ─────────────────────────────────────────────────────────────────

    @Transactional
    public EndpointStatusResponse toggleEndpoint(
            Long endpointId, User user) {

        MonitoredEndpoint endpoint = endpointRepository
                .findByIdAndUser(endpointId, user)
                .orElseThrow(() ->
                        new EndpointNotFoundException(endpointId));

        endpoint.setEnabled(!endpoint.isEnabled());
        endpointRepository.save(endpoint);

        log.info("User '{}' {} endpoint {} ({})",
                user.getUsername(),
                endpoint.isEnabled() ? "enabled" : "disabled",
                endpointId, endpoint.getUrl());

        return buildStatusResponse(endpoint);
    }

    // ─────────────────────────────────────────────────────────────────
    // runScheduledChecks() — 10s heartbeat
    // ─────────────────────────────────────────────────────────────────

    /**
     * Runs every 10 seconds. Checks each enabled endpoint if its
     * own intervalSeconds has elapsed since its last ping.
     *
     * After checking an endpoint, if the endpoint's owner has
     * any active SSE connections (browser tabs open), pushes
     * the updated status list to them instantly.
     */
    @Scheduled(fixedDelay = 10_000)
    @Transactional
    public void runScheduledChecks() {

        List<MonitoredEndpoint> activeEndpoints =
                endpointRepository.findByEnabledTrue();

        if (activeEndpoints.isEmpty()) {
            return;
        }

        log.debug("Scheduler tick: {} active endpoint(s)",
                activeEndpoints.size());

        for (MonitoredEndpoint endpoint : activeEndpoints) {
            try {
                boolean checked = checkEndpoint(endpoint);

                // ── Push live update if a check actually ran ───────────
                // Only push when a real ping happened — not on skip.
                // This avoids flooding the browser with identical updates.
                if (checked) {
                    User owner = endpoint.getUser();

                    // Push updated status list to all open browser tabs
                    // of this endpoint's owner
                    List<EndpointStatusResponse> updated =
                            getEndpoints(owner);
                    pushToEmitters(owner, updated);
                }

            } catch (Exception ex) {
                log.error("Scheduler error for endpoint {} ({}): {}",
                        endpoint.getId(),
                        endpoint.getUrl(),
                        ex.getMessage());
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // checkEndpoint() — returns true if a ping actually ran
    // ─────────────────────────────────────────────────────────────────

    /**
     * Checks one endpoint if its interval has elapsed.
     *
     * Returns true  → a ping was fired and result saved
     * Returns false → interval hasn't elapsed yet, skipped
     *
     * WHY boolean return:
     *   The scheduler needs to know whether a real check happened
     *   so it knows whether to push an SSE update.
     *   If we skipped, there's nothing new to push.
     */
    private boolean checkEndpoint(MonitoredEndpoint endpoint) {

        // ── Check if interval has elapsed ─────────────────────────────
        Optional<MonitorResult> previousResult =
                monitorResultRepository
                        .findTopByEndpointOrderByCheckedAtDesc(endpoint);

        if (previousResult.isPresent()) {
            long secondsSinceLast = ChronoUnit.SECONDS.between(
                    previousResult.get().getCheckedAt(),
                    OffsetDateTime.now());

            if (secondsSinceLast < endpoint.getIntervalSeconds()) {
                log.debug("Skipping endpoint {} — {}s elapsed, interval={}s",
                        endpoint.getId(),
                        secondsSinceLast,
                        endpoint.getIntervalSeconds());
                return false; // ← skipped, not checked
            }
        }

        Boolean previouslyUp = previousResult
                .map(MonitorResult::getIsUp)
                .orElse(null);

        // ── Fire the request ───────────────────────────────────────────
        int timeoutSeconds = Math.min(
                endpoint.getIntervalSeconds(), 30);

        SingleRequestResult result = httpClientService.fire(
                endpoint.getUrl(),
                endpoint.getMethod(),
                null, null,
                timeoutSeconds);

        // ── Determine isUp ─────────────────────────────────────────────
        boolean isUp = result.getStatusCode() != 0
                && result.getStatusCode()
                        == endpoint.getExpectedStatusCode();

        // ── Save MonitorResult ─────────────────────────────────────────
        MonitorResult monitorResult = MonitorResult.builder()
                .endpoint(endpoint)
                .isUp(isUp)
                .statusCode(result.getStatusCode() == 0
                        ? null : result.getStatusCode())
                .latencyMs(result.getLatencyMs())
                .errorMsg(isUp ? null : result.getErrorMessage())
                .build();

        monitorResultRepository.save(monitorResult);

        log.debug("Endpoint {} ({}): {} — {}ms status={}",
                endpoint.getId(), endpoint.getUrl(),
                isUp ? "UP" : "DOWN",
                result.getLatencyMs(),
                result.getStatusCode());

        // ── Detect status change ───────────────────────────────────────
        if (previouslyUp != null && previouslyUp != isUp) {
            if (!isUp) {
                log.warn("INCIDENT [DOWN] endpoint {} ({}) — status={} error={}",
                        endpoint.getId(), endpoint.getUrl(),
                        result.getStatusCode(),
                        result.getErrorMessage());
            } else {
                log.info("INCIDENT [RECOVERED] endpoint {} ({}) — status={} latency={}ms",
                        endpoint.getId(), endpoint.getUrl(),
                        result.getStatusCode(),
                        result.getLatencyMs());
            }
        }

        return true; // ← a real check happened
    }

    // ─────────────────────────────────────────────────────────────────
    // getHistory()
    // ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public EndpointHistoryResponse getHistory(
            Long endpointId, User user, int hours) {

        MonitoredEndpoint endpoint = endpointRepository
                .findByIdAndUser(endpointId, user)
                .orElseThrow(() ->
                        new EndpointNotFoundException(endpointId));

        OffsetDateTime windowStart =
                OffsetDateTime.now().minusHours(hours);

        long totalChecks = monitorResultRepository
                .countByEndpointAndCheckedAtAfter(endpoint, windowStart);

        long upCount = monitorResultRepository
                .countByEndpointAndIsUpTrueAndCheckedAtAfter(
                        endpoint, windowStart);

        long downCount = totalChecks - upCount;

        Double uptimePercent = totalChecks == 0 ? null
                : Math.round((double) upCount / totalChecks
                        * 10_000.0) / 100.0;

        List<MonitorResult> results = monitorResultRepository
                .findByEndpointAndCheckedAtAfterOrderByCheckedAtAsc(
                        endpoint, windowStart);

        List<EndpointHistoryResponse.CheckPointResult> checkPoints =
                results.stream()
                        .map(r -> EndpointHistoryResponse
                                .CheckPointResult.builder()
                                .checkedAt(r.getCheckedAt())
                                .isUp(r.getIsUp())
                                .latencyMs(r.getLatencyMs())
                                .statusCode(r.getStatusCode())
                                .errorMsg(r.getErrorMsg())
                                .build())
                        .collect(Collectors.toList());

        return EndpointHistoryResponse.builder()
                .endpointId(endpoint.getId())
                .url(endpoint.getUrl())
                .method(endpoint.getMethod())
                .hoursWindow(hours)
                .totalChecks(totalChecks)
                .upCount(upCount)
                .downCount(downCount)
                .uptimePercent(uptimePercent)
                .results(checkPoints)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────────

    private EndpointStatusResponse buildStatusResponse(
            MonitoredEndpoint endpoint) {

        Optional<MonitorResult> latest =
                monitorResultRepository
                        .findTopByEndpointOrderByCheckedAtDesc(endpoint);

        String currentStatus = latest
                .map(r -> r.getIsUp() ? "UP" : "DOWN")
                .orElse("UNKNOWN");

        Double uptimePercent24h =
                calculateUptimePercent(endpoint, 24);

        return EndpointStatusResponse.builder()
                .id(endpoint.getId())
                .url(endpoint.getUrl())
                .method(endpoint.getMethod())
                .intervalSeconds(endpoint.getIntervalSeconds())
                .expectedStatusCode(endpoint.getExpectedStatusCode())
                .enabled(endpoint.isEnabled())
                .up(latest.map(MonitorResult::getIsUp).orElse(null))
                .lastCheckedAt(latest
                        .map(MonitorResult::getCheckedAt).orElse(null))
                .lastLatencyMs(latest
                        .map(MonitorResult::getLatencyMs).orElse(null))
                .lastStatusCode(latest
                        .map(MonitorResult::getStatusCode).orElse(null))
                .lastErrorMsg(latest
                        .map(MonitorResult::getErrorMsg).orElse(null))
                .currentStatus(currentStatus)
                .uptimePercent24h(uptimePercent24h)
                .build();
    }

    private Double calculateUptimePercent(
            MonitoredEndpoint endpoint, int hours) {

        OffsetDateTime windowStart =
                OffsetDateTime.now().minusHours(hours);

        long total = monitorResultRepository
                .countByEndpointAndCheckedAtAfter(endpoint, windowStart);

        if (total == 0) return null;

        long up = monitorResultRepository
                .countByEndpointAndIsUpTrueAndCheckedAtAfter(
                        endpoint, windowStart);

        return Math.round((double) up / total * 10_000.0) / 100.0;
    }
}
