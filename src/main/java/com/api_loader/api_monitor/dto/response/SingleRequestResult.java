package com.api_loader.api_monitor.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * The result of ONE individual HTTP request fired by
 * HttpClientService.fire().
 *
 * This is an INTERNAL DTO — it never reaches the browser.
 * It travels between HttpClientService and LoadTestService
 * (and later MonitorService) only inside the backend.
 *
 * FLOW:
 *   LoadTestService calls HttpClientService.fire()
 *   HttpClientService returns SingleRequestResult
 *   LoadTestService reads it and creates a TestResult entity
 *   TestResult entity is saved to the database
 *
 * WHY never throws?
 *   HttpClientService catches ALL exceptions and always
 *   returns this object. If the request failed:
 *     statusCode  = 0
 *     success     = false
 *     errorMessage = "Connection refused" or "Read timeout"
 *   This way LoadTestService never needs try/catch —
 *   it just reads the success flag.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SingleRequestResult {

    /**
     * HTTP status code received from the server.
     * 0 means no response — connection failed or timed out
     * before the server could respond.
     */
    private int statusCode;

    /**
     * How long the request took from send to response.
     * Measured in milliseconds.
     * Still recorded even for failed requests — useful
     * to know a request timed out at exactly 5000ms.
     */
    private long latencyMs;

    /**
     * True if the server responded with any HTTP status.
     * False if connection failed, refused, or timed out.
     *
     * NOTE: success here means "got a response" not
     * "got a 200". A 500 response is still success=true
     * because the server DID respond. LoadTestService
     * decides if the response was "good" based on
     * statusCode vs the user's expected status.
     */
    private boolean success;

    /**
     * Null if success=true.
     * Set if success=false — describes what went wrong.
     * Examples: "Connection refused", "Read timeout after 5000ms"
     */
    private String errorMessage;
}