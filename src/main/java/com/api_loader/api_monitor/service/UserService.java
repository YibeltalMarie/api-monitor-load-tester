package com.api_loader.api_monitor.service;

import com.api_loader.api_monitor.dto.request.SignupRequest;
import com.api_loader.api_monitor.exception.UsernameAlreadyExistsException;
import com.api_loader.api_monitor.model.User;
import com.api_loader.api_monitor.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * UserService handles everything related to user accounts:
 *   1. Registering a new user (register)
 *   2. Loading a user during login (loadUserByUsername)
 *
 * ── WHY implements UserDetailsService? ──────────────────
 * Spring Security needs to know HOW to load a user when
 * someone tries to log in. It does this by calling
 * loadUserByUsername(). By implementing UserDetailsService
 * here, we tell Spring Security: "use this class to look
 * up users from our database."
 * Dev B registers this in SecurityConfig like:
 *   http.userDetailsService(userService)
 *
 * ── @Service ─────────────────────────────────────────────
 * Marks this class as a Spring-managed bean. Spring creates
 * one instance of it and injects it wherever it's needed
 * (e.g. into AuthController via @Autowired or constructor).
 *
 * ── @Slf4j ───────────────────────────────────────────────
 * Lombok generates a logger for us: `log.info(...)`.
 * We use it to print what's happening without System.out.
 *
 * ── @RequiredArgsConstructor ─────────────────────────────
 * Lombok generates a constructor for all `final` fields.
 * This is how Spring injects UserRepository and
 * PasswordEncoder into this service (constructor injection).
 * It is safer than @Autowired on fields.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService implements UserDetailsService {

    /**
     * Talks to the users table in the database.
     * We never write SQL — Spring Data JPA generates it
     * from the method names we defined in UserRepository.
     */
    private final UserRepository userRepository;

    /**
     * BCrypt password encoder.
     * Dev B defines this as a @Bean in SecurityConfig.
     * Spring injects it here automatically.
     *
     * Why injected and not created here?
     *   If we did `new BCryptPasswordEncoder()` here,
     *   Spring Security wouldn't know about it and couldn't
     *   use the same instance during login verification.
     *   The bean must be shared — defined once, used everywhere.
     */
    private final PasswordEncoder passwordEncoder;

    // ─────────────────────────────────────────────────────
    // METHOD 1: register()
    // Called by: Dev B's AuthController on POST /auth/signup
    // ─────────────────────────────────────────────────────

    /**
     * Registers a new user account.
     *
     * FLOW:
     *   Step 1 → Check if username is already taken
     *   Step 2 → Hash the plain-text password with BCrypt
     *   Step 3 → Build a User entity and save to DB
     *   Step 4 → Return saved User (with generated id + createdAt)
     *
     * @param request  the signup form data (username + password)
     * @return         the newly created and saved User entity
     * @throws UsernameAlreadyExistsException if username is taken
     *
     * ── @Transactional ───────────────────────────────────
     * Wraps the whole method in a database transaction.
     * If anything fails mid-way (e.g. DB goes down after
     * the check but before the save), the whole thing rolls
     * back. No partial data is left in the DB.
     */
    @Transactional
    public User register(SignupRequest request) {

        log.info("Attempting to register new user: '{}'",
                request.getUsername());

        // ── Step 1: Check if username is already taken ───
        // We query the DB: does any row have this username?
        // existsByUsername() is faster than findByUsername()
        // because it stops as soon as it finds ONE match
        // instead of loading the whole User object.
        if (userRepository.existsByUsername(request.getUsername())) {
            log.warn("Registration failed — username '{}' already exists",
                    request.getUsername());
            throw new UsernameAlreadyExistsException(request.getUsername());
        }

        // ── Step 2: Hash the password ────────────────────
        // BCrypt turns "mypassword123" into something like:
        // "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"
        //
        // BCrypt is one-way — you CANNOT reverse it back to
        // the original password. This is intentional.
        // During login, Spring Security hashes the entered
        // password again and COMPARES the two hashes.
        //
        // We NEVER store the plain password — only the hash.
        String hashedPassword = passwordEncoder.encode(
                request.getPassword());

        log.debug("Password hashed successfully for user: '{}'",
                request.getUsername());

        // ── Step 3: Build and save the User entity ───────
        // @Builder from Lombok lets us set only the fields
        // we care about. id and createdAt are set automatically
        // by @UuidGenerator and @PrePersist in User.java.
        User newUser = User.builder()
                .username(request.getUsername())
                .password(hashedPassword)
                .build();

        // save() inserts a new row into the users table.
        // It returns the saved entity with id and createdAt
        // populated (they were set by @PrePersist + @UuidGenerator
        // just before the INSERT runs).
        User savedUser = userRepository.save(newUser);

        log.info("User '{}' registered successfully with id: {}",
                savedUser.getUsername(), savedUser.getId());

        // ── Step 4: Return the saved user ────────────────
        // Dev B uses the returned User to know the registration
        // succeeded. The id is now available if needed.
        // Dev B does NOT return this User to the browser —
        // it redirects to the login page instead.
        return savedUser;
    }

    // ─────────────────────────────────────────────────────
    // METHOD 2: loadUserByUsername()
    // Called by: Spring Security automatically during login
    // Dev B does NOT call this directly
    // ─────────────────────────────────────────────────────

    /**
     * Loads a user from the database by username so Spring
     * Security can verify their password during login.
     *
     * FLOW (happens automatically when user submits login form):
     *   Step 1 → Spring Security gets username + password
     *            from the login form
     *   Step 2 → Spring Security calls loadUserByUsername()
     *            with just the username
     *   Step 3 → We look up the User in our DB
     *   Step 4 → Return UserDetails (Spring Security's format)
     *   Step 5 → Spring Security compares the submitted password
     *            against the stored hash using PasswordEncoder
     *   Step 6 → If match → login success, session created
     *            If no match → redirect to /auth/login?error
     *
     * @param username  the username from the login form
     * @return          UserDetails that Spring Security uses
     * @throws UsernameNotFoundException if no user found
     *                  (Spring Security catches this and shows
     *                   the login error page)
     *
     * ── @Transactional(readOnly = true) ──────────────────
     * readOnly = true tells Hibernate this is a read-only
     * operation. Hibernate skips dirty checking (checking
     * if any entity changed) which makes it slightly faster.
     * Use readOnly=true on any method that only reads data.
     */
    @Transactional(readOnly = true)
    @Override
    public UserDetails loadUserByUsername(String username)
            throws UsernameNotFoundException {

        log.debug("Spring Security requesting user: '{}'", username);

        // Look up our User entity from the database
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> {
                    log.warn("Login failed — user '{}' not found", username);
                    // Spring Security catches this exception
                    // and redirects to /auth/login?error
                    return new UsernameNotFoundException(
                            "User not found: " + username);
                });

        // ── Convert our User entity to Spring Security's ──
        // UserDetails format.
        //
        // Spring Security doesn't know about our User class.
        // It needs a UserDetails object. We use the built-in
        // org.springframework.security.core.userdetails.User
        // builder (note: different from our model.User class).
        //
        // Parameters:
        //   1. username  — the login identifier
        //   2. password  — the BCrypt hash (Spring Security
        //                  will call passwordEncoder.matches()
        //                  to compare with submitted password)
        //   3. roles     — authorities/permissions.
        //                  We use "ROLE_USER" for everyone.
        //                  Required by Spring Security even if
        //                  we don't use role-based access yet.
        return org.springframework.security.core.userdetails.User
                .withUsername(user.getUsername())
                .password(user.getPassword())
                .roles("USER")
                .build();
    }
}