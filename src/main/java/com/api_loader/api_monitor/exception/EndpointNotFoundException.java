package com.api_loader.api_monitor.exception;

/**
 * Thrown when a monitored endpoint ID does not exist in the database.
 *
 * WHEN thrown:
 *   MonitorService.deleteEndpoint()  → id not found for this user
 *   MonitorService.toggleEndpoint()  → id not found for this user
 *   MonitorService.getHistory()      → id not found for this user
 *
 * Dev B maps this to HTTP 404 Not Found in @ControllerAdvice.
 */
public class EndpointNotFoundException extends RuntimeException {

    public EndpointNotFoundException(Long id) {
        super("Monitored endpoint not found with id: " + id);
    }
}