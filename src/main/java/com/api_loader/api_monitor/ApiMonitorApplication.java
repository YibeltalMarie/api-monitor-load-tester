package com.api_loader.api_monitor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
// import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
// import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
// import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Main entry point of the Spring Boot application.
 *
 * ── @SpringBootApplication ───────────────────────────────
 * A shortcut for three annotations:
 *   @Configuration      — this class can define @Bean methods
 *   @EnableAutoConfiguration — Spring Boot auto-configures
 *                              everything it finds on classpath
 *   @ComponentScan      — scans this package and sub-packages
 *                         for @Service, @Repository, @Controller
 *
 * ── @EnableScheduling ────────────────────────────────────
 * Required for @Scheduled to work in MonitorService later.
 * Add it now so we don't forget.
 */
@SpringBootApplication
@EnableScheduling
public class ApiMonitorApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiMonitorApplication.class, args);
    }

    /**
     * Defines BCryptPasswordEncoder as a Spring bean.
     *
     * WHY here and not inside UserService?
     *   Spring Security also needs this exact bean during
     *   login to verify passwords. By defining it as a @Bean
     *   here, the SAME instance is shared across:
     *     - UserService (to hash on register)
     *     - Spring Security (to verify on login)
     *
     * BCrypt strength defaults to 10 rounds —
     * slow enough to resist brute force,
     * fast enough for normal use (~100ms per hash).
     */
    // @Bean
    // public PasswordEncoder passwordEncoder() {
    //     return new BCryptPasswordEncoder();
    // }
}