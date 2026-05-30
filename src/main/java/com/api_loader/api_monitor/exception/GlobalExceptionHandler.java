package com.api_loader.api_monitor.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.api_loader.api_monitor.exception.AccessDeniedException;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * GlobalExceptionHandler — catches all exceptions thrown by any
 * controller and returns a consistent JSON error response.
 *
 * WHY @ControllerAdvice:
 *   Without this, Spring returns its default Whitelabel Error Page
 *   (HTML) or a generic JSON with no useful message.
 *   Dev C can't show meaningful error messages to the user.
 *   With this, every error has the same JSON shape so Dev C
 *   always knows what field to read.
 *
 * ERROR RESPONSE SHAPE (all errors return this):
 *   {
 *     "status":    404,
 *     "error":     "Not Found",
 *     "message":   "Monitored endpoint not found with id: 42",
 *     "timestamp": "2026-05-25T10:30:00Z"
 *   }
 *
 * VALIDATION ERROR SHAPE (400 from @Valid failures):
 *   {
 *     "status":  400,
 *     "error":   "Validation Failed",
 *     "message": "url: URL is required; intervalSeconds: Interval must be at least 10 seconds",
 *     "timestamp": "2026-05-25T10:30:00Z"
 *   }
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ── 404 Not Found ────────────────────────────────────────────────────────

    @ExceptionHandler(EndpointNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleEndpointNotFound(
            EndpointNotFoundException ex) {
        log.warn("Endpoint not found: {}", ex.getMessage());
        return error(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(TestRunNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleTestRunNotFound(
            TestRunNotFoundException ex) {
        log.warn("Test run not found: {}", ex.getMessage());
        return error(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleUserNotFound(
            UserNotFoundException ex) {
        log.warn("User not found: {}", ex.getMessage());
        return error(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    // ── 403 Forbidden ────────────────────────────────────────────────────────

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(
            AccessDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());
        return error(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    // ── 409 Conflict ─────────────────────────────────────────────────────────

    @ExceptionHandler(UsernameAlreadyExistsException.class)
    public ResponseEntity<Map<String, Object>> handleUsernameExists(
            UsernameAlreadyExistsException ex) {
        log.warn("Username conflict: {}", ex.getMessage());
        return error(HttpStatus.CONFLICT, ex.getMessage());
    }

    // ── 400 Bad Request — @Valid validation failures ─────────────────────────
    // Thrown automatically by Spring when @Valid fails on a @RequestBody.
    // We collect all field errors into one readable message string.

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(
            MethodArgumentNotValidException ex) {

        // Collect all field errors into "fieldName: message" pairs
        String message = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));

        log.warn("Validation failed: {}", message);
        return error(HttpStatus.BAD_REQUEST, message);
    }

    // ── 500 Internal Server Error — catch-all ────────────────────────────────
    // Catches anything not handled above.
    // Logs the full stack trace but only returns a generic message
    // to the client — we never expose internal details to the browser.

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Please try again.");
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    /**
     * Builds the consistent error response body.
     * Every error from every controller looks exactly like this.
     *
     * @param status   HTTP status to return
     * @param message  human-readable error message
     * @return         ResponseEntity with error JSON body
     */
    private ResponseEntity<Map<String, Object>> error(
            HttpStatus status, String message) {

        Map<String, Object> body = Map.of(
                "status",    status.value(),
                "error",     status.getReasonPhrase(),
                "message",   message,
                "timestamp", OffsetDateTime.now().toString()
        );

        return ResponseEntity.status(status).body(body);
    }
}