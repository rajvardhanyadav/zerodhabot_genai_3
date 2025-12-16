-- V2__User_Sessions_Table.sql
-- Database schema for user session persistence
-- Enables Cloud Run session recovery across container instances
-- Version: 2.0
-- Date: 2025-12-16

-- =============================================
-- Table: user_sessions
-- Stores Kite session data for cross-instance recovery
-- =============================================
CREATE TABLE IF NOT EXISTS user_sessions (
    id BIGSERIAL PRIMARY KEY,

    -- User identification
    user_id VARCHAR(64) NOT NULL UNIQUE,
    kite_user_id VARCHAR(64) NOT NULL,

    -- Authentication tokens
    access_token VARCHAR(256) NOT NULL,
    public_token VARCHAR(256),

    -- Session lifecycle timestamps
    created_at TIMESTAMP NOT NULL,
    last_accessed_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,

    -- Session status
    active BOOLEAN NOT NULL DEFAULT true,

    -- Optimistic locking
    version BIGINT DEFAULT 0
);

-- Primary lookup index (most frequent query)
CREATE INDEX IF NOT EXISTS idx_user_session_user_id ON user_sessions(user_id);

-- Index for finding expired sessions (cleanup queries)
CREATE INDEX IF NOT EXISTS idx_user_session_expires_at ON user_sessions(expires_at);

-- Index for finding active sessions (monitoring queries)
CREATE INDEX IF NOT EXISTS idx_user_session_active ON user_sessions(active);

-- Composite index for efficient active session lookup
CREATE INDEX IF NOT EXISTS idx_user_session_user_active ON user_sessions(user_id, active);

-- Comments for documentation
COMMENT ON TABLE user_sessions IS 'Stores Kite Connect sessions for Cloud Run cross-instance recovery';
COMMENT ON COLUMN user_sessions.user_id IS 'Application user ID (from X-User-Id header or Kite userId)';
COMMENT ON COLUMN user_sessions.kite_user_id IS 'Original Kite user ID from authentication response';
COMMENT ON COLUMN user_sessions.access_token IS 'Kite access token for API authentication';
COMMENT ON COLUMN user_sessions.public_token IS 'Kite public token for WebSocket connections';
COMMENT ON COLUMN user_sessions.expires_at IS 'Estimated session expiration (soft expiration, ~18 hours)';
COMMENT ON COLUMN user_sessions.active IS 'Session status - false after logout or invalidation';

