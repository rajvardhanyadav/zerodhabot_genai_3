package com.tradingbot.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Entity representing a snapshot of Greeks/Delta values at a specific point in time.
 * Used for analyzing option pricing and strategy decisions retrospectively.
 */
@Entity
@Table(name = "delta_snapshots", indexes = {
    @Index(name = "idx_delta_symbol_time", columnList = "tradingSymbol, snapshotTimestamp"),
    @Index(name = "idx_delta_instrument_type", columnList = "instrumentType, snapshotTimestamp"),
    @Index(name = "idx_delta_execution_id", columnList = "executionId")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeltaSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64)
    private String executionId; // Optional: link to strategy execution

    @Column(length = 64)
    private String userId;

    // Instrument details
    @Column(nullable = false, length = 20)
    private String instrumentType; // NIFTY, BANKNIFTY

    @Column(length = 50)
    private String tradingSymbol;

    @Column
    private Long instrumentToken;

    @Column(length = 10)
    private String optionType; // CE, PE

    @Column(precision = 15, scale = 2)
    private BigDecimal strikePrice;

    @Column(length = 20)
    private String expiry;

    // Underlying spot price
    @Column(nullable = false, precision = 15, scale = 4)
    private BigDecimal spotPrice;

    // Option price at snapshot
    @Column(precision = 15, scale = 4)
    private BigDecimal optionPrice;

    // ATM strike calculated
    @Column(precision = 15, scale = 2)
    private BigDecimal atmStrike;

    // Greeks
    @Column(precision = 10, scale = 6)
    private BigDecimal delta;

    @Column(precision = 10, scale = 6)
    private BigDecimal gamma;

    @Column(precision = 10, scale = 6)
    private BigDecimal theta;

    @Column(precision = 10, scale = 6)
    private BigDecimal vega;

    @Column(precision = 10, scale = 6)
    private BigDecimal rho;

    // Implied Volatility
    @Column(precision = 10, scale = 6)
    private BigDecimal impliedVolatility;

    // Time to expiry in years
    @Column(precision = 10, scale = 8)
    private BigDecimal timeToExpiry;

    // Risk-free rate used
    @Column(precision = 10, scale = 6)
    private BigDecimal riskFreeRate;

    // Snapshot context
    @Column(length = 30)
    private String snapshotType; // ENTRY, EXIT, PERIODIC, MANUAL

    @Column(nullable = false)
    private LocalDateTime snapshotTimestamp;

    // Audit
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (snapshotTimestamp == null) {
            snapshotTimestamp = LocalDateTime.now();
        }
    }
}

