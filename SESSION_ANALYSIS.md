# Session Loss Analysis - Root Cause & Fixes

## Problem Statement
Application logs show: "No active session for userId=PAPER_DEFAULT_USER. Total active sessions: 0"
This should NOT happen during active strategy execution.

## Root Causes Identified

### 🔴 CRITICAL ISSUE #1: User Context NOT Propagated to Custom Executor Threads
**Location**: Multiple files using `CompletableFuture.supplyAsync()` with custom executors

**Problem**: When using a custom `ExecutorService` with `CompletableFuture.supplyAsync()`, the `InheritableThreadLocal` used by `CurrentUserContext` does NOT automatically propagate to executor threads.

**Affected Code**:
1. **TradingService.java** (line 166)
   ```java
   CompletableFuture.supplyAsync(() -> placeSingleBasketOrderItem(item), ORDER_EXECUTOR)
   ```
   - Uses custom `ORDER_EXECUTOR`
   - User context is LOST in executor threads
   - When `placeSingleBasketOrderItem` calls `getRequiredKiteForCurrentUser()`, context is missing

2. **ATMStraddleStrategy.java** (lines 257, 268, 293, 302)
   ```java
   CompletableFuture.supplyAsync(() -> getOrderHistory(...), EXIT_ORDER_EXECUTOR)
   ```
   - Uses custom `EXIT_ORDER_EXECUTOR`
   - Context is NOT inherited by custom executor threads

3. **SellATMStraddleStrategy.java** (lines 378, 642, 653, 678, 687, 965)
   - Same issue with `EXIT_ORDER_EXECUTOR`

4. **DeltaCacheService.java** (line 147)
   ```java
   CompletableFuture.supplyAsync(() -> {...}, DELTA_EXECUTOR)
   ```
   - Uses custom `DELTA_EXECUTOR`
   - Context properly handled with `callWithUserContext()` wrapper ✅

**Why InheritableThreadLocal Doesn't Work with Executors**:
- `InheritableThreadLocal` only propagates when `new Thread()` is created
- Executor thread pools reuse existing threads
- When a thread is reused, it retains its old `ThreadLocal` state (which may be empty)

### 🔴 CRITICAL ISSUE #2: Session Cleanup During Market Hours
**Location**: UserSessionManager.java (line 373)

**Problem**: The `cleanupStaleSessions()` scheduled method runs every hour and removes sessions older than 10 hours.

**Risk**: 
- Runs at market hours check: `if (now.isAfter(LocalTime.of(9, 0)) && now.isBefore(LocalTime.of(16, 0)))`
- If a session was created at 8:00 AM and market opens at 9:15 AM, by 6:00 PM (10 hours later) it's still valid but could be removed
- The 10-hour threshold is too aggressive for intraday strategies

### 🟡 MEDIUM ISSUE #3: No User Context in Exit Order Executor Threads
**Location**: SellATMStraddleStrategy.java (line 615)

**Problem**: `CompletableFuture.runAsync()` with `EXIT_ORDER_EXECUTOR` manually sets userId but doesn't use the helper methods:
```java
if (ownerUserId != null && !ownerUserId.isBlank()) {
    CurrentUserContext.setUserId(ownerUserId);
}
try {
    setupMonitoringInternal(...);
} finally {
    CurrentUserContext.clear();
}
```

This is correct but error-prone. Should use `runWithUserContext()` helper.

### 🟡 MEDIUM ISSUE #4: Scheduled Tasks May Run Without Sessions
**Location**: DeltaCacheService.java (line 194)

**Problem**: The `refreshDeltaCache()` scheduled task checks for active sessions and uses the first one:
```java
Set<String> activeUsers = userSessionManager.getActiveUserIds();
if (activeUsers.isEmpty()) {
    log.debug("Skipping delta cache refresh - no active user sessions available");
    return;
}
```

**Risk**: If this scheduled task runs at the exact moment sessions are being cleared/refreshed, it could fail or use a stale session.

### 🟢 LOW ISSUE #5: Thread Pool Configuration
**Location**: Multiple ExecutorService declarations

**Observation**: Thread pools are created as static final fields with daemon threads:
```java
private static final ExecutorService ORDER_EXECUTOR = Executors.newFixedThreadPool(4, r -> {
    Thread t = new Thread(r, "hft-order-placer");
    t.setDaemon(true);
    t.setPriority(Thread.MAX_PRIORITY);
    return t;
});
```

This is fine, but these pools should ideally be Spring-managed beans for better lifecycle control and graceful shutdown.

## Recommended Fixes

### Fix #1: Wrap All Executor Tasks with User Context
**Priority**: CRITICAL

For all `CompletableFuture.supplyAsync()` and `runAsync()` calls using custom executors, wrap the task:

```java
// BEFORE (BROKEN)
CompletableFuture.supplyAsync(() -> placeSingleBasketOrderItem(item), ORDER_EXECUTOR)

// AFTER (FIXED)
String userId = CurrentUserContext.getUserId();
CompletableFuture.supplyAsync(
    CurrentUserContext.wrapWithContext(() -> placeSingleBasketOrderItem(item)),
    ORDER_EXECUTOR
)
```

**Files to Fix**:
- TradingService.java
- ATMStraddleStrategy.java
- SellATMStraddleStrategy.java

### Fix #2: Increase Stale Session Threshold and Add Safety Checks
**Priority**: HIGH

Change the cleanup logic:
1. Increase threshold from 10 hours to 16 hours (allows for session created at 8 AM to survive until next day)
2. Add check to ensure no active strategies are running for the user before removing session
3. Add more defensive logging

### Fix #3: Use Helper Methods Consistently
**Priority**: MEDIUM

Replace manual context setting with `CurrentUserContext.runWithUserContext()` in all places.

### Fix #4: Add Session Validation Before Critical Operations
**Priority**: MEDIUM

Before any trading operation, validate session exists and is not stale:
```java
if (!userSessionManager.hasSession(userId)) {
    throw new IllegalStateException("Session lost for user " + userId);
}
```

### Fix #5: Consider Spring-Managed Executor Beans
**Priority**: LOW

Move executor creation to configuration classes for better lifecycle management.

## Testing Strategy

1. **Unit Tests**: Test context propagation in executor threads
2. **Integration Tests**: Simulate long-running strategy with session validation
3. **Load Tests**: Run multiple strategies concurrently and verify session stability
4. **Chaos Tests**: Simulate session cleanup during strategy execution

## Monitoring Additions

1. Add metrics for session lifecycle events
2. Log when context is missing (already done in UserSessionManager)
3. Add session age monitoring
4. Alert when session count drops to zero during market hours

