package com.api_loader.api_monitor.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * DTO = Data Transfer Object.
 *
 * This class represents the DATA SHAPE of what the user
 * sends when they fill the signup form and click submit.
 *
 * The flow is:
 *   Browser → POST /auth/signup → Dev B's AuthController
 *          → AuthController passes this object to
 *            UserService.register(signupRequest)
 *
 * WHY a DTO instead of using the User entity directly?
 *   The User entity has fields like id, createdAt, password
 *   (hashed). We never want the browser to send those.
 *   The DTO is a clean "input shape" — only what the user
 *   is allowed to provide. Nothing more.
 *
 * WHY validation annotations?
 *   @NotBlank and @Size check the input BEFORE it even
 *   reaches the service. If validation fails, Spring
 *   automatically returns HTTP 400 Bad Request with details.
 *   Dev B just adds @Valid to the controller parameter.
 */
@Getter
@Setter
public class SignupRequest {

    /**
     * The desired username.
     *
     * @NotBlank — rejects null, empty "", and whitespace-only "   "
     * @Size     — enforces 3 to 20 characters
     *
     * Example valid:   "john_doe"
     * Example invalid: ""  or  "a"  or  "this_is_way_too_long_username"
     */
    @NotBlank(message = "Username is required")
    @Size(min = 3, max = 20,
          message = "Username must be between 3 and 20 characters")
    private String username;

    /**
     * The desired password — plain text here, gets hashed
     * inside UserService.register() before touching the DB.
     *
     * IMPORTANT: This field is plain text only while it lives
     * in memory inside this DTO object. We NEVER log it,
     * NEVER store it, NEVER send it back in any response.
     * UserService hashes it immediately with BCrypt.
     */
    @NotBlank(message = "Password is required")
    @Size(min = 6, message = "Password must be at least 6 characters")
    private String password;
}