package com.api_loader.api_monitor.service;

import com.api_loader.api_monitor.dto.response.SingleRequestResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.Map;

/**
 * HttpClientService fires ONE HTTP request and returns
 * the result. That's its only job.
 *
 * WHY a separate service just for one request?
 *   Both LoadTestService and MonitorService need to fire
 *   HTTP requests. Instead of duplicating the WebClient
 *   logic in both places, we put it here once.
 *   Single responsibility — one class, one job.
 *
 * WHY WebClient and not RestTemplate?
 *   WebClient is the modern Spring HTTP client.
 *   RestTemplate is older and being phased out.
 *   WebClient handles timeouts and errors more cleanly.
 *   We use it in "blocking" mode here (.block()) because
 *   our virtual threads handle the concurrency — we don't
 *   need reactive programming on top of that.
 *
 * KEY RULE: this method NEVER throws an exception.
 *   Every possible failure is caught and returned as a
 *   SingleRequestResult with success=false.
 *   This means LoadTestService never needs try/catch.
 */
@Slf4j
@Service
public class HttpClientService {

    /**
     * WebClient is Spring's HTTP client.
     * We create one instance here (it is thread-safe
     * and designed to be reused across many requests).
     *
     * We do NOT set a base URL because the target URL
     * changes with every request — it comes from the user.
     */
    private final WebClient webClient;

    public HttpClientService() {
        this.webClient = WebClient.builder()
                // Increase max memory for response body
                // Default is 256KB which fails on large responses
                .codecs(config -> config
                        .defaultCodecs()
                        .maxInMemorySize(1024 * 1024)) // 1MB
                .build();
    }

    // ─────────────────────────────────────────────────────
    // fire() — the only public method
    // ─────────────────────────────────────────────────────

    /**
     * Fires one HTTP request and returns the result.
     *
     * FLOW:
     *   Step 1 → record start time
     *   Step 2 → build the request (method, url, headers, body)
     *   Step 3 → execute with timeout
     *   Step 4 → record end time → calculate latencyMs
     *   Step 5 → return SingleRequestResult
     *   (if anything fails → catch → return failure result)
     *
     * @param url            full URL to call
     * @param method         HTTP method (GET, POST, PUT, DELETE)
     * @param body           request body — null for GET/DELETE
     * @param headers        custom headers — null means none
     * @param timeoutSeconds abort if no response within this time
     * @return               always returns a result, never throws
     */
    public SingleRequestResult fire(
            String url,
            String method,
            String body,
            Map<String, String> headers,
            int timeoutSeconds) {

        // ── Step 1: Record the start time ────────────────
        // System.nanoTime() is more precise than
        // System.currentTimeMillis() for measuring durations.
        // We convert to milliseconds at the end.
        long startTime = System.nanoTime();

        try {
            // ── Step 2: Build the request ─────────────────

            // Start building — set the HTTP method and URL
            WebClient.RequestBodySpec requestSpec = webClient
                    .method(org.springframework.http.HttpMethod
                            .valueOf(method.toUpperCase()))
                    .uri(url);

            // Add custom headers if provided
            // Example: Authorization, Content-Type, API keys
            if (headers != null && !headers.isEmpty()) {
                headers.forEach(requestSpec::header);
            }

            // Add request body if provided (POST / PUT)
            WebClient.RequestHeadersSpec<?> headersSpec;
            if (body != null && !body.isBlank()) {
                headersSpec = requestSpec
                        .contentType(
                            org.springframework.http.MediaType
                                    .APPLICATION_JSON)
                        .bodyValue(body);
            } else {
                // GET and DELETE have no body
                headersSpec = requestSpec;
            }

            // ── Step 3: Execute the request with timeout ──
            // .retrieve()     → execute and get response
            // .toBodilessEntity() → we only care about the
            //                   status code, not the body
            // .block(timeout) → wait for response (blocking)
            //                   virtual thread handles this,
            //                   so blocking is fine here
            var response = headersSpec
                    .retrieve()
                    .toBodilessEntity()
                    .block(Duration.ofSeconds(timeoutSeconds));

            // ── Step 4: Calculate latency ─────────────────
            long latencyMs = calculateLatencyMs(startTime);

            // ── Step 5: Build and return success result ───
            int statusCode = (response != null)
                    ? response.getStatusCode().value()
                    : 0;

            log.debug("Request to {} completed: {}ms status={}",
                    url, latencyMs, statusCode);

            return SingleRequestResult.builder()
                    .statusCode(statusCode)
                    .latencyMs(latencyMs)
                    .success(true)
                    .errorMessage(null)
                    .build();

        } catch (WebClientResponseException ex) {
            // ── Server responded with an error status ─────
            // e.g. 404, 500, 503 — server IS reachable but
            // returned an error HTTP status code.
            // We still record it — the server DID respond.
            long latencyMs = calculateLatencyMs(startTime);

            log.debug("Request to {} got error response: {}ms status={}",
                    url, latencyMs, ex.getStatusCode().value());

            return SingleRequestResult.builder()
                    .statusCode(ex.getStatusCode().value())
                    .latencyMs(latencyMs)
                    .success(false)
                    .errorMessage(ex.getMessage())
                    .build();

        } catch (WebClientRequestException ex) {
            // ── Connection failed entirely ─────────────────
            // Server not reachable — wrong URL, server down,
            // firewall blocking, DNS failure, etc.
            long latencyMs = calculateLatencyMs(startTime);

            log.debug("Request to {} connection failed: {}",
                    url, ex.getMessage());

            return SingleRequestResult.builder()
                    .statusCode(0)
                    .latencyMs(latencyMs)
                    .success(false)
                    .errorMessage("Connection failed: "
                            + ex.getMessage())
                    .build();

        } catch (Exception ex) {
            // ── Catch-all: timeout or any other failure ────
            // This catches the timeout from .block(Duration)
            // which throws when the duration expires.
            long latencyMs = calculateLatencyMs(startTime);

            log.debug("Request to {} failed: {}", url,
                    ex.getMessage());

            return SingleRequestResult.builder()
                    .statusCode(0)
                    .latencyMs(latencyMs)
                    .success(false)
                    .errorMessage("Request failed: "
                            + ex.getMessage())
                    .build();
        }
    }

    // ─────────────────────────────────────────────────────
    // Private helper
    // ─────────────────────────────────────────────────────

    /**
     * Converts nanosecond start time to elapsed milliseconds.
     *
     * nanoTime() returns nanoseconds (1ms = 1,000,000 ns).
     * We divide by 1,000,000 to get milliseconds.
     * This is more accurate than measuring in milliseconds
     * from the start because nanoTime is monotonic —
     * it is not affected by system clock changes.
     */
    private long calculateLatencyMs(long startTimeNano) {
        return (System.nanoTime() - startTimeNano) / 1_000_000;
    }
}