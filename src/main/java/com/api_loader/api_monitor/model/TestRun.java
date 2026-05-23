package com.api_loader.api_monitor.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "test_run",
        indexes = @Index(name = "idx_test_run_user_id",
                columnList = "user_id"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestRun {

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    // Which user started this test
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_test_run_user"))
    private User user;

    @Column(nullable = false, length = 2048)
    private String url;

    @Column(nullable = false, length = 10)
    private String method;

    @Column(name = "total_requests", nullable = false)
    private int totalRequests;

    @Column(nullable = false)
    private int concurrency;

    @Column(name = "timeout_seconds", nullable = false)
    private int timeoutSeconds;

    // RUNNING → COMPLETED or FAILED
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TestRunStatus status;

    @Column(name = "start_time", nullable = false)
    private OffsetDateTime startTime;

    // Null while test is still RUNNING
    @Column(name = "end_time")
    private OffsetDateTime endTime;

    @PrePersist
    protected void onCreate() {
        this.startTime = OffsetDateTime.now();
        this.status = TestRunStatus.RUNNING;
    }

    public enum TestRunStatus {
        RUNNING, COMPLETED, FAILED
    }
}