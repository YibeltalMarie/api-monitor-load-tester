package com.api_loader.api_monitor;

import com.api_loader.api_monitor.dto.request.SignupRequest;
import com.api_loader.api_monitor.exception.UsernameAlreadyExistsException;
import com.api_loader.api_monitor.model.User;
import com.api_loader.api_monitor.repository.UserRepository;
import com.api_loader.api_monitor.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for UserService.
 *
 * ── @SpringBootTest ───────────────────────────────────────
 * Starts the FULL Spring application context — same as
 * running the app for real. This means the real database
 * (H2 in-memory), real beans, real BCrypt encoder.
 * This is NOT a mock test — it tests the real flow
 * end to end: service → repository → H2 database.
 */
@SpringBootTest
class UserServiceTest {

    // Spring injects the real instances of each
    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // ─────────────────────────────────────────────────────
    // TEST 1: Happy path — register a new user
    // ─────────────────────────────────────────────────────
    @Test
    void register_shouldSaveUserWithHashedPassword() {

        // ── ARRANGE: build the signup request ────────────
        SignupRequest request = new SignupRequest();
        request.setUsername("testuser");
        request.setPassword("password123");

        // ── ACT: call register() ─────────────────────────
        User savedUser = userService.register(request);

        // ── ASSERT 1: returned user has an id ────────────
        // If id is null, the save failed silently
        assertNotNull(savedUser.getId(),
                "Saved user should have a generated UUID id");

        // ── ASSERT 2: username was saved correctly ────────
        assertEquals("testuser", savedUser.getUsername(),
                "Username should match what was submitted");

        // ── ASSERT 3: password is NOT stored as plain text
        // The stored password must be a BCrypt hash,
        // which always starts with "$2a$"
        assertTrue(savedUser.getPassword().startsWith("$2a$"),
                "Password should be BCrypt hashed, not plain text");

        // ── ASSERT 4: BCrypt can verify the original ──────
        // passwordEncoder.matches("plain", "hash") returns
        // true if the plain text matches the hash.
        // This is how Spring Security verifies login.
        assertTrue(
                passwordEncoder.matches("password123",
                        savedUser.getPassword()),
                "BCrypt should verify the original password against the hash");

        // ── ASSERT 5: user actually exists in H2 ─────────
        // Go to the database directly and look it up
        Optional<User> fromDb = userRepository
                .findByUsername("testuser");

        assertTrue(fromDb.isPresent(),
                "User should exist in the H2 database");

        // ── ASSERT 6: createdAt was set automatically ─────
        assertNotNull(fromDb.get().getCreatedAt(),
                "createdAt should be set automatically by @PrePersist");

        System.out.println("✅ User saved to H2:");
        System.out.println("   id        : " + fromDb.get().getId());
        System.out.println("   username  : " + fromDb.get().getUsername());
        System.out.println("   password  : " + fromDb.get().getPassword());
        System.out.println("   createdAt : " + fromDb.get().getCreatedAt());
    }

    // ─────────────────────────────────────────────────────
    // TEST 2: Duplicate username — should throw exception
    // ─────────────────────────────────────────────────────
    @Test
    void register_shouldThrowWhenUsernameAlreadyExists() {

        // ── ARRANGE: register the first user ─────────────
        SignupRequest first = new SignupRequest();
        first.setUsername("duplicateuser");
        first.setPassword("password123");
        userService.register(first);

        // ── ACT + ASSERT: second registration should throw
        SignupRequest second = new SignupRequest();
        second.setUsername("duplicateuser");  // same username
        second.setPassword("differentpass");

        // assertThrows verifies that calling register()
        // with a duplicate username throws our custom exception
        UsernameAlreadyExistsException thrown = assertThrows(
                UsernameAlreadyExistsException.class,
                () -> userService.register(second),
                "Should throw UsernameAlreadyExistsException"
        );

        // Verify the exception message contains the username
        assertTrue(thrown.getMessage().contains("duplicateuser"),
                "Exception message should include the duplicate username");

        System.out.println("✅ Duplicate username correctly rejected:");
        System.out.println("   Exception: " + thrown.getMessage());
    }

    // ─────────────────────────────────────────────────────
    // TEST 3: loadUserByUsername — Spring Security lookup
    // ─────────────────────────────────────────────────────
    @Test
    void loadUserByUsername_shouldReturnUserDetails() {

        // ── ARRANGE: register a user first ───────────────
        SignupRequest request = new SignupRequest();
        request.setUsername("securityuser");
        request.setPassword("securepass");
        userService.register(request);

        // ── ACT: call loadUserByUsername ──────────────────
        // This is what Spring Security calls during login
        var userDetails = userService
                .loadUserByUsername("securityuser");

        // ── ASSERT: UserDetails has correct data ──────────
        assertNotNull(userDetails,
                "UserDetails should not be null");

        assertEquals("securityuser", userDetails.getUsername(),
                "UserDetails username should match");

        // The password in UserDetails should be the BCrypt hash
        assertTrue(userDetails.getPassword().startsWith("$2a$"),
                "UserDetails password should be the BCrypt hash");

        // Should have ROLE_USER authority
        assertTrue(
                userDetails.getAuthorities().stream()
                        .anyMatch(a -> a.getAuthority()
                                .equals("ROLE_USER")),
                "UserDetails should have ROLE_USER authority");

        System.out.println("✅ Spring Security UserDetails loaded:");
        System.out.println("   username    : " + userDetails.getUsername());
        System.out.println("   password    : " + userDetails.getPassword());
        System.out.println("   authorities : " + userDetails.getAuthorities());
    }
}