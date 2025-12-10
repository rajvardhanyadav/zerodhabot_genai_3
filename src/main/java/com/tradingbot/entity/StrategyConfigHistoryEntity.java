package com.tradingbot.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Entity for tracking strategy configuration changes.
 * Provides audit trail for configuration modifications.
 */
@Entity
@Table(name = "strategy_config_history", indexes = {
    @Index(name = "idx_config_strategy", columnList = "strategyName"),
    @Index(name = "idx_config_timestamp", columnList = "changedAt"),
    @Index(name = "idx_config_user", columnList = "userId")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StrategyConfigHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String strategyName;

    @Column(length = 64)
    private String userId;

    @Column(nullable = false)
    private LocalDateTime changedAt;

    @Column(length = 50)
    private String changedBy; // USER, SYSTEM, API

    @Column(columnDefinition = "TEXT")
    private String configurationJson;

    @Column(precision = 10, scale = 2)
    private BigDecimal stopLossPoints;

    @Column(precision = 10, scale = 2)
    private BigDecimal targetPoints;

    private Integer lots;

    @Column(length = 20)
    private String instrumentType;

    @Column(length = 20)
    private String expiry;

    @Column(length = 20)
    private String tradingMode;

    private Boolean autoRestartEnabled;

    @Column(length = 500)
    private String changeDescription;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (changedAt == null) {
            changedAt = LocalDateTime.now();
        }
    }
}

