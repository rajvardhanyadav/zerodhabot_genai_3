package com.tradingbot.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Entity for persisting every V3 neutral market detection evaluation.
 *
 * <p>Captures the complete 3-layer detection result (regime, microstructure, breakout risk)
 * including per-signal pass/fail, raw numeric values, and the final tradable decision.
 * Enables retrospective analysis of market detection accuracy by date.</p>
 *
 * <p>Volume estimate: ~750 rows/day (1 eval per 30s × 6.25 market hours).
 * Retention default: 30 days (~22.5K rows).</p>
 *
 * @since 4.3
 */
@Entity
@Table(name = "neutral_market_logs", indexes = {
        @Index(name = "idx_nml_trading_date", columnList = "tradingDate"),
        @Index(name = "idx_nml_instrument_evaluated", columnList = "instrument, evaluatedAt"),
        @Index(name = "idx_nml_evaluated_at", columnList = "evaluatedAt")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NeutralMarketLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ==================== EVALUATION CONTEXT ====================

    /** Instrument evaluated (e.g., "NIFTY") */
    @Column(nullable = false, length = 20)
    private String instrument;

    /** Timestamp when evaluation started (IST) */
    @Column(nullable = false)
    private LocalDateTime evaluatedAt;

    /** Trading date for date-based partitioning and querying */
    @Column(nullable = false)
    private LocalDate tradingDate;

    /** NIFTY spot price at time of evaluation */
    @Column(nullable = false)
    private Double spotPrice;

    /** Computed VWAP value at time of evaluation */
    private Double vwapValue;

    // ==================== FINAL DECISION ====================

    /** Final tradable decision (true = TRADE, false = SKIP) */
    @Column(nullable = false)
    private Boolean tradable;

    /** Market regime classification: STRONG_NEUTRAL, WEAK_NEUTRAL, TRENDING */
    @Column(nullable = false, length = 20)
    private String regime;

    /** Breakout risk level: LOW, MEDIUM, HIGH */
    @Column(nullable = false, length = 10)
    private String breakoutRisk;

    /** Veto reason if trade was blocked (e.g., BREAKOUT_HIGH, EXCESSIVE_RANGE), null if tradable */
    @Column(length = 30)
    private String vetoReason;

    // ==================== SCORES ====================

    /** Regime layer score (0–9) */
    @Column(nullable = false)
    private Integer regimeScore;

    /** Microstructure layer score (0–5) */
    @Column(nullable = false)
    private Integer microScore;

    /** Combined final score = regime + micro + time adjustment (0–15) */
    @Column(nullable = false)
    private Integer finalScore;

    /** Confidence as fraction (0.0–1.0) derived from finalScore / 15.0 */
    @Column(nullable = false)
    private Double confidence;

    /** Time-based adjustment applied to final score */
    private Integer timeAdjustment;

    /** Whether microstructure layer independently signals tradable */
    @Column(nullable = false)
    private Boolean microTradable;

    // ==================== REGIME LAYER SIGNALS (R1–R5) ====================

    /** R1: VWAP Proximity — |price − VWAP| / VWAP < threshold */
    @Column(nullable = false)
    private Boolean vwapProximityPassed;

    /** R1: Actual VWAP deviation fraction */
    private Double vwapDeviation;

    /** R2: Range Compression — (highest-lowest)/price over last N candles < threshold */
    @Column(nullable = false)
    private Boolean rangeCompressionPassed;

    /** R2: Actual range fraction */
    private Double rangeFraction;

    /** R3: Price Oscillation — direction reversals >= minimum */
    @Column(nullable = false)
    private Boolean oscillationPassed;

    /** R3: Actual reversal count */
    private Integer oscillationReversals;

    /** R4: ADX Trend Strength — ADX < threshold (ranging market) */
    @Column(nullable = false)
    private Boolean adxPassed;

    /** R4: Actual ADX value */
    private Double adxValue;

    /** R5: Gamma Pin — spot near max-OI strike (expiry day only) */
    @Column(nullable = false)
    private Boolean gammaPinPassed;

    /** Whether evaluation occurred on an expiry day */
    @Column(nullable = false)
    private Boolean expiryDay;

    // ==================== MICROSTRUCTURE LAYER SIGNALS (M1–M3) ====================

    /** M1: VWAP Pullback Momentum — deviation from VWAP then reverting */
    @Column(nullable = false)
    private Boolean microVwapPullbackPassed;

    /** M2: High-Frequency Oscillation — direction flips with small amplitude */
    @Column(nullable = false)
    private Boolean microHfOscillationPassed;

    /** M3: Micro Range Stability — tight range in short window */
    @Column(nullable = false)
    private Boolean microRangeStabilityPassed;

    // ==================== BREAKOUT / VETO GATE SIGNALS ====================

    /** Breakout risk is LOW (safe to trade) */
    @Column(nullable = false)
    private Boolean breakoutRiskLow;

    /** Excessive range veto NOT triggered (safe) */
    @Column(nullable = false)
    private Boolean excessiveRangeSafe;

    // ==================== SUMMARY & PERFORMANCE ====================

    /** Human-readable summary of all signals (compact format from V3) */
    @Column(length = 500)
    private String summary;

    /** Evaluation duration in milliseconds */
    private Long evaluationDurationMs;

    // ==================== AUDIT ====================

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (tradingDate == null) {
            tradingDate = LocalDate.now();
        }
    }
}

