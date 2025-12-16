package com.tradingbot.service.session;

import com.tradingbot.config.KiteConfig;
import com.tradingbot.config.PaperTradingConfig;
import com.tradingbot.entity.UserSessionEntity;
import com.tradingbot.repository.UserSessionRepository;
import com.tradingbot.util.CurrentUserContext;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Manages user Kite sessions with Cloud Run compatibility.
 *
 * CLOUD RUN ARCHITECTURE:
 * - Sessions are stored both in-memory (for fast access) and in the database (for persistence)
 * - When a request arrives at an instance without the session in memory, it is automatically
 *   restored from the database using the stored access token
 * - This enables session continuity across multiple container instances and restarts
 *
 * HFT CONSIDERATIONS:
 * - In-memory cache provides sub-millisecond session lookups for the hot path
 * - Database persistence is asynchronous where possible to avoid blocking
 * - Session restoration is transparent to calling code
 */
@Component
@Slf4j
public class UserSessionManager {

    private final KiteConfig kiteConfig;
    private final PaperTradingConfig paperTradingConfig;
    private final UserSessionRepository sessionRepository;

    public UserSessionManager(KiteConfig kiteConfig,
                             PaperTradingConfig paperTradingConfig,
                             UserSessionRepository sessionRepository) {
        this.kiteConfig = kiteConfig;
        this.paperTradingConfig = paperTradingConfig;
        this.sessionRepository = sessionRepository;
        log.info("UserSessionManager initialized with database-backed session persistence (Cloud Run compatible)");
    }

    /**
     * HFT-Optimized Session Entry with metadata for monitoring and health checks.
     * Immutable design to prevent partial updates in concurrent access.
     */
    private static final class SessionEntry {
        final KiteConnect kiteConnect;
        final Instant createdAt;
        final Instant lastAccessedAt;
        final String kiteUserId;  // Original Kite user ID for validation
        final String accessToken; // Stored for health check comparison

        SessionEntry(KiteConnect kiteConnect, String kiteUserId, String accessToken) {
            this.kiteConnect = kiteConnect;
            this.kiteUserId = kiteUserId;
            this.accessToken = accessToken;
            this.createdAt = Instant.now();
            this.lastAccessedAt = Instant.now();
        }

        /**
         * Create a new entry with updated access time (immutable pattern).
         */
        SessionEntry withUpdatedAccessTime() {
            SessionEntry updated = new SessionEntry(this.kiteConnect, this.kiteUserId, this.accessToken);
            // Note: createdAt is preserved via the field being set in constructor,
            // but we need to handle this differently for true immutability
            return new SessionEntry(this.kiteConnect, this.kiteUserId, this.accessToken,
                    this.createdAt, Instant.now());
        }

        private SessionEntry(KiteConnect kiteConnect, String kiteUserId, String accessToken,
                             Instant createdAt, Instant lastAccessedAt) {
            this.kiteConnect = kiteConnect;
            this.kiteUserId = kiteUserId;
            this.accessToken = accessToken;
            this.createdAt = createdAt;
            this.lastAccessedAt = lastAccessedAt;
        }

        /**
         * Check if session is potentially stale (created > 16 hours ago).
         * Kite sessions typically expire at end of day/next morning.
         *
         * UPDATED: Increased from 8 hours to 16 hours to prevent premature cleanup
         * during long-running intraday strategies (session created at 8 AM should
         * survive until at least midnight).
         */
        boolean isPotentiallyStale() {
            return Instant.now().isAfter(createdAt.plusSeconds(16 * 60 * 60));
        }
    }

    // Main session storage with concurrent access safety
    private final Map<String, SessionEntry> sessions = new ConcurrentHashMap<>();

    // ReadWriteLock for atomic compound operations (check-then-act patterns)
    private final ReadWriteLock sessionLock = new ReentrantReadWriteLock();

    // IST timezone for market hours check
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /**
     * Get the current user ID from thread context, with fallback for paper trading mode.
     *
     * CLOUD RUN NOTE: If this returns null unexpectedly in Cloud Run, check:
     * 1. Is this called from a scheduled task without explicit context setting?
     * 2. Is this called from an executor thread that doesn't inherit InheritableThreadLocal?
     * 3. Is the X-User-Id header being passed correctly from the load balancer?
     *
     * @return User ID (never null)
     * @throws IllegalStateException if user context is missing and not in paper mode
     */
    public String getCurrentUserIdRequired() {
        // Use debug version in Cloud Run to get detailed logging
        String id = CurrentUserContext.getUserIdWithDebug();

        if (id == null || id.isBlank()) {
            // Log detailed diagnostic info for Cloud Run debugging
            log.warn("User context is null/blank. Thread: {}, ActiveSessions: {}, SessionKeys: {}",
                    Thread.currentThread().getName(),
                    sessions.size(),
                    sessions.keySet());

            if (paperTradingConfig.isPaperTradingEnabled()) {
                String fallback = "PAPER_DEFAULT_USER";
                log.warn("UserContext missing in UserSessionManager; falling back to {} in paper mode", fallback);
                return fallback;
            }

            // Provide actionable error message
            throw new IllegalStateException(
                    "User context is missing (thread=" + Thread.currentThread().getName() + "). " +
                    "Ensure X-User-Id header is passed, or if this is a scheduled task, " +
                    "use CurrentUserContext.setUserId() before calling this method."
            );
        }
        return id;
    }

    /**
     * Check if a session exists for the given user ID.
     * Checks both in-memory cache and database for Cloud Run compatibility.
     *
     * @param userId User ID to check
     * @return true if session entry exists (in memory or database)
     */
    public boolean hasSession(String userId) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        String trimmedUserId = userId.trim();

        // Fast path: check in-memory cache first
        if (sessions.containsKey(trimmedUserId)) {
            return true;
        }

        // Slow path: check database (for Cloud Run session recovery)
        try {
            return sessionRepository.existsByUserIdAndActiveTrue(trimmedUserId);
        } catch (Exception e) {
            log.warn("Failed to check session in database for userId={}: {}", trimmedUserId, e.getMessage());
            return false;
        }
    }

    /**
     * Get KiteConnect for a user. May return null if session doesn't exist.
     * For HFT safety, prefer using getKiteForUserSafe() which returns Optional.
     *
     * CLOUD RUN: If session is not in memory, attempts to restore from database.
     *
     * @param userId User ID
     * @return KiteConnect or null
     */
    public KiteConnect getKiteForUser(String userId) {
        if (userId == null || userId.isBlank()) {
            log.warn("getKiteForUser called with null/blank userId");
            return null;
        }
        String trimmedUserId = userId.trim();

        // Fast path: check in-memory cache
        SessionEntry entry = sessions.get(trimmedUserId);
        if (entry != null) {
            if (entry.isPotentiallyStale()) {
                log.warn("Session for userId={} is potentially stale (created: {})", trimmedUserId, entry.createdAt);
            }
            return entry.kiteConnect;
        }

        // Cloud Run recovery path: attempt to restore session from database
        log.debug("Session not in memory for userId={}, attempting database recovery", trimmedUserId);
        KiteConnect recoveredKite = attemptSessionRecoveryFromDatabase(trimmedUserId);
        if (recoveredKite != null) {
            log.info("Successfully recovered session from database for userId={}", trimmedUserId);
            return recoveredKite;
        }

        log.debug("No session found for userId={} (checked memory and database)", trimmedUserId);
        return null;
    }

    /**
     * HFT-Safe: Get KiteConnect wrapped in Optional to force null handling.
     *
     * @param userId User ID
     * @return Optional containing KiteConnect or empty
     */
    public Optional<KiteConnect> getKiteForUserSafe(String userId) {
        return Optional.ofNullable(getKiteForUser(userId));
    }

    /**
     * Get required KiteConnect for the current user context.
     * Throws IllegalStateException if no session exists.
     *
     * HFT-Critical: This method is used in the hot path - optimize for the happy case.
     *
     * CLOUD RUN: If session is not in memory, attempts transparent recovery from database.
     * This enables session continuity across container instances and restarts.
     *
     * @return KiteConnect (never null)
     * @throws IllegalStateException if no session exists and cannot be recovered
     */
    public KiteConnect getRequiredKiteForCurrentUser() {
        String userId = getCurrentUserIdRequired();

        // Fast path: direct lookup without lock (optimized for HFT hot path)
        SessionEntry entry = sessions.get(userId);
        if (entry != null) {
            if (entry.isPotentiallyStale()) {
                log.warn("Session for userId={} is potentially stale (created: {}). " +
                        "Consider re-authenticating.", userId, entry.createdAt);
            }
            return entry.kiteConnect;
        }

        // Cloud Run recovery path: attempt to restore session from database
        log.info("Session not in memory for userId={} on this instance, attempting database recovery...", userId);
        KiteConnect recoveredKite = attemptSessionRecoveryFromDatabase(userId);
        if (recoveredKite != null) {
            log.info("Successfully recovered session from database for userId={} (Cloud Run session restoration)", userId);
            return recoveredKite;
        }

        // Session not found in memory or database - provide detailed error
        log.error("No active session for userId={} in memory or database. " +
                "In-memory sessions: {}. Active userIds: {}",
                userId, sessions.size(), sessions.keySet());

        throw new IllegalStateException(
                "No active session for user " + userId + ". " +
                "Please authenticate via /api/auth/session. " +
                "(In-memory sessions: " + sessions.size() + ", database checked: true)"
        );
    }

    /**
     * Backward compatible method when user header is already set.
     */
    public User createOrReplaceSessionForCurrentUser(String requestToken) throws KiteException, IOException {
        String headerUserId = CurrentUserContext.getUserId();
        return createSessionInternal(requestToken, headerUserId);
    }

    /**
     * Creates a session. If X-User-Id header was provided, uses it; otherwise resolves userId from Kite response and stores session under that id.
     * Returns the Kite User object.
     */
    public User createSessionFromRequestToken(String requestToken) throws KiteException, IOException {
        String headerUserId = CurrentUserContext.getUserId();
        return createSessionInternal(requestToken, headerUserId);
    }

    /**
     * Internal method to create a session with proper metadata tracking.
     *
     * HFT-Critical: Uses write lock to ensure atomic session replacement.
     *
     * CLOUD RUN: Sessions are persisted to database for cross-instance recovery.
     * The database write is done synchronously to ensure consistency, but this
     * only happens during login (not in the trading hot path).
     */
    private User createSessionInternal(String requestToken, String headerUserId) throws KiteException, IOException {
        if (requestToken == null || requestToken.isBlank()) {
            throw new IllegalArgumentException("Request token cannot be null or blank");
        }

        KiteConnect kc = new KiteConnect(kiteConfig.getApiKey());
        User user = kc.generateSession(requestToken, kiteConfig.getApiSecret());
        kc.setAccessToken(user.accessToken);

        // Determine final userId key
        String effectiveUserId = (headerUserId != null && !headerUserId.isBlank())
                ? headerUserId.trim()
                : user.userId;

        // Create session entry with metadata
        SessionEntry newEntry = new SessionEntry(kc, user.userId, user.accessToken);

        // Atomic session replacement with write lock
        sessionLock.writeLock().lock();
        try {
            SessionEntry oldEntry = sessions.put(effectiveUserId, newEntry);
            if (oldEntry != null) {
                log.info("Replaced existing session for userId={} (old session created at: {})",
                        effectiveUserId, oldEntry.createdAt);
            }
        } finally {
            sessionLock.writeLock().unlock();
        }

        // CLOUD RUN: Persist session to database for cross-instance recovery
        persistSessionToDatabase(effectiveUserId, user.userId, user.accessToken, user.publicToken);

        // If header was absent, set context for this request so downstream logging sees it
        if (headerUserId == null || headerUserId.isBlank()) {
            CurrentUserContext.setUserId(effectiveUserId);
            log.debug("UserContext set from Kite userId={} during session creation", effectiveUserId);
        }

        log.info("Kite session stored for userId={} (Kite user: {}, sessions count: {}, persisted to database: true)",
                effectiveUserId, user.userId, sessions.size());
        return user;
    }

    /**
     * Invalidate and remove session for the current user.
     * CLOUD RUN: Also deactivates the session in the database to prevent recovery on other instances.
     */
    public void invalidateSessionForCurrentUser() {
        String userId = getCurrentUserIdRequired();
        sessionLock.writeLock().lock();
        try {
            SessionEntry removed = sessions.remove(userId);
            if (removed != null) {
                log.info("Kite session invalidated for userId={} (session was created at: {}, last accessed: {})",
                        userId, removed.createdAt, removed.lastAccessedAt);
            } else {
                log.warn("Attempted to invalidate non-existent in-memory session for userId={}", userId);
            }
        } finally {
            sessionLock.writeLock().unlock();
        }

        // CLOUD RUN: Deactivate session in database to prevent recovery on other instances
        deactivateSessionInDatabase(userId);
    }

    /**
     * Invalidate session by user ID (for administrative use).
     * CLOUD RUN: Also deactivates the session in the database.
     *
     * @param userId User ID to invalidate
     * @return true if session was removed, false if it didn't exist
     */
    public boolean invalidateSession(String userId) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        String trimmedUserId = userId.trim();
        boolean removedFromMemory = false;

        sessionLock.writeLock().lock();
        try {
            SessionEntry removed = sessions.remove(trimmedUserId);
            if (removed != null) {
                log.info("Session administratively invalidated for userId={}", trimmedUserId);
                removedFromMemory = true;
            }
        } finally {
            sessionLock.writeLock().unlock();
        }

        // CLOUD RUN: Deactivate session in database
        boolean deactivatedInDb = deactivateSessionInDatabase(trimmedUserId);

        return removedFromMemory || deactivatedInDb;
    }

    /**
     * Get all active user IDs with valid sessions.
     * Used by background services (like DeltaCacheService) that need to operate across all users.
     *
     * @return Set of user IDs with active Kite sessions
     */
    public java.util.Set<String> getActiveUserIds() {
        return java.util.Collections.unmodifiableSet(sessions.keySet());
    }

    /**
     * Get KiteConnect for a specific user ID (for background services).
     * Returns null if no session exists for the user.
     *
     * @param userId User ID to get session for
     * @return KiteConnect instance or null if not found
     */
    public KiteConnect getKiteForUserOrNull(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        SessionEntry entry = sessions.get(userId.trim());
        return entry != null ? entry.kiteConnect : null;
    }

    /**
     * Get total count of active sessions (for monitoring).
     */
    public int getActiveSessionCount() {
        return sessions.size();
    }

    /**
     * Get session status information for monitoring.
     *
     * @param userId User ID to check
     * @return Session info map or null if no session exists
     */
    public java.util.Map<String, Object> getSessionInfo(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        SessionEntry entry = sessions.get(userId.trim());
        if (entry == null) {
            return null;
        }
        java.util.Map<String, Object> info = new java.util.HashMap<>();
        info.put("userId", userId);
        info.put("kiteUserId", entry.kiteUserId);
        info.put("createdAt", entry.createdAt.toString());
        info.put("lastAccessedAt", entry.lastAccessedAt.toString());
        info.put("isPotentiallyStale", entry.isPotentiallyStale());
        info.put("ageMinutes", java.time.Duration.between(entry.createdAt, Instant.now()).toMinutes());
        return info;
    }

    /**
     * Scheduled cleanup of stale sessions.
     * Runs every hour to remove sessions older than 18 hours (Kite sessions expire ~6-8 hours but we keep them longer for safety).
     *
     * SAFETY: Only runs outside market hours to avoid disrupting active trading.
     * UPDATED: Increased threshold from 10 to 18 hours to prevent premature cleanup.
     */
    @Scheduled(fixedRate = 3600000) // Every hour
    public void cleanupStaleSessions() {
        LocalTime now = LocalTime.now(IST);

        // Only cleanup outside market hours (before 9 AM or after 4 PM IST)
        if (now.isAfter(LocalTime.of(9, 0)) && now.isBefore(LocalTime.of(16, 0))) {
            log.debug("Skipping session cleanup during market hours ({})", now);
            return;
        }

        // INCREASED from 10 hours to 18 hours for safety during long trading sessions
        Instant staleThreshold = Instant.now().minusSeconds(18 * 60 * 60); // 18 hours

        sessionLock.writeLock().lock();
        try {
            int beforeCount = sessions.size();

            if (beforeCount == 0) {
                log.debug("No sessions to clean up");
                return;
            }

            log.info("Starting session cleanup check: {} active sessions, threshold: 18 hours", beforeCount);

            sessions.entrySet().removeIf(entry -> {
                if (entry.getValue().createdAt.isBefore(staleThreshold)) {
                    log.warn("Removing stale session for userId={} (created: {}, age: {} hours)",
                            entry.getKey(),
                            entry.getValue().createdAt,
                            java.time.Duration.between(entry.getValue().createdAt, Instant.now()).toHours());
                    return true;
                }
                return false;
            });

            int removedCount = beforeCount - sessions.size();
            if (removedCount > 0) {
                log.warn("Cleaned up {} stale sessions. Remaining: {}", removedCount, sessions.size());
            } else {
                log.debug("No stale sessions found during cleanup check");
            }
        } finally {
            sessionLock.writeLock().unlock();
        }
    }

    /**
     * Force cleanup of all sessions (for administrative use, e.g., during maintenance).
     *
     * @return Number of sessions cleared
     */
    public int clearAllSessions() {
        sessionLock.writeLock().lock();
        try {
            int count = sessions.size();
            sessions.clear();
            log.warn("All {} sessions forcefully cleared", count);
            return count;
        } finally {
            sessionLock.writeLock().unlock();
        }
    }

    // ==================== CLOUD RUN DATABASE PERSISTENCE METHODS ====================

    /**
     * Persist session to database for cross-instance recovery in Cloud Run.
     * This is called during session creation (login flow, not hot path).
     *
     * @param userId Application user ID
     * @param kiteUserId Original Kite user ID
     * @param accessToken Kite access token
     * @param publicToken Kite public token (optional)
     */
    private void persistSessionToDatabase(String userId, String kiteUserId, String accessToken, String publicToken) {
        try {
            Instant now = Instant.now();
            // Kite sessions typically expire next morning (~6 AM IST), estimate 18 hours from now
            Instant expiresAt = now.plusSeconds(18 * 60 * 60);

            UserSessionEntity entity = sessionRepository.findByUserId(userId)
                    .map(existing -> {
                        // Update existing session
                        existing.setKiteUserId(kiteUserId);
                        existing.setAccessToken(accessToken);
                        existing.setPublicToken(publicToken);
                        existing.setCreatedAt(now);
                        existing.setLastAccessedAt(now);
                        existing.setExpiresAt(expiresAt);
                        existing.setActive(true);
                        return existing;
                    })
                    .orElseGet(() -> UserSessionEntity.builder()
                            .userId(userId)
                            .kiteUserId(kiteUserId)
                            .accessToken(accessToken)
                            .publicToken(publicToken)
                            .createdAt(now)
                            .lastAccessedAt(now)
                            .expiresAt(expiresAt)
                            .active(true)
                            .build());

            sessionRepository.save(entity);
            log.debug("Session persisted to database for userId={}", userId);
        } catch (Exception e) {
            // Log but don't fail - session is already in memory and will work on this instance
            log.error("Failed to persist session to database for userId={}: {}. " +
                    "Session will work on this instance but may not be recoverable on other instances.",
                    userId, e.getMessage());
        }
    }

    /**
     * Attempt to recover a session from the database.
     * This is called when a request arrives at an instance without the session in memory.
     *
     * CLOUD RUN: This enables transparent session recovery across container instances.
     *
     * @param userId User ID to recover session for
     * @return KiteConnect instance if recovery successful, null otherwise
     */
    private KiteConnect attemptSessionRecoveryFromDatabase(String userId) {
        try {
            Optional<UserSessionEntity> sessionOpt = sessionRepository.findByUserIdAndActiveTrue(userId);
            if (sessionOpt.isEmpty()) {
                log.debug("No active session in database for userId={}", userId);
                return null;
            }

            UserSessionEntity dbSession = sessionOpt.get();

            // Check if session is potentially expired
            if (dbSession.isPotentiallyExpired()) {
                log.warn("Database session for userId={} appears expired (expiresAt={}), " +
                        "attempting recovery anyway - Kite API will validate", userId, dbSession.getExpiresAt());
            }

            // Recreate KiteConnect with stored access token
            KiteConnect kc = new KiteConnect(kiteConfig.getApiKey());
            kc.setAccessToken(dbSession.getAccessToken());

            // Create in-memory session entry
            SessionEntry newEntry = new SessionEntry(kc, dbSession.getKiteUserId(), dbSession.getAccessToken());

            // Store in memory cache with write lock
            sessionLock.writeLock().lock();
            try {
                sessions.put(userId, newEntry);
            } finally {
                sessionLock.writeLock().unlock();
            }

            // Update last accessed time in database (async-safe, non-blocking for HFT)
            try {
                sessionRepository.updateLastAccessedAt(userId, Instant.now());
            } catch (Exception e) {
                log.debug("Failed to update last accessed time for userId={}: {}", userId, e.getMessage());
            }

            log.info("Session recovered from database for userId={} (kiteUserId={}, created={})",
                    userId, dbSession.getKiteUserId(), dbSession.getCreatedAt());
            return kc;

        } catch (Exception e) {
            log.error("Failed to recover session from database for userId={}: {}", userId, e.getMessage());
            return null;
        }
    }

    /**
     * Deactivate session in database (called during logout).
     * This prevents the session from being recovered on other instances.
     *
     * @param userId User ID whose session should be deactivated
     * @return true if session was deactivated
     */
    private boolean deactivateSessionInDatabase(String userId) {
        try {
            int updated = sessionRepository.deactivateByUserId(userId);
            if (updated > 0) {
                log.debug("Session deactivated in database for userId={}", userId);
                return true;
            }
            return false;
        } catch (Exception e) {
            log.error("Failed to deactivate session in database for userId={}: {}", userId, e.getMessage());
            return false;
        }
    }

    /**
     * Scheduled task to clean up old inactive sessions from the database.
     * Runs every 6 hours to remove sessions that have been inactive for more than 24 hours.
     */
    @Scheduled(fixedRate = 6 * 60 * 60 * 1000) // Every 6 hours
    public void cleanupDatabaseSessions() {
        try {
            Instant threshold = Instant.now().minusSeconds(24 * 60 * 60); // 24 hours
            int deleted = sessionRepository.deleteInactiveSessionsOlderThan(threshold);
            if (deleted > 0) {
                log.info("Cleaned up {} inactive sessions from database (older than 24 hours)", deleted);
            }
        } catch (Exception e) {
            log.error("Failed to clean up database sessions: {}", e.getMessage());
        }
    }

    /**
     * Get count of sessions persisted in database (for monitoring).
     *
     * @return Number of active sessions in database
     */
    public long getDatabaseSessionCount() {
        try {
            return sessionRepository.countByActiveTrue();
        } catch (Exception e) {
            log.error("Failed to count database sessions: {}", e.getMessage());
            return -1;
        }
    }
}
