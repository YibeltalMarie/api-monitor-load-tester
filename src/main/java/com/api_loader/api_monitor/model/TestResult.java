package com.api_loader.api_monitor.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "test_result",
        indexes = @Index(name = "idx_test_result_run_id",
                columnList = "test_run_id"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestResult {

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    // Which test run this result belongs to
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "test_run_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_test_result_run"))
    private TestRun testRun;

    // Request number within the test (1, 2, 3...)
    @Column(name = "request_index", nullable = false)
    private int index;

    // 0 means connection failed or timed out
    @Column(name = "status_code", nullable = false)
    private int statusCode;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    @Column(nullable = false)
    private boolean success;

    // Null if successful
    @Column(name = "error_msg", length = 500)
    private String errorMsg;

    @Column(name = "recorded_at", nullable = false)
    private OffsetDateTime recordedAt;

    @PrePersist
    protected void onCreate() {
        this.recordedAt = OffsetDateTime.now();
    }
}