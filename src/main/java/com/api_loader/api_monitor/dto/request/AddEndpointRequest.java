package com.api_loader.api_monitor.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * Data the user submits when adding a new monitored endpoint.
 *
 * FLOW:
 *   User fills the add-endpoint form on monitor-list.html
 *   Dev C sends it via fetch() as JSON to POST /api/monitor
 *   Dev B receives it in MonitorController
 *   Dev B passes it to MonitorService.addEndpoint()
 *   Dev A saves it as a MonitoredEndpoint entity
 *
 * VALIDATION:
 *   @Valid on the controller parameter triggers these automatically.
 *   Any failure → HTTP 400 before the service is called.
 */
@Getter
@Setter
public class AddEndpointRequest {

    /** The URL to monitor — e.g. https://api.example.com/health */
    @NotBlank(message = "URL is required")
    private String url;

    /** HTTP method to use when pinging — GET or HEAD recommended */
    @NotBlank(message = "HTTP method is required")
    private String method;

    /**
     * How often to check this endpoint in seconds.
     * Min 10s — avoid hammering targets.
     * Max 86400s — once per day is the slowest useful interval.
     * The scheduler runs every 60s so values under 60 are
     * effectively treated as 60s in the current implementation.
     */
    @Min(value = 10,    message = "Interval must be at least 10 seconds")
    @Max(value = 86400, message = "Interval cannot exceed 86400 seconds (24h)")
    private int intervalSeconds;

    /**
     * The HTTP status code we consider healthy.
     * Defaults to 200 in MonitoredEndpoint.@PrePersist if 0 is passed.
     * Example: 200, 201, 204, 301
     */
    @Min(value = 100, message = "Expected status code must be a valid HTTP status (100–599)")
    @Max(value = 599, message = "Expected status code must be a valid HTTP status (100–599)")
    private int expectedStatusCode = 200;
}