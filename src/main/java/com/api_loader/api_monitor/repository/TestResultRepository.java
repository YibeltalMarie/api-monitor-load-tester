package com.api_loader.api_monitor.repository;

import com.api_loader.api_monitor.model.TestResult;
import com.api_loader.api_monitor.model.TestRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TestResultRepository extends JpaRepository<TestResult, UUID> {

    // Load all results for a test run, in request order
    List<TestResult> findByTestRunOrderByIndex(TestRun testRun);

    // Count results (used for progress tracking)
    long countByTestRun(TestRun testRun);
}