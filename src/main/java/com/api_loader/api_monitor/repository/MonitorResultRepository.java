package com.api_loader.api_monitor.repository;

import com.api_loader.api_monitor.model.MonitoredEndpoint;
import com.api_loader.api_monitor.model.MonitorResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface MonitorResultRepository
        extends JpaRepository<MonitorResult, Long> {

    // Latest check result for current status badge
    Optional<MonitorResult> findTopByEndpointOrderByCheckedAtDesc(
            MonitoredEndpoint endpoint);

    // Results within a time window (24h, 7d, 30d charts)
    List<MonitorResult> findByEndpointAndCheckedAtAfterOrderByCheckedAtAsc(
            MonitoredEndpoint endpoint, OffsetDateTime after);

    // Count checks in window (for uptime % calculation)
    long countByEndpointAndCheckedAtAfter(
            MonitoredEndpoint endpoint, OffsetDateTime after);

    // Count UP checks in window
    long countByEndpointAndIsUpTrueAndCheckedAtAfter(
            MonitoredEndpoint endpoint, OffsetDateTime after);
}