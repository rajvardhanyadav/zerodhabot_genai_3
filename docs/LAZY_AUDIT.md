# @Lazy Circular Dependency Audit

> **Generated:** 2026-03-20  
> **Last Updated:** 2026-03-20 (Phase 1 completed: non-cycle @Lazy eliminated)  
> **Purpose:** Map all `@Lazy` injection sites, identify the circular dependency they break, and classify for event-driven decoupling.

---

## Summary

| # | File | Injected Bean | Cycle Type | Real Cycle? | Status |
|---|---|---|---|---|---|
| 1 | `StrategyService` | `StrategyRestartScheduler` | Bidirectional | ✅ YES | **@Lazy retained** — documented, pending event decoupling |
| 2 | `StrategyService` | `TradePersistenceService` | Forward-only | ❌ NO | ✅ **REMOVED** — constructor injection |
| 3 | `StrategyService` | `Executor (persistenceExecutor)` | No cycle | ❌ NO | ✅ **REMOVED** — constructor injection |
| 4 | `StrategyService` | `DailyPnlGateService` | Forward-only | ❌ NO | ✅ **REMOVED** — constructor injection |
| 5 | `SellATMStraddleStrategy` | `StrategyService` | Factory cycle | ✅ YES | **@Lazy retained** — documented, pending ObjectProvider/event |
| 6 | `ShortStrangleStrategy` | `StrategyService` | Factory cycle | ✅ YES | **@Lazy retained** — documented, pending ObjectProvider/event |
| 7 | `StraddleExitHandler` | `StrategyService` | Factory cycle | ✅ YES | **@Lazy retained** — documented, pending event decoupling |
| 8 | `LegReplacementHandler` | `StrategyService` | Factory cycle | ✅ YES | **@Lazy retained** — documented, pending event decoupling |
| 9 | `StrategyRestartScheduler` | `StrategyService` | Bidirectional | ✅ YES | **@Lazy retained** — documented, pending event decoupling |
| 10 | `UnifiedTradingService` | `TradePersistenceService` | Forward-only | ❌ NO | ✅ **REMOVED** — constructor injection |
| 11 | `WebSocketService` | `TradePersistenceService` | Forward-only | ❌ NO | ✅ **REMOVED** — constructor injection |
| 12 | `PaperTradingService` | `TradePersistenceService` | Forward-only | ❌ NO | ✅ **REMOVED** — constructor injection |
| 13 | `DeltaCacheService` | `TradePersistenceService` | Forward-only | ❌ NO | ✅ **REMOVED** — constructor injection |
| 14–18 | `DataCleanupService` | 5 Repositories | Late-added repos | ❌ NO | ✅ **REMOVED** — constructor injection |
| 19–20 | `SystemHealthMonitorService` | `WebSocketService`, `StrategyService` | Forward-only | ❌ NO | ✅ **REMOVED** — constructor injection |
| 21–25 | `TradePersistenceService` | 5 Repositories | Late-added repos | ❌ NO | ✅ **REMOVED** — constructor injection |

---

## Remaining @Lazy Usages (6 — All Real Cycles)

### CYCLE A — StrategyService ↔ StrategyRestartScheduler (Bidirectional)

```
StrategyService
  └─ @Lazy field: StrategyRestartScheduler        [StrategyService.java:73]
       └─ constructor @Lazy: StrategyService       [StrategyRestartScheduler.java:93]
```

**Why it exists:** `StrategyService.stopStrategy()` calls `strategyRestartScheduler.cancelScheduledRestarts()`.
`StrategyRestartScheduler.onMarketStateEvent()` calls `strategyService.executeStrategy()`.

**Decoupling plan:** Replace direct `StrategyService → StrategyRestartScheduler` call with
`StrategyStoppedEvent`. The scheduler listens for this event and cancels restarts.
Replace direct `StrategyRestartScheduler → StrategyService` call with
`StrategyRestartRequestEvent`. StrategyService listens and executes.

---

### CYCLE B — StrategyService ↔ {SellATMStraddleStrategy, ShortStrangleStrategy} (Factory Cycle)

```
StrategyService
  └─ constructor: StrategyFactory
       └─ injects: SellATMStraddleStrategy (via @Component)
            └─ constructor @Lazy: StrategyService   [SellATMStraddleStrategy.java:126]

StrategyService
  └─ constructor: StrategyFactory
       └─ injects: ShortStrangleStrategy (via @Component)
            └─ constructor @Lazy: StrategyService   [ShortStrangleStrategy.java:119]
```

**Why it exists:** Strategies call `strategyService.completeExecution()` and
`strategyService.updateLegLifecycleState()` after exits.

**Decoupling options:**
1. **`ObjectProvider<StrategyService>`** — breaks the eager init cycle without events.
2. **`StrategyCompletionEvent`** — strategy publishes event, StrategyService listens.
3. **Callback interface** — StrategyService passes a `StrategyCompletionCallback` to `execute()` (already partially in place).

---

### CYCLE C — StraddleExitHandler / LegReplacementHandler → StrategyService

```
StraddleExitHandler
  └─ constructor @Lazy: StrategyService             [StraddleExitHandler.java:42]
       └─ StrategyFactory → Strategies → StraddleExitHandler

LegReplacementHandler
  └─ constructor @Lazy: StrategyService             [LegReplacementHandler.java:62]
       └─ StrategyFactory → Strategies → LegReplacementHandler
```

**Why it exists:** Exit/replacement handlers call `strategyService.completeExecution()` after processing.

**Decoupling plan:** Same as Cycle B — publish `StrategyCompletionEvent` or use the
existing `StrategyCompletionCallback` pattern.

---

## Phase 1 Completed Changes (Non-Cycle @Lazy Removed)

All 16 non-cycle `@Lazy` usages have been converted to constructor injection:

| Service | Previously @Lazy Bean | Change |
|---|---|---|
| `StrategyService` | `TradePersistenceService`, `Executor`, `DailyPnlGateService` | Made `final` → Lombok `@RequiredArgsConstructor` |
| `UnifiedTradingService` | `TradePersistenceService` | Made `final` → Lombok `@RequiredArgsConstructor` |
| `WebSocketService` | `TradePersistenceService` | Added to explicit constructor |
| `PaperTradingService` | `TradePersistenceService` | Made `final` → Lombok `@RequiredArgsConstructor` |
| `DeltaCacheService` | `TradePersistenceService` | Added to explicit constructor |
| `SystemHealthMonitorService` | `WebSocketService`, `StrategyService` | Added to explicit constructor |
| `TradePersistenceService` | 5 Repositories | Made `final` → Lombok `@RequiredArgsConstructor` |
| `DataCleanupService` | 5 Repositories | Made `final` → Lombok `@RequiredArgsConstructor` |

## Phase 2 TODO (Event-Driven Decoupling for Real Cycles)

1. **Cycle A:** Publish `StrategyStoppedEvent` / `StrategyRestartRequestEvent`.
2. **Cycle B+C:** Use `ObjectProvider<StrategyService>` or existing `StrategyCompletionCallback`.
