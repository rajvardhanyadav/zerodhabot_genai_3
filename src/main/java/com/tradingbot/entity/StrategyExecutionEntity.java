package com.tradingbot.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Entity representing a strategy execution.
 * Tracks the complete lifecycle of a trading strategy including all order legs.
 */
@Entity
@Table(name = "strategy_executions", indexes = {
    @Index(name = "idx_strategy_user_date", columnList = "userId, createdAt"),
    @Index(name = "idx_strategy_execution_id", columnList = "executionId"),
    @Index(name = "idx_strategy_root_id", columnList = "rootExecutionId"),
    @Index(name = "idx_strategy_status", columnList = "status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StrategyExecutionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String executionId;

    @Column(length = 64)
    private String rootExecutionId;

    @Column(length = 64)
    private String parentExecutionId;

    @Column(nullable = false, length = 64)
    private String userId;

    // Strategy details
    @Column(nullable = false, length = 30)
    private String strategyType; // ATM_STRADDLE, SELL_ATM_STRADDLE, etc.

    @Column(nullable = false, length = 20)
    private String instrumentType; // NIFTY, BANKNIFTY

    @Column(length = 20)
    private String expiry;

    // Execution state
    @Column(nullable = false, length = 20)
    private String status; // EXECUTING, MONITORING, COMPLETED, FAILED, STOPPED

    @Column(length = 50)
    private String completionReason; // TARGET_HIT, STOPLOSS_HIT, MANUAL_STOP, etc.

    @Column(length = 500)
    private String message;

    // Configuration
    @Column(precision = 10, scale = 4)
    private BigDecimal stopLossPoints;

    @Column(precision = 10, scale = 4)
    private BigDecimal targetPoints;

    @Column
    private Integer lots;

    // Financial metrics
    @Column(precision = 15, scale = 4)
    private BigDecimal entryPrice;

    @Column(precision = 15, scale = 4)
    private BigDecimal exitPrice;

    @Column(precision = 15, scale = 4)
    private BigDecimal realizedPnl;

    @Column(precision = 15, scale = 4)
    private BigDecimal totalCharges;

    // Spot price at entry (for ATM calculation reference)
    @Column(precision = 15, scale = 4)
    private BigDecimal spotPriceAtEntry;

    @Column(precision = 15, scale = 4)
    private BigDecimal atmStrikeUsed;

    // Trading mode
    @Column(nullable = false, length = 10)
    private String tradingMode; // PAPER or LIVE

    // Auto-restart tracking
    @Column
    private Integer autoRestartCount;

    // Order legs (embedded as JSON or separate table)
    @OneToMany(mappedBy = "strategyExecution", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<OrderLegEntity> orderLegs = new ArrayList<>();

    // Timestamps
    @Column(nullable = false)
    private LocalDateTime startedAt;

    @Column
    private LocalDateTime completedAt;

    // Total duration in milliseconds
    @Column
    private Long durationMs;

    // Audit
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (startedAt == null) {
            startedAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        if (completedAt != null && startedAt != null) {
            durationMs = java.time.Duration.between(startedAt, completedAt).toMillis();
        }
    }

    public void addOrderLeg(OrderLegEntity leg) {
        orderLegs.add(leg);
        leg.setStrategyExecution(this);
    }

    public void removeOrderLeg(OrderLegEntity leg) {
        orderLegs.remove(leg);
        leg.setStrategyExecution(null);
    }
}

