package com.tradingbot.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Entity tracking timing metrics for order placements.
 * Critical for HFT analysis - captures latency data for optimization.
 */
@Entity
@Table(name = "order_timing_metrics", indexes = {
    @Index(name = "idx_timing_order_id", columnList = "orderId"),
    @Index(name = "idx_timing_user_date", columnList = "userId, orderTimestamp"),
    @Index(name = "idx_timing_execution_id", columnList = "executionId")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderTimingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String orderId;

    @Column(length = 64)
    private String exchangeOrderId;

    @Column(length = 64)
    private String executionId; // Link to strategy execution

    @Column(nullable = false, length = 64)
    private String userId;

    @Column(nullable = false, length = 50)
    private String tradingSymbol;

    @Column(nullable = false, length = 10)
    private String transactionType; // BUY or SELL

    @Column(nullable = false, length = 10)
    private String orderType; // MARKET, LIMIT, etc.

    // Timing metrics (all in milliseconds for precision)

    // When the order was initiated in our system
    @Column
    private LocalDateTime orderInitiatedAt;

    // When the order was sent to broker/exchange
    @Column
    private LocalDateTime orderSentAt;

    // When we received acknowledgement from broker
    @Column
    private LocalDateTime orderAcknowledgedAt;

    // When the order was executed (filled)
    @Column
    private LocalDateTime orderExecutedAt;

    // When we received the execution confirmation
    @Column
    private LocalDateTime executionConfirmedAt;

    // Calculated latencies (in milliseconds)

    // Time from initiation to sending
    @Column
    private Long initiationToSendMs;

    // Time from sending to acknowledgement (network + broker processing)
    @Column
    private Long sendToAckMs;

    // Time from acknowledgement to execution (exchange matching)
    @Column
    private Long ackToExecutionMs;

    // Total end-to-end latency
    @Column
    private Long totalLatencyMs;

    // Price slippage (difference from expected price)
    @Column(precision = 15, scale = 4)
    private BigDecimal expectedPrice;

    @Column(precision = 15, scale = 4)
    private BigDecimal actualPrice;

    @Column(precision = 10, scale = 4)
    private BigDecimal slippagePoints;

    @Column(precision = 10, scale = 4)
    private BigDecimal slippagePercent;

    // Order status at the time of measurement
    @Column(length = 20)
    private String orderStatus;

    // Trading mode
    @Column(nullable = false, length = 10)
    private String tradingMode; // PAPER or LIVE

    // Context
    @Column(length = 50)
    private String orderContext; // STRATEGY_ENTRY, STRATEGY_EXIT, MANUAL, etc.

    // Audit
    @Column
    private LocalDateTime orderTimestamp;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        calculateLatencies();
    }

    /**
     * Calculate latency metrics from timestamp data
     */
    public void calculateLatencies() {
        if (orderInitiatedAt != null && orderSentAt != null) {
            initiationToSendMs = java.time.Duration.between(orderInitiatedAt, orderSentAt).toMillis();
        }

        if (orderSentAt != null && orderAcknowledgedAt != null) {
            sendToAckMs = java.time.Duration.between(orderSentAt, orderAcknowledgedAt).toMillis();
        }

        if (orderAcknowledgedAt != null && orderExecutedAt != null) {
            ackToExecutionMs = java.time.Duration.between(orderAcknowledgedAt, orderExecutedAt).toMillis();
        }

        if (orderInitiatedAt != null && orderExecutedAt != null) {
            totalLatencyMs = java.time.Duration.between(orderInitiatedAt, orderExecutedAt).toMillis();
        }

        // Calculate slippage
        if (expectedPrice != null && actualPrice != null) {
            slippagePoints = actualPrice.subtract(expectedPrice);
            if (expectedPrice.compareTo(BigDecimal.ZERO) != 0) {
                slippagePercent = slippagePoints
                        .divide(expectedPrice, 6, java.math.RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
            }
        }
    }
}

