package com.api_loader.api_monitor.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * The data shape of what the user submits on the
 * load test config form.
 *
 * FLOW:
 *   User fills the form on load-test-config.html
 *   Dev C sends it via JavaScript fetch() as JSON
 *   Dev B receives it in LoadTestController
 *   Dev B passes it to LoadTestService.startTest()
 *   Dev A uses it to configure and run the test
 *
 * VALIDATION:
 *   @NotBlank, @Min, @Max run automatically when Dev B
 *   adds @Valid to the controller parameter.
 *   If any validation fails → HTTP 400 Bad Request
 *   before the service is even called.
 *
 * LIMITS EXPLAINED:
 *   totalRequests max 10000 — more than this would
 *     overwhelm both the target server and our own app
 *   concurrency max 500 — each concurrent request uses
 *     one virtual thread. 500 is already very high load.
 *   timeoutSeconds max 60 — no reason to wait longer
 *     than 1 minute for a single HTTP request
 */
@Getter
@Setter
public class LoadTestRequest {

    /** The target URL to load test */
    @NotBlank(message = "URL is required")
    private String url;

    /** HTTP method: GET, POST, PUT, DELETE */
    @NotBlank(message = "HTTP method is required")
    private String method;

    /** Total number of HTTP requests to fire */
    @Min(value = 1, message = "Must fire at least 1 request")
    @Max(value = 10000, message = "Cannot exceed 10,000 requests")
    private int totalRequests;

    /**
     * How many requests run simultaneously.
     * concurrency=10 means 10 requests fire at the same time.
     * concurrency=1 means requests fire one after another.
     */
    @Min(value = 1, message = "Concurrency must be at least 1")
    @Max(value = 500, message = "Concurrency cannot exceed 500")
    private int concurrency;

    /** Per-request timeout in seconds */
    @Min(value = 1, message = "Timeout must be at least 1 second")
    @Max(value = 60, message = "Timeout cannot exceed 60 seconds")
    private int timeoutSeconds;

    /**
     * Request body — only used for POST and PUT.
     * Null for GET and DELETE.
     */
    private String requestBody;

    /**
     * Optional custom HTTP headers.
     * Example: {"Authorization": "Bearer token123"}
     * Null means no custom headers — use defaults only.
     */
    private Map<String, String> headers;
}