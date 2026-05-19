package com.api_loader.api_monitor.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "monitored_endpoint",
        indexes = @Index(name = "idx_monitored_endpoint_user_id",
                columnList = "user_id"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MonitoredEndpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Which user owns this monitor
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_monitored_endpoint_user"))
    private User user;

    @Column(nullable = false, length = 2048)
    private String url;

    @Column(nullable = false, length = 10)
    private String method;

    @Column(name = "interval_seconds", nullable = false)
    private int intervalSeconds;

    @Column(name = "expected_status_code", nullable = false)
    private int expectedStatusCode;

    // false = paused, true = actively monitored
    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = OffsetDateTime.now();
        if (this.expectedStatusCode == 0) {
            this.expectedStatusCode = 200;
        }
        this.enabled = true;
    }
}