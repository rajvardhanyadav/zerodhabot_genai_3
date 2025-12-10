package com.tradingbot.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Entity representing an end-of-day position snapshot.
 * Used for historical analysis and position tracking across days.
 */
@Entity
@Table(name = "position_snapshots", indexes = {
    @Index(name = "idx_position_user_date", columnList = "userId, snapshotDate"),
    @Index(name = "idx_position_symbol", columnList = "tradingSymbol, snapshotDate")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PositionSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String userId;

    @Column(nullable = false)
    private LocalDate snapshotDate;

    // Instrument details
    @Column(nullable = false, length = 50)
    private String tradingSymbol;

    @Column(nullable = false, length = 10)
    private String exchange;

    @Column
    private Long instrumentToken;

    @Column(nullable = false, length = 10)
    private String product; // CNC, MIS, NRML

    // Position details
    @Column
    private Integer quantity;

    @Column
    private Integer overnightQuantity;

    @Column
    private Integer multiplier;

    // Buy side
    @Column
    private Integer buyQuantity;

    @Column(precision = 15, scale = 4)
    private BigDecimal buyPrice;

    @Column(precision = 18, scale = 4)
    private BigDecimal buyValue;

    // Sell side
    @Column
    private Integer sellQuantity;

    @Column(precision = 15, scale = 4)
    private BigDecimal sellPrice;

    @Column(precision = 18, scale = 4)
    private BigDecimal sellValue;

    // Day trading
    @Column
    private Integer dayBuyQuantity;

    @Column(precision = 15, scale = 4)
    private BigDecimal dayBuyPrice;

    @Column(precision = 18, scale = 4)
    private BigDecimal dayBuyValue;

    @Column
    private Integer daySellQuantity;

    @Column(precision = 15, scale = 4)
    private BigDecimal daySellPrice;

    @Column(precision = 18, scale = 4)
    private BigDecimal daySellValue;

    // P&L
    @Column(precision = 15, scale = 4)
    private BigDecimal pnl;

    @Column(precision = 15, scale = 4)
    private BigDecimal realised;

    @Column(precision = 15, scale = 4)
    private BigDecimal unrealised;

    @Column(precision = 15, scale = 4)
    private BigDecimal m2m;

    // Price data
    @Column(precision = 15, scale = 4)
    private BigDecimal averagePrice;

    @Column(precision = 15, scale = 4)
    private BigDecimal lastPrice;

    @Column(precision = 15, scale = 4)
    private BigDecimal closePrice;

    @Column(name = "\"value\"", precision = 18, scale = 4)
    private BigDecimal value;

    // Trading mode
    @Column(nullable = false, length = 10)
    private String tradingMode; // PAPER or LIVE

    // Snapshot time
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
        if (snapshotDate == null) {
            snapshotDate = LocalDate.now();
        }
    }
}

