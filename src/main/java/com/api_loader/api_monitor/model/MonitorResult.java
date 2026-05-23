package com.api_loader.api_monitor.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

@Entity
@Table(name = "monitor_result")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MonitorResult {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "endpoint_id", nullable = false)
    private MonitoredEndpoint endpoint;
    
    @CreationTimestamp
    @Column(name = "checked_at", nullable = false)
    private OffsetDateTime checkedAt;
    
    @Column(name = "is_up", nullable = false)
    private Boolean isUp;
    
    @Column(name = "status_code")
    private Integer statusCode;
    
    @Column(name = "latency_ms")
    private Long latencyMs;
    
    @Column(name = "error_msg", length = 500)
    private String errorMsg;
}