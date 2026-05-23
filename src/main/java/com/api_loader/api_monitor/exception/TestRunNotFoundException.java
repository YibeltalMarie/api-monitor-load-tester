package com.api_loader.api_monitor.exception;

import java.util.UUID;

/**
 * Thrown when a test run ID does not exist in the database.
 *
 * WHEN thrown:
 *   LoadTestService.getResult(id, user) → id not in DB
 *   LoadTestService.getRawResults(id, user) → id not in DB
 *
 * Dev B catches this in @ControllerAdvice and returns
 * HTTP 404 Not Found with the exception message as the
 * error body so Dev C can show a "test not found" message.
 */
public class TestRunNotFoundException extends RuntimeException {

    public TestRunNotFoundException(UUID id) {
        super("Test run not found with id: " + id);
    }
}