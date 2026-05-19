package com.api_loader.api_monitor.repository;

import com.api_loader.api_monitor.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    // Used by UserService to check if username is taken
    boolean existsByUsername(String username);

    // Used by Spring Security during login
    Optional<User> findByUsername(String username);
}