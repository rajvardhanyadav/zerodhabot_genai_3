package com.tradingbot.service.session;

import com.tradingbot.config.KiteConfig;
import com.tradingbot.config.PaperTradingConfig;
import com.tradingbot.util.CurrentUserContext;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.User;
import lombok.RequiredArgsConstructor;
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

@Component
@RequiredArgsConstructor
@Slf4j
public class UserSessionManager {

    private final KiteConfig kiteConfig;
    private final PaperTradingConfig paperTradingConfig;

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
         * Check if session is potentially stale (created > 8 hours ago).
         * Kite sessions typically expire at end of day/next morning.
         */
        boolean isPotentiallyStale() {
            return Instant.now().isAfter(createdAt.plusSeconds(8 * 60 * 60));
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
     * Thread-safe but does NOT guarantee the session is still valid.
     *
     * @param userId User ID to check
     * @return true if session entry exists
     */
    public boolean hasSession(String userId) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        return sessions.containsKey(userId.trim());
    }

    /**
     * Get KiteConnect for a user. May return null if session doesn't exist.
     * For HFT safety, prefer using getKiteForUserSafe() which returns Optional.
     *
     * @param userId User ID
     * @return KiteConnect or null
     */
    public KiteConnect getKiteForUser(String userId) {
        if (userId == null || userId.isBlank()) {
            log.warn("getKiteForUser called with null/blank userId");
            return null;
        }
        SessionEntry entry = sessions.get(userId.trim());
        if (entry == null) {
            log.debug("No session found for userId={}", userId);
            return null;
        }
        if (entry.isPotentiallyStale()) {
            log.warn("Session for userId={} is potentially stale (created: {})", userId, entry.createdAt);
        }
        return entry.kiteConnect;
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
     * @return KiteConnect (never null)
     * @throws IllegalStateException if no session exists
     */
    public KiteConnect getRequiredKiteForCurrentUser() {
        String userId = getCurrentUserIdRequired();

        // Fast path: direct lookup without lock
        SessionEntry entry = sessions.get(userId);
        if (entry != null) {
            if (entry.isPotentiallyStale()) {
                log.warn("Session for userId={} is potentially stale (created: {}). " +
                        "Consider re-authenticating.", userId, entry.createdAt);
            }
            return entry.kiteConnect;
        }

        // Session not found - provide detailed error
        log.error("No active session for userId={}. " +
                "Total active sessions: {}. Active userIds: {}",
                userId, sessions.size(), sessions.keySet());

        throw new IllegalStateException(
                "No active session for user " + userId + ". " +
                "Please authenticate via /api/auth/session. " +
                "(Active sessions: " + sessions.size() + ")"
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

        // If header was absent, set context for this request so downstream logging sees it
        if (headerUserId == null || headerUserId.isBlank()) {
            CurrentUserContext.setUserId(effectiveUserId);
            log.debug("UserContext set from Kite userId={} during session creation", effectiveUserId);
        }

        log.info("Kite session stored for userId={} (Kite user: {}, sessions count: {})",
                effectiveUserId, user.userId, sessions.size());
        return user;
    }

    /**
     * Invalidate and remove session for the current user.
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
                log.warn("Attempted to invalidate non-existent session for userId={}", userId);
            }
        } finally {
            sessionLock.writeLock().unlock();
        }
    }

    /**
     * Invalidate session by user ID (for administrative use).
     *
     * @param userId User ID to invalidate
     * @return true if session was removed, false if it didn't exist
     */
    public boolean invalidateSession(String userId) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        sessionLock.writeLock().lock();
        try {
            SessionEntry removed = sessions.remove(userId.trim());
            if (removed != null) {
                log.info("Session administratively invalidated for userId={}", userId);
                return true;
            }
            return false;
        } finally {
            sessionLock.writeLock().unlock();
        }
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
     * Runs every hour to remove sessions older than 10 hours (Kite sessions expire ~6-8 hours).
     */
    @Scheduled(fixedRate = 3600000) // Every hour
    public void cleanupStaleSessions() {
        LocalTime now = LocalTime.now(IST);

        // Only cleanup outside market hours (before 9 AM or after 4 PM IST)
        if (now.isAfter(LocalTime.of(9, 0)) && now.isBefore(LocalTime.of(16, 0))) {
            log.debug("Skipping session cleanup during market hours");
            return;
        }

        Instant staleThreshold = Instant.now().minusSeconds(10 * 60 * 60); // 10 hours

        sessionLock.writeLock().lock();
        try {
            int beforeCount = sessions.size();
            sessions.entrySet().removeIf(entry -> {
                if (entry.getValue().createdAt.isBefore(staleThreshold)) {
                    log.info("Removing stale session for userId={} (created: {})",
                            entry.getKey(), entry.getValue().createdAt);
                    return true;
                }
                return false;
            });
            int removedCount = beforeCount - sessions.size();
            if (removedCount > 0) {
                log.info("Cleaned up {} stale sessions", removedCount);
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
}
