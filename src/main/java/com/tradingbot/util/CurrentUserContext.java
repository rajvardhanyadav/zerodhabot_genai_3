package com.tradingbot.util;

import lombok.extern.slf4j.Slf4j;

/**
 * Thread-safe user context management for HFT systems.
 *
 * CLOUD RUN COMPATIBILITY:
 * - Uses InheritableThreadLocal so child threads (async tasks) inherit the user context
 * - Provides explicit user ID passing methods for scheduled tasks that don't have request context
 * - Includes detailed logging for debugging context propagation issues
 *
 * USAGE PATTERNS:
 * 1. HTTP Request: Filter sets context, automatically inherited by async operations within the request
 * 2. Scheduled Tasks: Must explicitly set user context using setUserId() before operations
 * 3. Background Jobs: Use runWithUserContext() for guaranteed context management
 */
@Slf4j
public final class CurrentUserContext {

    /**
     * InheritableThreadLocal ensures child threads created during request processing
     * inherit the user context. This is critical for:
     * - CompletableFuture.supplyAsync()
     * - @Async methods called within a request
     * - Parallel streams
     */
    private static final InheritableThreadLocal<String> USER_ID = new InheritableThreadLocal<>();

    /**
     * Tracks the thread that set the context - useful for debugging in Cloud Run
     * where thread reuse patterns differ from local development.
     */
    private static final ThreadLocal<String> CONTEXT_SOURCE = new ThreadLocal<>();

    /**
     * Tracks the timestamp when context was set - helps identify stale contexts.
     */
    private static final ThreadLocal<Long> CONTEXT_TIMESTAMP = new ThreadLocal<>();

    private CurrentUserContext() {}

    /**
     * Set user ID in the current thread context.
     * The context will be automatically inherited by child threads.
     *
     * CLOUD RUN NOTE: In Cloud Run, executor threads are reused and don't inherit
     * InheritableThreadLocal from request threads. Use TaskDecorator or explicit
     * context passing for async operations.
     *
     * @param userId User ID to set (will be trimmed if not null)
     */
    public static void setUserId(String userId) {
        if (userId != null && !userId.isBlank()) {
            String trimmed = userId.trim();
            USER_ID.set(trimmed);
            CONTEXT_SOURCE.set(Thread.currentThread().getName());
            CONTEXT_TIMESTAMP.set(System.currentTimeMillis());
            log.debug("User context SET: userId={}, thread={}", trimmed, Thread.currentThread().getName());
        } else {
            log.warn("Attempted to set null/blank userId in thread={}", Thread.currentThread().getName());
        }
    }

    /**
     * Get user ID from current thread context.
     *
     * @return User ID or null if not set
     */
    public static String getUserId() {
        String id = USER_ID.get();
        if (id == null && log.isTraceEnabled()) {
            log.trace("getUserId() returned null in thread={}", Thread.currentThread().getName());
        }
        return id;
    }

    /**
     * Get user ID with detailed context information for debugging.
     * Use this when investigating context propagation issues.
     *
     * @return User ID or null, with side effect of detailed logging
     */
    public static String getUserIdWithDebug() {
        String id = USER_ID.get();
        String source = CONTEXT_SOURCE.get();
        Long timestamp = CONTEXT_TIMESTAMP.get();
        long ageMs = timestamp != null ? System.currentTimeMillis() - timestamp : -1;
        log.debug("getUserIdWithDebug: userId={}, currentThread={}, contextSource={}, ageMs={}",
                id, Thread.currentThread().getName(), source, ageMs);
        return id;
    }

    /**
     * Get diagnostic information about the current context state.
     * Useful for troubleshooting Cloud Run context propagation issues.
     *
     * @return Diagnostic string with userId, thread, source, and age information
     */
    public static String getContextDiagnostics() {
        String id = USER_ID.get();
        String source = CONTEXT_SOURCE.get();
        Long timestamp = CONTEXT_TIMESTAMP.get();
        long ageMs = timestamp != null ? System.currentTimeMillis() - timestamp : -1;
        return String.format("userId=%s, thread=%s, source=%s, ageMs=%d",
                id != null ? id : "null",
                Thread.currentThread().getName(),
                source != null ? source : "null",
                ageMs);
    }

    /**
     * Get required user ID, throwing exception if not present.
     *
     * @return User ID (never null or blank)
     * @throws IllegalStateException if user context is missing
     */
    public static String getRequiredUserId() {
        String id = USER_ID.get();
        if (id == null || id.isBlank()) {
            String threadName = Thread.currentThread().getName();
            String source = CONTEXT_SOURCE.get();
            log.error("User context missing! thread={}, contextSource={}, stackTrace follows",
                    threadName, source);
            // Log stack trace to help identify the call path
            if (log.isDebugEnabled()) {
                log.debug("Stack trace for missing user context:", new Exception("Context trace"));
            }
            throw new IllegalStateException(
                    "User context is missing. Provide X-User-Id header. " +
                    "(thread=" + threadName + ", contextSource=" + source + ")"
            );
        }
        return id;
    }

    /**
     * Clear user context from current thread.
     * IMPORTANT: Always call this in a finally block after request processing.
     *
     * CLOUD RUN NOTE: Clearing context is critical in Cloud Run where threads are
     * reused across requests. Failure to clear can cause context leakage.
     */
    public static void clear() {
        String previousId = USER_ID.get();
        USER_ID.remove();
        CONTEXT_SOURCE.remove();
        CONTEXT_TIMESTAMP.remove();
        if (previousId != null) {
            log.debug("User context CLEARED: previousUserId={}, thread={}",
                    previousId, Thread.currentThread().getName());
        }
    }

    /**
     * Check if user context is currently set.
     *
     * @return true if user ID is set and not blank
     */
    public static boolean isContextSet() {
        String id = USER_ID.get();
        return id != null && !id.isBlank();
    }

    /**
     * Execute a runnable with a specific user context.
     * Context is automatically cleared after execution.
     *
     * Use this for scheduled tasks or background jobs that need user context:
     * <pre>
     * CurrentUserContext.runWithUserContext(userId, () -> {
     *     // Your code here has access to user context
     *     tradingService.doSomething();
     * });
     * </pre>
     *
     * @param userId User ID to set for the execution
     * @param task Task to execute with user context
     */
    public static void runWithUserContext(String userId, Runnable task) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId cannot be null or blank for runWithUserContext");
        }
        String previousUserId = USER_ID.get();
        try {
            setUserId(userId);
            task.run();
        } finally {
            // Restore previous context instead of just clearing
            if (previousUserId != null) {
                setUserId(previousUserId);
            } else {
                clear();
            }
        }
    }

    /**
     * Execute a supplier with a specific user context and return the result.
     * Context is automatically cleared after execution.
     *
     * @param userId User ID to set for the execution
     * @param supplier Supplier to execute with user context
     * @return Result from the supplier
     */
    public static <T> T callWithUserContext(String userId, java.util.function.Supplier<T> supplier) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId cannot be null or blank for callWithUserContext");
        }
        String previousUserId = USER_ID.get();
        try {
            setUserId(userId);
            return supplier.get();
        } finally {
            // Restore previous context instead of just clearing
            if (previousUserId != null) {
                setUserId(previousUserId);
            } else {
                clear();
            }
        }
    }

    /**
     * Create a Runnable wrapper that captures and propagates the current user context.
     * Use this when submitting tasks to executors that don't inherit thread locals.
     *
     * <pre>
     * executor.submit(CurrentUserContext.wrapWithContext(() -> {
     *     // This code will have the user context from when wrapWithContext was called
     *     tradingService.doSomething();
     * }));
     * </pre>
     *
     * @param task Task to wrap with context propagation
     * @return Wrapped task that will set user context before execution
     */
    public static Runnable wrapWithContext(Runnable task) {
        String capturedUserId = USER_ID.get();
        if (capturedUserId == null) {
            log.warn("wrapWithContext called with no user context set - task will run without context");
            return task;
        }
        return () -> {
            String previousUserId = USER_ID.get();
            try {
                setUserId(capturedUserId);
                task.run();
            } finally {
                if (previousUserId != null) {
                    setUserId(previousUserId);
                } else {
                    clear();
                }
            }
        };
    }

    /**
     * Create a Callable wrapper that captures and propagates the current user context.
     *
     * @param task Callable to wrap with context propagation
     * @return Wrapped callable that will set user context before execution
     */
    public static <T> java.util.concurrent.Callable<T> wrapWithContext(java.util.concurrent.Callable<T> task) {
        String capturedUserId = USER_ID.get();
        if (capturedUserId == null) {
            log.warn("wrapWithContext(Callable) called with no user context set - task will run without context");
            return task;
        }
        return () -> {
            String previousUserId = USER_ID.get();
            try {
                setUserId(capturedUserId);
                return task.call();
            } finally {
                if (previousUserId != null) {
                    setUserId(previousUserId);
                } else {
                    clear();
                }
            }
        };
    }

    /**
     * Create a Supplier wrapper that captures and propagates the current user context.
     * Use this for CompletableFuture.supplyAsync() with custom executors.
     *
     * <pre>
     * CompletableFuture.supplyAsync(
     *     CurrentUserContext.wrapSupplier(() -> someOperation()),
     *     customExecutor
     * );
     * </pre>
     *
     * @param supplier Supplier to wrap with context propagation
     * @return Wrapped supplier that will set user context before execution
     */
    public static <T> java.util.function.Supplier<T> wrapSupplier(java.util.function.Supplier<T> supplier) {
        String capturedUserId = USER_ID.get();
        if (capturedUserId == null) {
            log.warn("wrapSupplier called with no user context set - task will run without context");
            return supplier;
        }
        return () -> {
            String previousUserId = USER_ID.get();
            try {
                setUserId(capturedUserId);
                return supplier.get();
            } finally {
                if (previousUserId != null) {
                    setUserId(previousUserId);
                } else {
                    clear();
                }
            }
        };
    }
}
