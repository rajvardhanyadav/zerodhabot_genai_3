package com.tradingbot.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Entity representing an individual trade execution.
 * Captures complete trade lifecycle including entry, exit, P&L, and charges.
 *
 * Optimized for HFT analysis with precise decimal handling and indexed fields.
 */
@Entity
@Table(name = "trades", indexes = {
    @Index(name = "idx_trade_user_date", columnList = "userId, tradingDate"),
    @Index(name = "idx_trade_execution_id", columnList = "executionId"),
    @Index(name = "idx_trade_symbol", columnList = "tradingSymbol"),
    @Index(name = "idx_trade_timestamp", columnList = "entryTimestamp")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TradeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String userId;

    @Column(nullable = false, length = 64)
    private String orderId;

    @Column(length = 64)
    private String executionId;

    @Column(length = 64)
    private String exchangeOrderId;

    // Instrument details
    @Column(nullable = false, length = 50)
    private String tradingSymbol;

    @Column(nullable = false, length = 10)
    private String exchange;

    @Column
    private Long instrumentToken;

    @Column(length = 10)
    private String optionType; // CE, PE, or null for futures/equity

    @Column(precision = 15, scale = 2)
    private BigDecimal strikePrice;

    @Column(length = 20)
    private String expiry;

    // Trade details
    @Column(nullable = false, length = 10)
    private String transactionType; // BUY or SELL

    @Column(nullable = false, length = 10)
    private String orderType; // MARKET, LIMIT, SL, SL-M

    @Column(nullable = false, length = 10)
    private String product; // CNC, MIS, NRML

    @Column(nullable = false)
    private Integer quantity;

    // Entry details
    @Column(precision = 15, scale = 4)
    private BigDecimal entryPrice;

    @Column
    private LocalDateTime entryTimestamp;

    @Column
    private Long entryLatencyMs; // Time from order placement to execution

    // Exit details (nullable for open trades)
    @Column(precision = 15, scale = 4)
    private BigDecimal exitPrice;

    @Column
    private LocalDateTime exitTimestamp;

    @Column(length = 64)
    private String exitOrderId;

    @Column
    private Long exitLatencyMs;

    // P&L details (using BigDecimal for precision in HFT)
    @Column(precision = 15, scale = 4)
    private BigDecimal realizedPnl;

    @Column(precision = 15, scale = 4)
    private BigDecimal unrealizedPnl;

    // Charges breakdown
    @Column(precision = 10, scale = 4)
    private BigDecimal brokerage;

    @Column(precision = 10, scale = 4)
    private BigDecimal stt;

    @Column(precision = 10, scale = 4)
    private BigDecimal exchangeCharges;

    @Column(precision = 10, scale = 4)
    private BigDecimal gst;

    @Column(precision = 10, scale = 4)
    private BigDecimal sebiCharges;

    @Column(precision = 10, scale = 4)
    private BigDecimal stampDuty;

    @Column(precision = 10, scale = 4)
    private BigDecimal totalCharges;

    // Status
    @Column(nullable = false, length = 20)
    private String status; // OPEN, CLOSED, CANCELLED, REJECTED

    @Column(length = 255)
    private String statusMessage;

    // Trading mode
    @Column(nullable = false, length = 10)
    private String tradingMode; // PAPER or LIVE

    // Date for daily aggregation
    @Column(nullable = false)
    private LocalDate tradingDate;

    // Audit fields
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (tradingDate == null) {
            tradingDate = LocalDate.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

