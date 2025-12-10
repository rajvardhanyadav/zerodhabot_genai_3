package com.tradingbot.service.persistence;

import com.tradingbot.entity.*;
import com.tradingbot.model.StrategyExecution;
import com.tradingbot.paper.PaperOrder;
import com.tradingbot.paper.PaperPosition;
import com.tradingbot.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Persistence Service for trading data.
 *
 * Uses asynchronous writes to maintain HFT performance.
 * All persistence operations are non-blocking on the trading hot path.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TradePersistenceService {

    private final TradeRepository tradeRepository;
    private final StrategyExecutionRepository strategyExecutionRepository;
    private final OrderLegRepository orderLegRepository;
    private final DeltaSnapshotRepository deltaSnapshotRepository;
    private final DailyPnLSummaryRepository dailyPnLSummaryRepository;
    private final PositionSnapshotRepository positionSnapshotRepository;
    private final OrderTimingRepository orderTimingRepository;

    // ==================== TRADE PERSISTENCE ====================

    /**
     * Persist a trade asynchronously.
     * Does not block the trading hot path.
     */
    @Async("persistenceExecutor")
    @Transactional
    public CompletableFuture<TradeEntity> persistTradeAsync(TradeEntity trade) {
        try {
            TradeEntity saved = tradeRepository.save(trade);
            log.debug("Persisted trade: orderId={}, symbol={}", trade.getOrderId(), trade.getTradingSymbol());

            // Update daily summary
            updateDailySummaryForTrade(saved);

            return CompletableFuture.completedFuture(saved);
        } catch (Exception e) {
            log.error("Failed to persist trade: orderId={}", trade.getOrderId(), e);
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Create a TradeEntity from a PaperOrder
     */
    public TradeEntity createTradeFromPaperOrder(PaperOrder order, String tradingMode) {
        return TradeEntity.builder()
                .userId(order.getPlacedBy())
                .orderId(order.getOrderId())
                .exchangeOrderId(order.getExchangeOrderId())
                .tradingSymbol(order.getTradingSymbol())
                .exchange(order.getExchange())
                .instrumentToken(order.getInstrumentToken())
                .transactionType(order.getTransactionType())
                .orderType(order.getOrderType())
                .product(order.getProduct())
                .quantity(order.getQuantity())
                .entryPrice(order.getAveragePrice() != null ? BigDecimal.valueOf(order.getAveragePrice()) : null)
                .entryTimestamp(order.getExchangeTimestamp())
                .brokerage(order.getBrokerageCharges() != null ? BigDecimal.valueOf(order.getBrokerageCharges()) : null)
                .totalCharges(order.getTotalCharges() != null ? BigDecimal.valueOf(order.getTotalCharges()) : null)
                .status(order.getStatus())
                .statusMessage(order.getStatusMessage())
                .tradingMode(tradingMode)
                .tradingDate(LocalDate.now())
                .build();
    }

    /**
     * Update trade with exit information
     */
    @Async("persistenceExecutor")
    @Transactional
    public CompletableFuture<Void> updateTradeExitAsync(String orderId, BigDecimal exitPrice,
                                                         LocalDateTime exitTimestamp, String exitOrderId,
                                                         BigDecimal realizedPnl) {
        try {
            Optional<TradeEntity> tradeOpt = tradeRepository.findByOrderId(orderId);
            if (tradeOpt.isPresent()) {
                TradeEntity trade = tradeOpt.get();
                trade.setExitPrice(exitPrice);
                trade.setExitTimestamp(exitTimestamp);
                trade.setExitOrderId(exitOrderId);
                trade.setRealizedPnl(realizedPnl);
                trade.setStatus("CLOSED");
                tradeRepository.save(trade);

                log.debug("Updated trade exit: orderId={}, pnl={}", orderId, realizedPnl);
            }
            return CompletableFuture.completedFuture(null);
        } catch (Exception e) {
            log.error("Failed to update trade exit: orderId={}", orderId, e);
            return CompletableFuture.failedFuture(e);
        }
    }

    // ==================== STRATEGY EXECUTION PERSISTENCE ====================

    /**
     * Persist a strategy execution
     */
    @Async("persistenceExecutor")
    @Transactional
    public CompletableFuture<StrategyExecutionEntity> persistStrategyExecutionAsync(StrategyExecution execution) {
        try {
            StrategyExecutionEntity entity = mapToStrategyExecutionEntity(execution);
            StrategyExecutionEntity saved = strategyExecutionRepository.save(entity);

            log.debug("Persisted strategy execution: executionId={}, type={}",
                    execution.getExecutionId(), execution.getStrategyType());

            return CompletableFuture.completedFuture(saved);
        } catch (Exception e) {
            log.error("Failed to persist strategy execution: executionId={}", execution.getExecutionId(), e);
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Update strategy execution status
     */
    @Async("persistenceExecutor")
    @Transactional
    public CompletableFuture<Void> updateStrategyExecutionStatusAsync(String executionId, String status,
                                                                       String completionReason,
                                                                       BigDecimal realizedPnl) {
        try {
            Optional<StrategyExecutionEntity> entityOpt = strategyExecutionRepository.findByExecutionId(executionId);
            if (entityOpt.isPresent()) {
                StrategyExecutionEntity entity = entityOpt.get();
                entity.setStatus(status);
                entity.setCompletionReason(completionReason);
                entity.setRealizedPnl(realizedPnl);
                entity.setCompletedAt(LocalDateTime.now());
                strategyExecutionRepository.save(entity);

                log.debug("Updated strategy execution status: executionId={}, status={}", executionId, status);
            }
            return CompletableFuture.completedFuture(null);
        } catch (Exception e) {
            log.error("Failed to update strategy execution: executionId={}", executionId, e);
            return CompletableFuture.failedFuture(e);
        }
    }

    private StrategyExecutionEntity mapToStrategyExecutionEntity(StrategyExecution execution) {
        StrategyExecutionEntity entity = StrategyExecutionEntity.builder()
                .executionId(execution.getExecutionId())
                .rootExecutionId(execution.getRootExecutionId())
                .parentExecutionId(execution.getParentExecutionId())
                .userId(execution.getUserId())
                .strategyType(execution.getStrategyType() != null ? execution.getStrategyType().name() : null)
                .instrumentType(execution.getInstrumentType())
                .expiry(execution.getExpiry())
                .status(execution.getStatus() != null ? execution.getStatus().name() : null)
                .completionReason(execution.getCompletionReason() != null ? execution.getCompletionReason().name() : null)
                .message(execution.getMessage())
                .stopLossPoints(execution.getStopLossPoints() != null ? BigDecimal.valueOf(execution.getStopLossPoints()) : null)
                .targetPoints(execution.getTargetPoints() != null ? BigDecimal.valueOf(execution.getTargetPoints()) : null)
                .lots(execution.getLots())
                .entryPrice(execution.getEntryPrice() != null ? BigDecimal.valueOf(execution.getEntryPrice()) : null)
                .realizedPnl(execution.getProfitLoss() != null ? BigDecimal.valueOf(execution.getProfitLoss()) : null)
                .tradingMode(execution.getTradingMode())
                .autoRestartCount(execution.getAutoRestartCount())
                .startedAt(execution.getTimestamp() != null ?
                        LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(execution.getTimestamp()),
                                java.time.ZoneId.systemDefault()) : LocalDateTime.now())
                .build();

        // Map order legs
        if (execution.getOrderLegs() != null) {
            for (StrategyExecution.OrderLeg leg : execution.getOrderLegs()) {
                OrderLegEntity legEntity = OrderLegEntity.builder()
                        .orderId(leg.getOrderId())
                        .tradingSymbol(leg.getTradingSymbol())
                        .optionType(leg.getOptionType())
                        .quantity(leg.getQuantity())
                        .entryPrice(leg.getEntryPrice() != null ? BigDecimal.valueOf(leg.getEntryPrice()) : null)
                        .entryTransactionType(leg.getEntryTransactionType())
                        .entryTimestamp(leg.getEntryTimestamp() != null ?
                                LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(leg.getEntryTimestamp()),
                                        java.time.ZoneId.systemDefault()) : null)
                        .exitOrderId(leg.getExitOrderId())
                        .exitTransactionType(leg.getExitTransactionType())
                        .exitQuantity(leg.getExitQuantity())
                        .exitPrice(leg.getExitPrice() != null ? BigDecimal.valueOf(leg.getExitPrice()) : null)
                        .exitTimestamp(leg.getExitTimestamp() != null ?
                                LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(leg.getExitTimestamp()),
                                        java.time.ZoneId.systemDefault()) : null)
                        .exitStatus(leg.getExitStatus())
                        .exitMessage(leg.getExitMessage())
                        .realizedPnl(leg.getRealizedPnl() != null ? BigDecimal.valueOf(leg.getRealizedPnl()) : null)
                        .lifecycleState(leg.getLifecycleState() != null ? leg.getLifecycleState().name() : "OPEN")
                        .build();
                entity.addOrderLeg(legEntity);
            }
        }

        return entity;
    }

    // ==================== DELTA SNAPSHOT PERSISTENCE ====================

    /**
     * Persist a delta/Greeks snapshot
     */
    @Async("persistenceExecutor")
    @Transactional
    public CompletableFuture<DeltaSnapshotEntity> persistDeltaSnapshotAsync(DeltaSnapshotEntity snapshot) {
        try {
            DeltaSnapshotEntity saved = deltaSnapshotRepository.save(snapshot);
            log.debug("Persisted delta snapshot: instrument={}, delta={}",
                    snapshot.getInstrumentType(), snapshot.getDelta());
            return CompletableFuture.completedFuture(saved);
        } catch (Exception e) {
            log.error("Failed to persist delta snapshot", e);
            return CompletableFuture.failedFuture(e);
        }
    }

    // ==================== DAILY SUMMARY ====================

    /**
     * Get or create daily P&L summary for user
     */
    @Transactional
    public DailyPnLSummaryEntity getOrCreateDailySummary(String userId, LocalDate date, String tradingMode) {
        return dailyPnLSummaryRepository
                .findByUserIdAndTradingDateAndTradingMode(userId, date, tradingMode)
                .orElseGet(() -> {
                    DailyPnLSummaryEntity summary = DailyPnLSummaryEntity.builder()
                            .userId(userId)
                            .tradingDate(date)
                            .tradingMode(tradingMode)
                            .build();
                    return dailyPnLSummaryRepository.save(summary);
                });
    }

    /**
     * Update daily summary when a trade is recorded
     */
    @Transactional
    public void updateDailySummaryForTrade(TradeEntity trade) {
        if (trade.getRealizedPnl() == null) {
            return; // Skip if trade is still open
        }

        DailyPnLSummaryEntity summary = getOrCreateDailySummary(
                trade.getUserId(), trade.getTradingDate(), trade.getTradingMode());

        summary.recordTrade(
                trade.getRealizedPnl() != null ? trade.getRealizedPnl() : BigDecimal.ZERO,
                trade.getTotalCharges() != null ? trade.getTotalCharges() : BigDecimal.ZERO
        );

        dailyPnLSummaryRepository.save(summary);
    }

    // ==================== POSITION SNAPSHOTS ====================

    /**
     * Persist position snapshots (typically at end of day)
     */
    @Async("persistenceExecutor")
    @Transactional
    public CompletableFuture<List<PositionSnapshotEntity>> persistPositionSnapshotsAsync(
            List<PaperPosition> positions, String userId, String tradingMode) {
        try {
            List<PositionSnapshotEntity> entities = positions.stream()
                    .map(pos -> mapToPositionSnapshotEntity(pos, userId, tradingMode))
                    .toList();

            List<PositionSnapshotEntity> saved = positionSnapshotRepository.saveAll(entities);
            log.debug("Persisted {} position snapshots for user={}", saved.size(), userId);

            return CompletableFuture.completedFuture(saved);
        } catch (Exception e) {
            log.error("Failed to persist position snapshots for user={}", userId, e);
            return CompletableFuture.failedFuture(e);
        }
    }

    private PositionSnapshotEntity mapToPositionSnapshotEntity(PaperPosition pos, String userId, String tradingMode) {
        return PositionSnapshotEntity.builder()
                .userId(userId)
                .tradingSymbol(pos.getTradingSymbol())
                .exchange(pos.getExchange())
                .instrumentToken(pos.getInstrumentToken())
                .product(pos.getProduct())
                .quantity(pos.getQuantity())
                .overnightQuantity(pos.getOvernightQuantity())
                .multiplier(pos.getMultiplier())
                .buyQuantity(pos.getBuyQuantity())
                .buyPrice(pos.getBuyPrice() != null ? BigDecimal.valueOf(pos.getBuyPrice()) : null)
                .buyValue(pos.getBuyValue() != null ? BigDecimal.valueOf(pos.getBuyValue()) : null)
                .sellQuantity(pos.getSellQuantity())
                .sellPrice(pos.getSellPrice() != null ? BigDecimal.valueOf(pos.getSellPrice()) : null)
                .sellValue(pos.getSellValue() != null ? BigDecimal.valueOf(pos.getSellValue()) : null)
                .dayBuyQuantity(pos.getDayBuyQuantity())
                .dayBuyPrice(pos.getDayBuyPrice() != null ? BigDecimal.valueOf(pos.getDayBuyPrice()) : null)
                .dayBuyValue(pos.getDayBuyValue() != null ? BigDecimal.valueOf(pos.getDayBuyValue()) : null)
                .daySellQuantity(pos.getDaySellQuantity())
                .daySellPrice(pos.getDaySellPrice() != null ? BigDecimal.valueOf(pos.getDaySellPrice()) : null)
                .daySellValue(pos.getDaySellValue() != null ? BigDecimal.valueOf(pos.getDaySellValue()) : null)
                .pnl(pos.getPnl() != null ? BigDecimal.valueOf(pos.getPnl()) : null)
                .realised(pos.getRealised() != null ? BigDecimal.valueOf(pos.getRealised()) : null)
                .unrealised(pos.getUnrealised() != null ? BigDecimal.valueOf(pos.getUnrealised()) : null)
                .m2m(pos.getM2m() != null ? BigDecimal.valueOf(pos.getM2m()) : null)
                .averagePrice(pos.getAveragePrice() != null ? BigDecimal.valueOf(pos.getAveragePrice()) : null)
                .lastPrice(pos.getLastPrice() != null ? BigDecimal.valueOf(pos.getLastPrice()) : null)
                .closePrice(pos.getClosePrice() != null ? BigDecimal.valueOf(pos.getClosePrice()) : null)
                .value(pos.getValue() != null ? BigDecimal.valueOf(pos.getValue()) : null)
                .tradingMode(tradingMode)
                .snapshotDate(LocalDate.now())
                .snapshotTimestamp(LocalDateTime.now())
                .build();
    }

    // ==================== ORDER TIMING ====================

    /**
     * Persist order timing metrics
     */
    @Async("persistenceExecutor")
    @Transactional
    public CompletableFuture<OrderTimingEntity> persistOrderTimingAsync(OrderTimingEntity timing) {
        try {
            timing.calculateLatencies();
            OrderTimingEntity saved = orderTimingRepository.save(timing);
            log.debug("Persisted order timing: orderId={}, latency={}ms",
                    timing.getOrderId(), timing.getTotalLatencyMs());
            return CompletableFuture.completedFuture(saved);
        } catch (Exception e) {
            log.error("Failed to persist order timing: orderId={}", timing.getOrderId(), e);
            return CompletableFuture.failedFuture(e);
        }
    }

    // ==================== QUERY METHODS ====================

    public List<TradeEntity> getTradesForDateRange(String userId, LocalDate startDate, LocalDate endDate) {
        return tradeRepository.findByUserIdAndTradingDateBetweenOrderByEntryTimestampDesc(userId, startDate, endDate);
    }

    public List<StrategyExecutionEntity> getStrategyExecutionsForUser(String userId) {
        return strategyExecutionRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public List<DailyPnLSummaryEntity> getDailySummariesForDateRange(String userId, LocalDate startDate, LocalDate endDate) {
        return dailyPnLSummaryRepository.findByUserIdAndTradingDateBetweenOrderByTradingDateDesc(userId, startDate, endDate);
    }

    public Optional<DailyPnLSummaryEntity> getDailySummary(String userId, LocalDate date, String tradingMode) {
        return dailyPnLSummaryRepository.findByUserIdAndTradingDateAndTradingMode(userId, date, tradingMode);
    }
}

