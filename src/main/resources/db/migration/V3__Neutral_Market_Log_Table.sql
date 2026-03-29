-- V3__Neutral_Market_Log_Table.sql
-- Persistence for V3 neutral market detection evaluations
-- Enables retrospective analysis of the 3-layer detection engine
-- Volume: ~750 rows/day (1 eval/30s × 6.25 market hours)
-- Version: 3.0
-- Date: 2026-03-29

-- =============================================
-- Table: neutral_market_logs
-- Stores every V3 neutral market evaluation
-- =============================================
CREATE TABLE IF NOT EXISTS neutral_market_logs (
    id BIGSERIAL PRIMARY KEY,

    -- Evaluation context
    instrument VARCHAR(20) NOT NULL,
    evaluated_at TIMESTAMP NOT NULL,
    trading_date DATE NOT NULL,
    spot_price DOUBLE PRECISION NOT NULL,
    vwap_value DOUBLE PRECISION,

    -- Final decision
    tradable BOOLEAN NOT NULL,
    regime VARCHAR(20) NOT NULL,
    breakout_risk VARCHAR(10) NOT NULL,
    veto_reason VARCHAR(30),

    -- Scores
    regime_score INTEGER NOT NULL,
    micro_score INTEGER NOT NULL,
    final_score INTEGER NOT NULL,
    confidence DOUBLE PRECISION NOT NULL,
    time_adjustment INTEGER,
    micro_tradable BOOLEAN NOT NULL,

    -- Regime layer signals (R1–R5)
    vwap_proximity_passed BOOLEAN NOT NULL,
    vwap_deviation DOUBLE PRECISION,
    range_compression_passed BOOLEAN NOT NULL,
    range_fraction DOUBLE PRECISION,
    oscillation_passed BOOLEAN NOT NULL,
    oscillation_reversals INTEGER,
    adx_passed BOOLEAN NOT NULL,
    adx_value DOUBLE PRECISION,
    gamma_pin_passed BOOLEAN NOT NULL,
    expiry_day BOOLEAN NOT NULL,

    -- Microstructure layer signals (M1–M3)
    micro_vwap_pullback_passed BOOLEAN NOT NULL,
    micro_hf_oscillation_passed BOOLEAN NOT NULL,
    micro_range_stability_passed BOOLEAN NOT NULL,

    -- Breakout / veto gate signals
    breakout_risk_low BOOLEAN NOT NULL,
    excessive_range_safe BOOLEAN NOT NULL,

    -- Summary & performance
    summary VARCHAR(500),
    evaluation_duration_ms BIGINT,

    -- Audit
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Primary analysis index: query by trading date
CREATE INDEX IF NOT EXISTS idx_nml_trading_date ON neutral_market_logs(trading_date);

-- Composite index: instrument + evaluated_at for instrument-specific time queries
CREATE INDEX IF NOT EXISTS idx_nml_instrument_evaluated ON neutral_market_logs(instrument, evaluated_at);

-- Index for cleanup queries (delete by timestamp)
CREATE INDEX IF NOT EXISTS idx_nml_evaluated_at ON neutral_market_logs(evaluated_at);

-- Comments
COMMENT ON TABLE neutral_market_logs IS 'V3 neutral market detection evaluation logs for historical analysis';
COMMENT ON COLUMN neutral_market_logs.regime IS 'Market regime: STRONG_NEUTRAL, WEAK_NEUTRAL, TRENDING';
COMMENT ON COLUMN neutral_market_logs.breakout_risk IS 'Breakout risk level: LOW, MEDIUM, HIGH';
COMMENT ON COLUMN neutral_market_logs.veto_reason IS 'Reason trade was blocked: BREAKOUT_HIGH, EXCESSIVE_RANGE, or NULL if tradable';
COMMENT ON COLUMN neutral_market_logs.regime_score IS 'Regime layer score (0-9)';
COMMENT ON COLUMN neutral_market_logs.micro_score IS 'Microstructure layer score (0-5)';
COMMENT ON COLUMN neutral_market_logs.final_score IS 'Combined score = regime + micro + time adjustment (0-15)';
COMMENT ON COLUMN neutral_market_logs.confidence IS 'Confidence fraction (0.0-1.0) = finalScore / 15.0';

