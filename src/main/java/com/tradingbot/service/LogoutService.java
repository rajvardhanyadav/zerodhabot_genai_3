package com.tradingbot.service;

import com.tradingbot.paper.PaperTradingService;
import com.tradingbot.service.session.UserSessionManager;
import com.tradingbot.service.strategy.StrategyRestartScheduler;
import com.tradingbot.service.strategy.monitoring.WebSocketService;
import com.tradingbot.util.CurrentUserContext;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Service responsible for comprehensive user logout and cleanup.
 *
 * <p>This service orchestrates the cleanup of all user-related data on logout:
 * <ul>
 *   <li>Kite Connect session (access token)</li>
 *   <li>Active strategy executions</li>
 *   <li>WebSocket connections and subscriptions</li>
 *   <li>Paper trading state (orders, positions, accounts)</li>
 *   <li>Scheduled auto-restart tasks</li>
 *   <li>Thread-local user context</li>
 * </ul>
 *
 * <p><b>Thread Safety:</b> This service uses a per-user lock to prevent
 * concurrent logout operations for the same user, ensuring idempotent behavior.
 *
 * <p><b>HFT Safety:</b> Cleanup operations are designed to be non-blocking
 * for other users and avoid impacting active trading paths.
 *
 * @author Trading Bot Team
 * @since 4.2
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LogoutService {

    private final UserSessionManager sessionManager;
    private final StrategyService strategyService;
    private final WebSocketService webSocketService;
    private final PaperTradingService paperTradingService;
    private final StrategyRestartScheduler strategyRestartScheduler;

    // Per-user logout locks to ensure idempotent concurrent logout calls
    private final java.util.concurrent.ConcurrentHashMap<String, ReentrantLock> userLogoutLocks =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Result of logout operation containing cleanup statistics.
     */
    public record LogoutResult(
            boolean success,
            String userId,
            String message,
            int strategiesStopped,
            int scheduledRestartsCancelled,
            boolean webSocketDisconnected,
            boolean paperTradingReset,
            boolean sessionInvalidated,
            long durationMs
    ) {
        /**
         * Create a successful logout result.
         */
        public static LogoutResult success(String userId, int strategiesStopped,
                                           int restartsCancelled, boolean wsDisconnected,
                                           boolean paperReset, boolean sessionInvalidated,
                                           long durationMs) {
            return new LogoutResult(true, userId, "Logout successful", strategiesStopped,
                    restartsCancelled, wsDisconnected, paperReset, sessionInvalidated, durationMs);
        }

        /**
         * Create a partial success result when some cleanup failed.
         */
        public static LogoutResult partial(String userId, String message, int strategiesStopped,
                                           int restartsCancelled, boolean wsDisconnected,
                                           boolean paperReset, boolean sessionInvalidated,
                                           long durationMs) {
            return new LogoutResult(true, userId, message, strategiesStopped,
                    restartsCancelled, wsDisconnected, paperReset, sessionInvalidated, durationMs);
        }

        /**
         * Create a failed logout result.
         */
        public static LogoutResult failure(String userId, String message) {
            return new LogoutResult(false, userId, message, 0, 0, false, false, false, 0);
        }
    }

    /**
     * Perform complete logout for the current user.
     *
     * <p>This method performs the following cleanup steps in order:
     * <ol>
     *   <li>Stop all active strategies for the user</li>
     *   <li>Cancel any scheduled strategy auto-restarts</li>
     *   <li>Disconnect user's WebSocket and clear subscriptions</li>
     *   <li>Reset paper trading state (if applicable)</li>
     *   <li>Invalidate the Kite session</li>
     *   <li>Clear thread-local user context</li>
     * </ol>
     *
     * <p><b>Idempotency:</b> Multiple concurrent calls for the same user are safe.
     * Only the first call performs cleanup; subsequent calls return immediately.
     *
     * <p><b>Fail-safe:</b> Each cleanup step is wrapped in try-catch to ensure
     * partial failures don't prevent other cleanup steps from executing.
     *
     * @return LogoutResult containing statistics about the cleanup performed
     */
    public LogoutResult logout() {
        String userId = getCurrentUserId();
        if (userId == null || userId.isBlank()) {
            log.warn("Logout attempted without valid user context");
            return LogoutResult.failure(null, "No active session to logout");
        }

        return logoutUser(userId);
    }

    /**
     * Perform complete logout for a specific user (admin/system use).
     *
     * <p>This method can be used by administrators to force-logout a user
     * or by system processes that need to clean up user state.
     *
     * @param userId The user ID to logout
     * @return LogoutResult containing statistics about the cleanup performed
     */
    public LogoutResult logoutUser(String userId) {
        if (userId == null || userId.isBlank()) {
            return LogoutResult.failure(null, "Invalid user ID");
        }

        // Get or create per-user lock
        ReentrantLock userLock = userLogoutLocks.computeIfAbsent(userId, k -> new ReentrantLock());

        // Try to acquire lock - if already held, logout is in progress
        if (!userLock.tryLock()) {
            log.info("Logout already in progress for user: {}", userId);
            return LogoutResult.partial(userId, "Logout already in progress", 0, 0, false, false, false, 0);
        }

        long startTime = System.currentTimeMillis();
        log.info("=== LOGOUT START === userId: {}", userId);

        // Track cleanup results
        int strategiesStopped = 0;
        int restartsCancelled = 0;
        boolean wsDisconnected = false;
        boolean paperReset = false;
        boolean sessionInvalidated = false;
        StringBuilder warnings = new StringBuilder();

        try {
            // Step 1: Stop all active strategies for the user
            strategiesStopped = stopUserStrategies(userId, warnings);

            // Step 2: Cancel scheduled auto-restarts
            restartsCancelled = cancelScheduledRestarts(userId, warnings);

            // Step 3: Disconnect WebSocket and clear subscriptions
            wsDisconnected = disconnectUserWebSocket(userId, warnings);

            // Step 4: Reset paper trading state
            paperReset = resetPaperTradingState(userId, warnings);

            // Step 5: Invalidate Kite session
            sessionInvalidated = invalidateSession(userId, warnings);

            // Step 6: Clear user context (ThreadLocal cleanup)
            clearUserContext(userId);

            long duration = System.currentTimeMillis() - startTime;

            String message = warnings.length() > 0
                    ? "Logout completed with warnings: " + warnings.toString()
                    : "Logout successful";

            log.info("=== LOGOUT COMPLETE === userId: {}, duration: {}ms, strategies: {}, restarts: {}, ws: {}, paper: {}, session: {}",
                    userId, duration, strategiesStopped, restartsCancelled, wsDisconnected, paperReset, sessionInvalidated);

            if (warnings.length() > 0) {
                return LogoutResult.partial(userId, message, strategiesStopped, restartsCancelled,
                        wsDisconnected, paperReset, sessionInvalidated, duration);
            }

            return LogoutResult.success(userId, strategiesStopped, restartsCancelled,
                    wsDisconnected, paperReset, sessionInvalidated, duration);

        } catch (Exception e) {
            log.error("Unexpected error during logout for user {}: {}", userId, e.getMessage(), e);
            return LogoutResult.failure(userId, "Logout failed: " + e.getMessage());
        } finally {
            userLock.unlock();
            // Clean up the lock entry after use (avoid memory leak)
            userLogoutLocks.remove(userId, userLock);
        }
    }

    /**
     * Stop all active strategies for the user and clear the in-memory registry.
     *
     * <p>This method performs two cleanup actions:
     * <ol>
     *   <li>Stop all ACTIVE strategies (closes open positions)</li>
     *   <li>Clear ALL strategy executions from in-memory registry (prevents stale data)</li>
     * </ol>
     *
     * @param userId The user ID
     * @param warnings StringBuilder to accumulate warnings
     * @return Number of strategies stopped
     */
    private int stopUserStrategies(String userId, StringBuilder warnings) {
        log.debug("Stopping active strategies for user: {}", userId);
        int stopped = 0;

        try {
            // Set user context for strategy operations that require it
            stopped = CurrentUserContext.callWithUserContext(userId, () -> {
                try {
                    var result = strategyService.stopAllActiveStrategies();
                    int count = (Integer) result.getOrDefault("totalStrategies", 0);
                    log.info("Stopped {} active strategies for user: {}", count, userId);
                    return count;
                } catch (KiteException e) {
                    log.warn("Kite API error stopping strategies for user {}: {}", userId, e.getMessage());
                    warnings.append("Strategy stop partial failure (Kite API error); ");
                    return 0;
                } catch (Exception e) {
                    log.warn("Error stopping strategies for user {}: {}", userId, e.getMessage());
                    warnings.append("Strategy stop partial failure; ");
                    return 0;
                }
            });
        } catch (Exception e) {
            log.warn("Could not stop strategies for user {}: {}", userId, e.getMessage());
            warnings.append("Strategy cleanup skipped; ");
        }

        // Clear ALL strategy executions from in-memory registry
        // This ensures no stale data remains after logout
        try {
            int cleared = strategyService.clearUserStrategies(userId);
            log.info("Cleared {} strategy executions from registry for user: {}", cleared, userId);
        } catch (Exception e) {
            log.warn("Error clearing strategy registry for user {}: {}", userId, e.getMessage());
            warnings.append("Strategy registry cleanup partial failure; ");
        }

        return stopped;
    }

    /**
     * Cancel any scheduled strategy auto-restarts for the user.
     *
     * @param userId The user ID
     * @param warnings StringBuilder to accumulate warnings
     * @return Number of restarts cancelled
     */
    private int cancelScheduledRestarts(String userId, StringBuilder warnings) {
        log.debug("Cancelling scheduled restarts for user: {}", userId);
        try {
            int cancelled = strategyRestartScheduler.cancelScheduledRestartsForUser(userId);
            log.info("Cancelled {} scheduled restarts for user: {}", cancelled, userId);
            return cancelled;
        } catch (Exception e) {
            log.warn("Error cancelling scheduled restarts for user {}: {}", userId, e.getMessage());
            warnings.append("Restart cancellation partial failure; ");
            return 0;
        }
    }

    /**
     * Disconnect user's WebSocket and clear all subscriptions.
     *
     * @param userId The user ID
     * @param warnings StringBuilder to accumulate warnings
     * @return true if disconnected successfully
     */
    private boolean disconnectUserWebSocket(String userId, StringBuilder warnings) {
        log.debug("Disconnecting WebSocket for user: {}", userId);
        try {
            // Use user context to access the correct WebSocket context
            return CurrentUserContext.callWithUserContext(userId, () -> {
                try {
                    webSocketService.disconnectForLogout();
                    log.info("WebSocket disconnected for user: {}", userId);
                    return true;
                } catch (Exception e) {
                    log.warn("Error disconnecting WebSocket for user {}: {}", userId, e.getMessage());
                    warnings.append("WebSocket disconnect partial failure; ");
                    return false;
                }
            });
        } catch (Exception e) {
            log.warn("Could not disconnect WebSocket for user {}: {}", userId, e.getMessage());
            warnings.append("WebSocket cleanup skipped; ");
            return false;
        }
    }

    /**
     * Reset paper trading state for the user.
     *
     * @param userId The user ID
     * @param warnings StringBuilder to accumulate warnings
     * @return true if reset successfully
     */
    private boolean resetPaperTradingState(String userId, StringBuilder warnings) {
        log.debug("Resetting paper trading state for user: {}", userId);
        try {
            paperTradingService.resetAccount(userId);
            log.info("Paper trading state reset for user: {}", userId);
            return true;
        } catch (Exception e) {
            log.warn("Error resetting paper trading for user {}: {}", userId, e.getMessage());
            warnings.append("Paper trading reset partial failure; ");
            return false;
        }
    }

    /**
     * Invalidate the Kite session for the user.
     *
     * @param userId The user ID
     * @param warnings StringBuilder to accumulate warnings
     * @return true if invalidated successfully
     */
    private boolean invalidateSession(String userId, StringBuilder warnings) {
        log.debug("Invalidating session for user: {}", userId);
        try {
            boolean removed = sessionManager.invalidateSession(userId);
            if (removed) {
                log.info("Session invalidated for user: {}", userId);
            } else {
                log.debug("No active session found to invalidate for user: {}", userId);
            }
            return true;
        } catch (Exception e) {
            log.warn("Error invalidating session for user {}: {}", userId, e.getMessage());
            warnings.append("Session invalidation failed; ");
            return false;
        }
    }

    /**
     * Clear thread-local user context.
     *
     * @param userId The user ID (for logging)
     */
    private void clearUserContext(String userId) {
        log.debug("Clearing user context for user: {}", userId);
        try {
            CurrentUserContext.clear();
            log.debug("User context cleared for user: {}", userId);
        } catch (Exception e) {
            log.warn("Error clearing user context for user {}: {}", userId, e.getMessage());
            // This is non-critical, don't add to warnings
        }
    }

    /**
     * Get current user ID from context, returning null if not set.
     *
     * @return Current user ID or null
     */
    private String getCurrentUserId() {
        try {
            return CurrentUserContext.getUserId();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Check if a user has an active session.
     *
     * @param userId The user ID to check
     * @return true if user has an active session
     */
    public boolean hasActiveSession(String userId) {
        return userId != null && !userId.isBlank() && sessionManager.hasSession(userId);
    }
}

