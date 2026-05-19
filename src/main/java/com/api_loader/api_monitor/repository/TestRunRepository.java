package com.api_loader.api_monitor.repository;

import com.api_loader.api_monitor.model.TestRun;
import com.api_loader.api_monitor.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TestRunRepository extends JpaRepository<TestRun, UUID> {

    // Load history for one user, newest first
    List<TestRun> findByUserOrderByStartTimeDesc(User user);

    // Load one run scoped to a user (for security check)
    Optional<TestRun> findByIdAndUser(UUID id, User user);

    // Used on startup to fix stuck RUNNING tests
    List<TestRun> findByStatus(TestRun.TestRunStatus status);
}