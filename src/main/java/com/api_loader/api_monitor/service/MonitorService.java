package com.api_loader.api_monitor.service;

import com.api_loader.api_monitor.dto.request.AddEndpointRequest;
import com.api_loader.api_monitor.dto.response.EndpointHistoryResponse;
import com.api_loader.api_monitor.dto.response.EndpointStatusResponse;
import com.api_loader.api_monitor.dto.response.SingleRequestResult;
import com.api_loader.api_monitor.exception.AccessDeniedException;
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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MonitorService {

    private final MonitoredEndpointRepository endpointRepository;
    private final MonitorResultRepository     monitorResultRepository;
    private final HttpClientService           httpClientService;

    // ─────────────────────────────────────────────────────────────────
    // Step 1 — addEndpoint()
    // ─────────────────────────────────────────────────────────────────

    /**
     * Saves a new monitored endpoint to the DB.
     *
     * FLOW:
     *   Dev B receives POST /api/monitor with AddEndpointRequest body
     *   Dev B calls this method with the request and logged-in user
     *   We build a MonitoredEndpoint entity and save it
     *   We return the saved entity so Dev B can return its ID to Dev C
     *
     * WHY @Transactional:
     *   We write to the DB. If save() fails partway through,
     *   the transaction rolls back automatically — no partial data.
     *
     * NOTE: MonitoredEndpoint.@PrePersist sets:
     *   createdAt = now()
     *   enabled   = true
     *   expectedStatusCode defaults to 200 if 0 was passed
     *
     * @param request  validated DTO from Dev B
     * @param user     the logged-in user who owns this endpoint
     * @return         the saved MonitoredEndpoint with its generated ID
     */
    @Transactional
    public MonitoredEndpoint addEndpoint(AddEndpointRequest request, User user) {

        MonitoredEndpoint endpoint = MonitoredEndpoint.builder()
                .user(user)
                .url(request.getUrl())
                .method(request.getMethod().toUpperCase())
                .intervalSeconds(request.getIntervalSeconds())
                .expectedStatusCode(request.getExpectedStatusCode())
                .build();
        // enabled and createdAt are set by @PrePersist

        MonitoredEndpoint saved = endpointRepository.save(endpoint);

        log.info("User '{}' added monitor endpoint {} ({})",
                user.getUsername(), saved.getId(), saved.getUrl());

        return saved;
    }

    // ─────────────────────────────────────────────────────────────────
    // Step 2 — getEndpoints()
    // ─────────────────────────────────────────────────────────────────

    /**
     * Loads all endpoints for a user, each with its latest check result.
     *
     * FLOW:
     *   1. Load all MonitoredEndpoint rows for this user
     *   2. For each endpoint, query the latest MonitorResult
     *   3. Build one EndpointStatusResponse per endpoint
     *   4. Return the list — Dev B serves it as JSON to Dev C
     *
     * WHY readOnly = true:
     *   We only read from the DB here — no writes.
     *   readOnly = true is a performance hint to JPA:
     *   skip dirty checking (comparing entities to detect changes),
     *   which is wasted work when we're only reading.
     *
     * WHY the latest result fields are nullable:
     *   A brand new endpoint has no MonitorResult rows yet.
     *   up = null → Dev C shows a grey "PENDING" badge.
     *
     * @param user  the logged-in user
     * @return      list of endpoint rows with current status
     */
    @Transactional(readOnly = true)
    public List<EndpointStatusResponse> getEndpoints(User user) {

        List<MonitoredEndpoint> endpoints = endpointRepository.findByUser(user);

        return endpoints.stream()
                .map(endpoint -> {

                    // Get the latest MonitorResult for this endpoint
                    // Empty if the endpoint has never been checked yet
                    Optional<MonitorResult> latest =
                            monitorResultRepository
                                    .findTopByEndpointOrderByCheckedAtDesc(endpoint);

                    return EndpointStatusResponse.builder()
                            .id(endpoint.getId())
                            .url(endpoint.getUrl())
                            .method(endpoint.getMethod())
                            .intervalSeconds(endpoint.getIntervalSeconds())
                            .expectedStatusCode(endpoint.getExpectedStatusCode())
                            .enabled(endpoint.isEnabled())
                            // latest result fields — all null if never checked
                            .up(latest.map(MonitorResult::getIsUp).orElse(null))
                            .lastCheckedAt(latest.map(MonitorResult::getCheckedAt).orElse(null))
                            .lastLatencyMs(latest.map(MonitorResult::getLatencyMs).orElse(null))
                            .lastStatusCode(latest.map(MonitorResult::getStatusCode).orElse(null))
                            .lastErrorMsg(latest.map(MonitorResult::getErrorMsg).orElse(null))
                            .build();
                })
                .collect(Collectors.toList());
    }

    // ─────────────────────────────────────────────────────────────────
    // Step 3 — deleteEndpoint() and toggleEndpoint()
    // ─────────────────────────────────────────────────────────────────

    /**
     * Deletes an endpoint and all its MonitorResult history.
     *
     * FLOW:
     *   1. Find the endpoint by ID scoped to this user
     *   2. If not found → EndpointNotFoundException (404)
     *   3. If found but belongs to another user → AccessDeniedException (403)
     *      (findByIdAndUser handles both in one query)
     *   4. Delete the entity — cascade removes all MonitorResult rows
     *
     * WHY findByIdAndUser instead of findById + ownership check:
     *   One DB query instead of two. The ownership check is done
     *   at the SQL level — more efficient and cleaner.
     *   If the ID doesn't exist OR belongs to another user,
     *   the Optional is empty → we throw EndpointNotFoundException.
     *
     * NOTE: MonitorResult has a FK to MonitoredEndpoint.
     *   We need to delete all results first, then the endpoint.
     *   OR add cascade = CascadeType.ALL to the endpoint's results.
     *   Here we delete results manually to be explicit.
     *
     * @param endpointId  ID of the endpoint to delete
     * @param user        the logged-in user (ownership check)
     */
    @Transactional
    public void deleteEndpoint(Long endpointId, User user) {

        MonitoredEndpoint endpoint = endpointRepository
                .findByIdAndUser(endpointId, user)
                .orElseThrow(() -> new EndpointNotFoundException(endpointId));

        // Delete all MonitorResult rows for this endpoint first
        // to avoid FK constraint violation
        List<MonitorResult> results =
                monitorResultRepository.findByEndpointAndCheckedAtAfterOrderByCheckedAtAsc(
                        endpoint, OffsetDateTime.now().minusYears(100));
        monitorResultRepository.deleteAll(results);

        endpointRepository.delete(endpoint);

        log.info("User '{}' deleted monitor endpoint {} ({})",
                user.getUsername(), endpointId, endpoint.getUrl());
    }

    /**
     * Flips the enabled flag on an endpoint.
     *
     * enabled = true  → scheduler will ping this endpoint
     * enabled = false → scheduler skips this endpoint (paused)
     *
     * FLOW:
     *   1. Find the endpoint scoped to this user
     *   2. Flip the boolean
     *   3. Save and return the updated EndpointStatusResponse
     *
     * @param endpointId  ID of the endpoint to toggle
     * @param user        the logged-in user (ownership check)
     * @return            updated status row so Dev C can update the UI
     */
    @Transactional
    public EndpointStatusResponse toggleEndpoint(Long endpointId, User user) {

        MonitoredEndpoint endpoint = endpointRepository
                .findByIdAndUser(endpointId, user)
                .orElseThrow(() -> new EndpointNotFoundException(endpointId));

        // Flip the boolean
        endpoint.setEnabled(!endpoint.isEnabled());
        endpointRepository.save(endpoint);

        log.info("User '{}' {} endpoint {} ({})",
                user.getUsername(),
                endpoint.isEnabled() ? "enabled" : "disabled",
                endpointId,
                endpoint.getUrl());

        // Return the updated row with latest status
        Optional<MonitorResult> latest =
                monitorResultRepository
                        .findTopByEndpointOrderByCheckedAtDesc(endpoint);

        return EndpointStatusResponse.builder()
                .id(endpoint.getId())
                .url(endpoint.getUrl())
                .method(endpoint.getMethod())
                .intervalSeconds(endpoint.getIntervalSeconds())
                .expectedStatusCode(endpoint.getExpectedStatusCode())
                .enabled(endpoint.isEnabled())
                .up(latest.map(MonitorResult::getIsUp).orElse(null))
                .lastCheckedAt(latest.map(MonitorResult::getCheckedAt).orElse(null))
                .lastLatencyMs(latest.map(MonitorResult::getLatencyMs).orElse(null))
                .lastStatusCode(latest.map(MonitorResult::getStatusCode).orElse(null))
                .lastErrorMsg(latest.map(MonitorResult::getErrorMsg).orElse(null))
                .build();
    }

    // ─────────────────────────────────────────────────────────────────
    // Step 4 — runScheduledChecks()
    // ─────────────────────────────────────────────────────────────────

    /**
     * The core scheduler — runs automatically every 60 seconds.
     *
     * FLOW per tick:
     *   1. Load all enabled endpoints (all users)
     *   2. For each endpoint:
     *      a. Get the previous MonitorResult (to detect status change)
     *      b. Fire the HTTP request via HttpClientService
     *      c. Determine isUp: response received AND status code matches expected
     *      d. Save a new MonitorResult
     *      e. If status changed UP→DOWN or DOWN→UP → log the incident
     *
     * WHY fixedDelay and not fixedRate:
     *   fixedRate fires every N ms regardless of how long the task took.
     *   If checking 100 endpoints takes 55s and rate is 60s,
     *   the next run starts only 5s later — overlapping runs.
     *   fixedDelay waits N ms AFTER the previous run finishes.
     *   Much safer for a job that does real network I/O.
     *
     * WHY @Transactional on this method:
     *   We read and write in a loop. One transaction per tick
     *   keeps the reads consistent and batches the writes.
     *
     * WHAT "isUp" means:
     *   true  = we got a response AND status code == expectedStatusCode
     *   false = connection failed, timeout, or wrong status code
     *
     * WHAT "incident" means:
     *   A status change — UP→DOWN or DOWN→UP.
     *   We log it so the team can see it in application logs.
     *   No separate DB table needed at this stage.
     */
    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void runScheduledChecks() {

        List<MonitoredEndpoint> activeEndpoints =
                endpointRepository.findByEnabledTrue();

        if (activeEndpoints.isEmpty()) {
            log.debug("Scheduled check: no active endpoints to ping");
            return;
        }

        log.info("Scheduled check: pinging {} active endpoint(s)",
                activeEndpoints.size());

        for (MonitoredEndpoint endpoint : activeEndpoints) {
            try {
                checkEndpoint(endpoint);
            } catch (Exception ex) {
                // Never let one failed endpoint crash the whole scheduler tick.
                // Log the error and move on to the next endpoint.
                log.error("Scheduler error checking endpoint {} ({}): {}",
                        endpoint.getId(), endpoint.getUrl(), ex.getMessage());
            }
        }
    }

    /**
     * Checks one endpoint — fires the request, saves the result,
     * logs an incident if the status changed.
     *
     * Extracted from runScheduledChecks() to keep the loop clean
     * and to make each endpoint check independently try/catchable.
     *
     * @param endpoint  the endpoint to check
     */
    private void checkEndpoint(MonitoredEndpoint endpoint) {

        // ── Get previous result to detect status changes ──────────────
        Optional<MonitorResult> previousResult =
                monitorResultRepository
                        .findTopByEndpointOrderByCheckedAtDesc(endpoint);

        Boolean previouslyUp = previousResult
                .map(MonitorResult::getIsUp)
                .orElse(null); // null = never checked before

        // ── Fire the HTTP request ──────────────────────────────────────
        // Timeout: use intervalSeconds as a sensible upper bound,
        // capped at 30s so slow endpoints don't block the scheduler.
        int timeoutSeconds = Math.min(endpoint.getIntervalSeconds(), 30);

        SingleRequestResult result = httpClientService.fire(
                endpoint.getUrl(),
                endpoint.getMethod(),
                null,   // no request body for monitoring checks
                null,   // no custom headers
                timeoutSeconds
        );

        // ── Determine isUp ─────────────────────────────────────────────
        // UP means: we got a response AND the status code is what we expect.
        // If fire() returns statusCode=0 it means connection failed entirely.
        boolean isUp = result.getStatusCode() == endpoint.getExpectedStatusCode();

        // ── Save MonitorResult ─────────────────────────────────────────
        MonitorResult monitorResult = new MonitorResult();
        monitorResult.setEndpoint(endpoint);
        monitorResult.setIsUp(isUp);
        monitorResult.setStatusCode(result.getStatusCode());
        monitorResult.setLatencyMs(result.getLatencyMs());
        monitorResult.setErrorMsg(isUp ? null : result.getErrorMessage());
        monitorResultRepository.save(monitorResult);

        log.debug("Checked endpoint {} ({}): {} {}ms status={}",
                endpoint.getId(), endpoint.getUrl(),
                isUp ? "UP" : "DOWN",
                result.getLatencyMs(),
                result.getStatusCode());

        // ── Detect and log incident ────────────────────────────────────
        // Only log if the status actually changed.
        // If previouslyUp is null (first check ever), no incident to log.
        if (previouslyUp != null && previouslyUp != isUp) {
            if (!isUp) {
                // Was UP, now DOWN
                log.warn("INCIDENT [DOWN] endpoint {} ({}) went DOWN — " +
                         "status={} error={}",
                        endpoint.getId(), endpoint.getUrl(),
                        result.getStatusCode(),
                        result.getErrorMessage());
            } else {
                // Was DOWN, now UP
                log.info("INCIDENT [RECOVERED] endpoint {} ({}) is back UP — " +
                         "status={} latency={}ms",
                        endpoint.getId(), endpoint.getUrl(),
                        result.getStatusCode(),
                        result.getLatencyMs());
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Step 5 — getHistory()
    // ─────────────────────────────────────────────────────────────────

    /**
     * Loads all MonitorResult rows for an endpoint within a time window
     * and calculates uptime percentage.
     *
     * FLOW:
     *   1. Find the endpoint scoped to this user (ownership check)
     *   2. Calculate the start of the time window (now - hours)
     *   3. Count total checks and UP checks in the window
     *   4. Calculate uptime %
     *   5. Load all result rows ordered by time (for the chart)
     *   6. Build and return EndpointHistoryResponse
     *
     * UPTIME % FORMULA:
     *   uptimePercent = (upCount / totalChecks) * 100
     *   If totalChecks = 0 → uptimePercent = null (never checked)
     *
     * @param endpointId  ID of the endpoint
     * @param user        the logged-in user (ownership check)
     * @param hours       how far back to look — e.g. 24 means last 24 hours
     * @return            history with uptime stats and result list for chart
     */
    @Transactional(readOnly = true)
    public EndpointHistoryResponse getHistory(Long endpointId, User user, int hours) {

        // ── Ownership check ────────────────────────────────────────────
        MonitoredEndpoint endpoint = endpointRepository
                .findByIdAndUser(endpointId, user)
                .orElseThrow(() -> new EndpointNotFoundException(endpointId));

        // ── Time window ────────────────────────────────────────────────
        OffsetDateTime windowStart = OffsetDateTime.now().minusHours(hours);

        // ── Count checks ───────────────────────────────────────────────
        long totalChecks = monitorResultRepository
                .countByEndpointAndCheckedAtAfter(endpoint, windowStart);

        long upCount = monitorResultRepository
                .countByEndpointAndIsUpTrueAndCheckedAtAfter(endpoint, windowStart);

        long downCount = totalChecks - upCount;

        // ── Uptime % ───────────────────────────────────────────────────
        // null if never checked — Dev C shows "No data" instead of "0%"
        Double uptimePercent = totalChecks == 0
                ? null
                : Math.round((double) upCount / totalChecks * 10_000.0) / 100.0;
        // Math.round(...* 10_000) / 100.0 gives 2 decimal places
        // e.g. 97.333...% → 97.33%

        // ── Load result rows for chart ─────────────────────────────────
        List<MonitorResult> results =
                monitorResultRepository
                        .findByEndpointAndCheckedAtAfterOrderByCheckedAtAsc(
                                endpoint, windowStart);

        List<EndpointHistoryResponse.CheckPointResult> checkPoints = results.stream()
                .map(r -> EndpointHistoryResponse.CheckPointResult.builder()
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
}