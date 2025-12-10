package com.tradingbot.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Entity for storing alert history.
 * Tracks all alerts sent via Telegram or other channels.
 */
@Entity
@Table(name = "alert_history", indexes = {
    @Index(name = "idx_alert_timestamp", columnList = "timestamp"),
    @Index(name = "idx_alert_type", columnList = "alertType"),
    @Index(name = "idx_alert_strategy", columnList = "strategyName")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AlertHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDateTime timestamp;

    @Column(nullable = false, length = 50)
    private String alertType; // TRADE_EXECUTED, STOP_LOSS_HIT, TARGET_HIT, ERROR, DAILY_SUMMARY, MTM_BREACH

    @Column(length = 100)
    private String strategyName;

    @Column(length = 50)
    private String symbol;

    @Column(length = 2000)
    private String message;

    @Column(length = 20)
    private String severity; // INFO, WARNING, CRITICAL

    @Column(length = 64)
    private String userId;

    private Boolean telegramSent;

    private String telegramMessageId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (timestamp == null) {
            timestamp = LocalDateTime.now();
        }
    }
}

