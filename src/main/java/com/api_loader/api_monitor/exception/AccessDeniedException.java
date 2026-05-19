package com.api_loader.api_monitor.exception;

/**
 * Thrown when a user tries to access data that belongs
 * to a different user.
 *
 * WHEN thrown:
 *   LoadTestService.getResult() → testRun.user != current user
 *   LoadTestService.getRawResults() → same check
 *   MonitorService.deleteEndpoint() → endpoint.user != current user
 *   MonitorService.toggleEndpoint() → same check
 *   MonitorService.getHistory() → same check
 *
 * WHY do we check this in the SERVICE and not just rely
 * on Spring Security?
 *   Spring Security controls who can access a URL.
 *   But it cannot know whether test run "abc-123" belongs
 *   to the logged-in user — that's business logic.
 *   We must check ownership in the service layer.
 *
 * Dev B maps this to HTTP 403 Forbidden in @ControllerAdvice.
 * We use our own exception and NOT Spring Security's built-in
 * AccessDeniedException to avoid conflicts with Spring's
 * internal security handling.
 */
public class AccessDeniedException extends RuntimeException {

    public AccessDeniedException() {
        super("You do not have permission to access this resource");
    }
}