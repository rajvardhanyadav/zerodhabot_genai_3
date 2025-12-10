-- V1__Initial_Schema.sql
-- Initial database schema for Zerodha Trading Bot persistence layer
-- Version: 1.0
-- Date: 2025-12-10

-- =============================================
-- Table: trades
-- Stores individual trade executions
-- =============================================
CREATE TABLE IF NOT EXISTS trades (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    order_id VARCHAR(64) NOT NULL,
    execution_id VARCHAR(64),
    exchange_order_id VARCHAR(64),

    -- Instrument details
    trading_symbol VARCHAR(50) NOT NULL,
    exchange VARCHAR(10) NOT NULL,
    instrument_token BIGINT,
    option_type VARCHAR(10),
    strike_price DECIMAL(15,2),
    expiry VARCHAR(20),

    -- Trade details
    transaction_type VARCHAR(10) NOT NULL,
    order_type VARCHAR(10) NOT NULL,
    product VARCHAR(10) NOT NULL,
    quantity INTEGER NOT NULL,

    -- Entry details
    entry_price DECIMAL(15,4),
    entry_timestamp TIMESTAMP,
    entry_latency_ms BIGINT,

    -- Exit details
    exit_price DECIMAL(15,4),
    exit_timestamp TIMESTAMP,
    exit_order_id VARCHAR(64),
    exit_latency_ms BIGINT,

    -- P&L
    realized_pnl DECIMAL(15,4),
    unrealized_pnl DECIMAL(15,4),

    -- Charges
    brokerage DECIMAL(10,4),
    stt DECIMAL(10,4),
    exchange_charges DECIMAL(10,4),
    gst DECIMAL(10,4),
    sebi_charges DECIMAL(10,4),
    stamp_duty DECIMAL(10,4),
    total_charges DECIMAL(10,4),

    -- Status
    status VARCHAR(20) NOT NULL,
    status_message VARCHAR(255),
    trading_mode VARCHAR(10) NOT NULL,
    trading_date DATE NOT NULL,

    -- Audit
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for trades
CREATE INDEX IF NOT EXISTS idx_trade_user_date ON trades(user_id, trading_date);
CREATE INDEX IF NOT EXISTS idx_trade_execution_id ON trades(execution_id);
CREATE INDEX IF NOT EXISTS idx_trade_symbol ON trades(trading_symbol);
CREATE INDEX IF NOT EXISTS idx_trade_timestamp ON trades(entry_timestamp);

-- =============================================
-- Table: strategy_executions
-- Stores strategy execution lifecycle
-- =============================================
CREATE TABLE IF NOT EXISTS strategy_executions (
    id BIGSERIAL PRIMARY KEY,
    execution_id VARCHAR(64) NOT NULL UNIQUE,
    root_execution_id VARCHAR(64),
    parent_execution_id VARCHAR(64),
    user_id VARCHAR(64) NOT NULL,

    -- Strategy details
    strategy_type VARCHAR(30) NOT NULL,
    instrument_type VARCHAR(20) NOT NULL,
    expiry VARCHAR(20),

    -- Execution state
    status VARCHAR(20) NOT NULL,
    completion_reason VARCHAR(50),
    message VARCHAR(500),

    -- Configuration
    stop_loss_points DECIMAL(10,4),
    target_points DECIMAL(10,4),
    lots INTEGER,

    -- Financial metrics
    entry_price DECIMAL(15,4),
    exit_price DECIMAL(15,4),
    realized_pnl DECIMAL(15,4),
    total_charges DECIMAL(15,4),
    spot_price_at_entry DECIMAL(15,4),
    atm_strike_used DECIMAL(15,2),

    -- Trading mode
    trading_mode VARCHAR(10) NOT NULL,
    auto_restart_count INTEGER,

    -- Timestamps
    started_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    duration_ms BIGINT,

    -- Audit
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for strategy_executions
CREATE INDEX IF NOT EXISTS idx_strategy_user_date ON strategy_executions(user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_strategy_execution_id ON strategy_executions(execution_id);
CREATE INDEX IF NOT EXISTS idx_strategy_root_id ON strategy_executions(root_execution_id);
CREATE INDEX IF NOT EXISTS idx_strategy_status ON strategy_executions(status);

-- =============================================
-- Table: order_legs
-- Stores individual order legs within a strategy
-- =============================================
CREATE TABLE IF NOT EXISTS order_legs (
    id BIGSERIAL PRIMARY KEY,
    strategy_execution_id BIGINT NOT NULL REFERENCES strategy_executions(id) ON DELETE CASCADE,
    order_id VARCHAR(64) NOT NULL,
    trading_symbol VARCHAR(50) NOT NULL,
    option_type VARCHAR(10),
    quantity INTEGER,

    -- Entry details
    entry_price DECIMAL(15,4),
    entry_transaction_type VARCHAR(10),
    entry_timestamp TIMESTAMP,
    entry_latency_ms BIGINT,

    -- Exit details
    exit_order_id VARCHAR(64),
    exit_transaction_type VARCHAR(10),
    exit_quantity INTEGER,
    exit_price DECIMAL(15,4),
    exit_requested_at TIMESTAMP,
    exit_timestamp TIMESTAMP,
    exit_latency_ms BIGINT,
    exit_status VARCHAR(20),
    exit_message VARCHAR(255),

    -- P&L
    realized_pnl DECIMAL(15,4),
    lifecycle_state VARCHAR(20) NOT NULL,

    -- Greeks at entry
    delta_at_entry DECIMAL(10,6),
    gamma_at_entry DECIMAL(10,6),
    theta_at_entry DECIMAL(10,6),
    vega_at_entry DECIMAL(10,6),
    iv_at_entry DECIMAL(10,6),

    -- Audit
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for order_legs
CREATE INDEX IF NOT EXISTS idx_leg_order_id ON order_legs(order_id);
CREATE INDEX IF NOT EXISTS idx_leg_symbol ON order_legs(trading_symbol);

-- =============================================
-- Table: delta_snapshots
-- Stores Greeks/Delta snapshots for analysis
-- =============================================
CREATE TABLE IF NOT EXISTS delta_snapshots (
    id BIGSERIAL PRIMARY KEY,
    execution_id VARCHAR(64),
    user_id VARCHAR(64),

    -- Instrument details
    instrument_type VARCHAR(20) NOT NULL,
    trading_symbol VARCHAR(50),
    instrument_token BIGINT,
    option_type VARCHAR(10),
    strike_price DECIMAL(15,2),
    expiry VARCHAR(20),

    -- Prices
    spot_price DECIMAL(15,4) NOT NULL,
    option_price DECIMAL(15,4),
    atm_strike DECIMAL(15,2),

    -- Greeks
    delta DECIMAL(10,6),
    gamma DECIMAL(10,6),
    theta DECIMAL(10,6),
    vega DECIMAL(10,6),
    rho DECIMAL(10,6),
    implied_volatility DECIMAL(10,6),
    time_to_expiry DECIMAL(10,8),
    risk_free_rate DECIMAL(10,6),

    -- Snapshot context
    snapshot_type VARCHAR(30),
    snapshot_timestamp TIMESTAMP NOT NULL,

    -- Audit
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for delta_snapshots
CREATE INDEX IF NOT EXISTS idx_delta_symbol_time ON delta_snapshots(trading_symbol, snapshot_timestamp);
CREATE INDEX IF NOT EXISTS idx_delta_instrument_type ON delta_snapshots(instrument_type, snapshot_timestamp);
CREATE INDEX IF NOT EXISTS idx_delta_execution_id ON delta_snapshots(execution_id);

-- =============================================
-- Table: daily_pnl_summary
-- Aggregates daily P&L for users
-- =============================================
CREATE TABLE IF NOT EXISTS daily_pnl_summary (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    trading_date DATE NOT NULL,

    -- P&L Summary
    realized_pnl DECIMAL(15,4) DEFAULT 0,
    unrealized_pnl DECIMAL(15,4) DEFAULT 0,
    gross_pnl DECIMAL(15,4) DEFAULT 0,
    net_pnl DECIMAL(15,4) DEFAULT 0,

    -- Charges
    total_brokerage DECIMAL(12,4) DEFAULT 0,
    total_stt DECIMAL(12,4) DEFAULT 0,
    total_exchange_charges DECIMAL(12,4) DEFAULT 0,
    total_gst DECIMAL(12,4) DEFAULT 0,
    total_charges DECIMAL(12,4) DEFAULT 0,

    -- Statistics
    total_trades INTEGER DEFAULT 0,
    winning_trades INTEGER DEFAULT 0,
    losing_trades INTEGER DEFAULT 0,
    break_even_trades INTEGER DEFAULT 0,
    win_rate DECIMAL(5,2) DEFAULT 0,
    avg_pnl_per_trade DECIMAL(15,4) DEFAULT 0,
    best_trade_pnl DECIMAL(15,4),
    worst_trade_pnl DECIMAL(15,4),

    -- Strategy stats
    strategy_executions INTEGER DEFAULT 0,
    successful_strategies INTEGER DEFAULT 0,
    failed_strategies INTEGER DEFAULT 0,

    -- Volume
    total_turnover DECIMAL(18,4) DEFAULT 0,
    total_quantity_traded INTEGER DEFAULT 0,

    -- Trading mode
    trading_mode VARCHAR(10) NOT NULL,

    -- Market context
    nifty_open DECIMAL(15,4),
    nifty_close DECIMAL(15,4),
    nifty_change_percent DECIMAL(10,4),
    end_of_day_balance DECIMAL(18,4),

    -- Audit
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    UNIQUE(user_id, trading_date, trading_mode)
);

-- Indexes for daily_pnl_summary
CREATE INDEX IF NOT EXISTS idx_daily_user_date ON daily_pnl_summary(user_id, trading_date);
CREATE INDEX IF NOT EXISTS idx_daily_date ON daily_pnl_summary(trading_date);

-- =============================================
-- Table: position_snapshots
-- Stores EOD position snapshots
-- =============================================
CREATE TABLE IF NOT EXISTS position_snapshots (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    snapshot_date DATE NOT NULL,

    -- Instrument details
    trading_symbol VARCHAR(50) NOT NULL,
    exchange VARCHAR(10) NOT NULL,
    instrument_token BIGINT,
    product VARCHAR(10) NOT NULL,

    -- Position
    quantity INTEGER,
    overnight_quantity INTEGER,
    multiplier INTEGER,

    -- Buy side
    buy_quantity INTEGER,
    buy_price DECIMAL(15,4),
    buy_value DECIMAL(18,4),

    -- Sell side
    sell_quantity INTEGER,
    sell_price DECIMAL(15,4),
    sell_value DECIMAL(18,4),

    -- Day trading
    day_buy_quantity INTEGER,
    day_buy_price DECIMAL(15,4),
    day_buy_value DECIMAL(18,4),
    day_sell_quantity INTEGER,
    day_sell_price DECIMAL(15,4),
    day_sell_value DECIMAL(18,4),

    -- P&L
    pnl DECIMAL(15,4),
    realised DECIMAL(15,4),
    unrealised DECIMAL(15,4),
    m2m DECIMAL(15,4),

    -- Prices
    average_price DECIMAL(15,4),
    last_price DECIMAL(15,4),
    close_price DECIMAL(15,4),
    value DECIMAL(18,4),

    -- Metadata
    trading_mode VARCHAR(10) NOT NULL,
    snapshot_timestamp TIMESTAMP NOT NULL,

    -- Audit
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for position_snapshots
CREATE INDEX IF NOT EXISTS idx_position_user_date ON position_snapshots(user_id, snapshot_date);
CREATE INDEX IF NOT EXISTS idx_position_symbol ON position_snapshots(trading_symbol, snapshot_date);

-- =============================================
-- Table: order_timing_metrics
-- HFT latency analysis
-- =============================================
CREATE TABLE IF NOT EXISTS order_timing_metrics (
    id BIGSERIAL PRIMARY KEY,
    order_id VARCHAR(64) NOT NULL,
    exchange_order_id VARCHAR(64),
    execution_id VARCHAR(64),
    user_id VARCHAR(64) NOT NULL,
    trading_symbol VARCHAR(50) NOT NULL,
    transaction_type VARCHAR(10) NOT NULL,
    order_type VARCHAR(10) NOT NULL,

    -- Timing
    order_initiated_at TIMESTAMP,
    order_sent_at TIMESTAMP,
    order_acknowledged_at TIMESTAMP,
    order_executed_at TIMESTAMP,
    execution_confirmed_at TIMESTAMP,

    -- Latencies (ms)
    initiation_to_send_ms BIGINT,
    send_to_ack_ms BIGINT,
    ack_to_execution_ms BIGINT,
    total_latency_ms BIGINT,

    -- Slippage
    expected_price DECIMAL(15,4),
    actual_price DECIMAL(15,4),
    slippage_points DECIMAL(10,4),
    slippage_percent DECIMAL(10,4),

    -- Metadata
    order_status VARCHAR(20),
    trading_mode VARCHAR(10) NOT NULL,
    order_context VARCHAR(50),
    order_timestamp TIMESTAMP,

    -- Audit
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for order_timing_metrics
CREATE INDEX IF NOT EXISTS idx_timing_order_id ON order_timing_metrics(order_id);
CREATE INDEX IF NOT EXISTS idx_timing_user_date ON order_timing_metrics(user_id, order_timestamp);
CREATE INDEX IF NOT EXISTS idx_timing_execution_id ON order_timing_metrics(execution_id);

