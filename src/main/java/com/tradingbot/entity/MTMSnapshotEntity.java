package com.tradingbot.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Entity for storing MTM (Mark-to-Market) snapshots.
 * Tracks real-time P&L during trading sessions.
 */
@Entity
@Table(name = "mtm_snapshots", indexes = {
    @Index(name = "idx_mtm_timestamp", columnList = "timestamp"),
    @Index(name = "idx_mtm_date", columnList = "tradingDate"),
    @Index(name = "idx_mtm_user", columnList = "userId"),
    @Index(name = "idx_mtm_execution", columnList = "executionId")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MTMSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String userId;

    @Column(nullable = false)
    private LocalDateTime timestamp;

    @Column(nullable = false)
    private LocalDate tradingDate;

    @Column(length = 64)
    private String executionId;

    @Column(precision = 15, scale = 2)
    private BigDecimal totalMTM;

    @Column(precision = 15, scale = 2)
    private BigDecimal unrealizedPnL;

    @Column(precision = 15, scale = 2)
    private BigDecimal realizedPnL;

    @Column(precision = 15, scale = 2)
    private BigDecimal spotPrice;

    @Column(precision = 15, scale = 2)
    private BigDecimal maxMTM;

    @Column(precision = 15, scale = 2)
    private BigDecimal minMTM;

    @Column(precision = 10, scale = 4)
    private BigDecimal portfolioDelta;

    @Column(precision = 10, scale = 4)
    private BigDecimal portfolioGamma;

    @Column(precision = 10, scale = 4)
    private BigDecimal portfolioTheta;

    @Column(precision = 10, scale = 4)
    private BigDecimal portfolioVega;

    @Column(length = 20)
    private String tradingMode; // PAPER, LIVE

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (timestamp == null) {
            timestamp = LocalDateTime.now();
        }
        if (tradingDate == null) {
            tradingDate = LocalDate.now();
        }
    }
}

