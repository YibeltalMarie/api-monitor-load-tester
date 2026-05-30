package com.api_loader.api_monitor.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

@Entity
@Table(name = "monitor_result",
        indexes = {
            @Index(name = "idx_monitor_result_endpoint_id",
                    columnList = "endpoint_id"),
            @Index(name = "idx_monitor_result_checked_at",
                    columnList = "checked_at")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MonitorResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "endpoint_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_monitor_result_endpoint"))
    private MonitoredEndpoint endpoint;

    // @CreationTimestamp sets this automatically when the row is inserted.
    // We do NOT set it manually — Hibernate handles it.
    @CreationTimestamp
    @Column(name = "checked_at", nullable = false, updatable = false)
    private OffsetDateTime checkedAt;

    @Column(name = "is_up", nullable = false)
    private Boolean isUp;

    // Null if connection failed entirely (no HTTP response received)
    @Column(name = "status_code")
    private Integer statusCode;

    // Null if connection failed entirely
    @Column(name = "latency_ms")
    private Long latencyMs;

    // Null if isUp = true (no error when healthy)
    @Column(name = "error_msg", length = 500)
    private String errorMsg;
}