package com.api_loader.api_monitor.exception;

/**
 * Thrown by UserService.register() when someone tries to
 * sign up with a username that already exists in the database.
 *
 * This is a RuntimeException (unchecked) — meaning callers
 * do NOT have to wrap it in try/catch. Spring just lets it
 * bubble up to Dev B's @ControllerAdvice which maps it to
 * HTTP 409 Conflict.
 *
 * Why RuntimeException and not Exception?
 *   Checked exceptions (extends Exception) force every caller
 *   to handle them. In Spring apps, unchecked exceptions are
 *   the convention — you let them propagate and handle them
 *   globally in one place (@ControllerAdvice).
 */
public class UsernameAlreadyExistsException extends RuntimeException {

    /**
     * @param username the username that was already taken
     *
     * Example message produced:
     *   "Username 'john' is already taken"
     *
     * Dev B reads this message and returns it in the error
     * response JSON so Dev C can show it to the user.
     */
    public UsernameAlreadyExistsException(String username) {
        super("Username '" + username + "' is already taken");
    }
}