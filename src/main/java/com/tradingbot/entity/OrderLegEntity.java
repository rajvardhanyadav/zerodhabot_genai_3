package com.tradingbot.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Entity representing an individual order leg within a strategy execution.
 * Tracks the complete lifecycle of a single leg (CE or PE in a straddle).
 */
@Entity
@Table(name = "order_legs", indexes = {
    @Index(name = "idx_leg_order_id", columnList = "orderId"),
    @Index(name = "idx_leg_symbol", columnList = "tradingSymbol")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderLegEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "strategy_execution_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private StrategyExecutionEntity strategyExecution;

    @Column(nullable = false, length = 64)
    private String orderId;

    @Column(nullable = false, length = 50)
    private String tradingSymbol;

    @Column(length = 10)
    private String optionType; // CE or PE

    @Column
    private Integer quantity;

    // Entry details
    @Column(precision = 15, scale = 4)
    private BigDecimal entryPrice;

    @Column(length = 10)
    private String entryTransactionType; // BUY or SELL

    @Column
    private LocalDateTime entryTimestamp;

    @Column
    private Long entryLatencyMs;

    // Exit details
    @Column(length = 64)
    private String exitOrderId;

    @Column(length = 10)
    private String exitTransactionType;

    @Column
    private Integer exitQuantity;

    @Column(precision = 15, scale = 4)
    private BigDecimal exitPrice;

    @Column
    private LocalDateTime exitRequestedAt;

    @Column
    private LocalDateTime exitTimestamp;

    @Column
    private Long exitLatencyMs;

    @Column(length = 20)
    private String exitStatus;

    @Column(length = 255)
    private String exitMessage;

    // P&L
    @Column(precision = 15, scale = 4)
    private BigDecimal realizedPnl;

    // Lifecycle state
    @Column(nullable = false, length = 20)
    private String lifecycleState; // OPEN, EXIT_PENDING, EXITED, EXIT_FAILED

    // Greeks at entry (for analysis)
    @Column(precision = 10, scale = 6)
    private BigDecimal deltaAtEntry;

    @Column(precision = 10, scale = 6)
    private BigDecimal gammaAtEntry;

    @Column(precision = 10, scale = 6)
    private BigDecimal thetaAtEntry;

    @Column(precision = 10, scale = 6)
    private BigDecimal vegaAtEntry;

    @Column(precision = 10, scale = 6)
    private BigDecimal ivAtEntry;

    // Audit
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

