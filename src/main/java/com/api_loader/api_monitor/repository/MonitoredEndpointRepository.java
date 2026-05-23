package com.api_loader.api_monitor.repository;

import com.api_loader.api_monitor.model.MonitoredEndpoint;
import com.api_loader.api_monitor.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MonitoredEndpointRepository
        extends JpaRepository<MonitoredEndpoint, Long> {

    // Load all endpoints for a user (monitor board)
    List<MonitoredEndpoint> findByUser(User user);

    // Load only active endpoints (used by @Scheduled job)
    List<MonitoredEndpoint> findByEnabledTrue();

    // Scope a single endpoint to its owner (security check)
    Optional<MonitoredEndpoint> findByIdAndUser(Long id, User user);
}