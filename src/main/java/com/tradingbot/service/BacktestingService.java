package com.tradingbot.service;

import com.tradingbot.dto.*;
import com.tradingbot.model.StrategyStatus;
import com.tradingbot.service.strategy.monitoring.PositionMonitor;
import com.tradingbot.service.strategy.monitoring.WebSocketService;
import com.tradingbot.util.CurrentUserContext;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Instrument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for running backtests of trading strategies
 * Uses historical data to simulate strategy execution on past trading days
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BacktestingService {

    private final StrategyService strategyService;
    private final HistoricalDataService historicalDataService;
    private final WebSocketService webSocketService;
    private final UnifiedTradingService unifiedTradingService;
    private final TradingService tradingService;

    private final Map<String, BacktestExecution> backtestExecutions = new ConcurrentHashMap<>();
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * Execute a backtest for the given request
     */
    public BacktestResponse executeBacktest(BacktestRequest request) throws KiteException, IOException {
        String userId = CurrentUserContext.getRequiredUserId();
        String backtestId = UUID.randomUUID().toString();

        log.info("Starting backtest {} for strategy: {} on instrument: {} by user {}",
                backtestId, request.getStrategyType(), request.getInstrumentType(), userId);

        // Validate paper trading mode
        if (!unifiedTradingService.isPaperTradingEnabled()) {
            throw new IllegalStateException("Backtesting requires paper trading mode. Please enable trading.paperTradingEnabled=true.");
        }

        LocalDateTime startTime = LocalDateTime.now(IST);

        try {
            // Determine backtest date (default to latest previous trading day)
            HistoricalDataService.DayRange dayRange = determinBacktestDate(request.getBacktestDate());
            LocalDate backtestDate = dayRange.localDate;

            log.info("Backtest {} will run on date: {} (window: {} to {})",
                    backtestId, backtestDate, dayRange.start, dayRange.end);

            // Create backtest execution tracker
            BacktestExecution execution = new BacktestExecution();
            execution.setBacktestId(backtestId);
            execution.setUserId(userId);
            execution.setStrategyType(request.getStrategyType());
            execution.setInstrumentType(request.getInstrumentType());
            execution.setBacktestDate(backtestDate);
            execution.setStartTime(startTime);
            backtestExecutions.put(backtestId, execution);

            // Convert backtest request to strategy request
            StrategyRequest strategyRequest = convertToStrategyRequest(request, dayRange);

            // Temporarily disable live WebSocket subscription
            webSocketService.setLiveSubscriptionEnabled(false);

            try {
                // Execute the strategy
                StrategyExecutionResponse strategyResponse = strategyService.executeStrategy(strategyRequest);
                String executionId = strategyResponse.getExecutionId();
                execution.setExecutionId(executionId);

                if (strategyResponse == null || executionId == null) {
                    throw new RuntimeException("Strategy execution failed: no execution ID returned");
                }

                // Get the monitor
                Optional<PositionMonitor> monitorOpt = webSocketService.getMonitor(executionId);
                if (monitorOpt.isEmpty()) {
                    throw new RuntimeException("No monitor found for execution " + executionId);
                }

                PositionMonitor monitor = monitorOpt.get();
                List<PositionMonitor.LegMonitor> legs = monitor.getLegs();

                if (legs.isEmpty()) {
                    throw new RuntimeException("No legs found in monitor for execution " + executionId);
                }

                // Fetch historical data for all legs
                Map<Long, NavigableMap<Long, Double>> tokenToSecondPrices = fetchHistoricalDataForLegs(
                        legs, dayRange.start, dayRange.end, userId);

                // Run the backtest replay and collect results
                BacktestResponse response = runBacktestReplay(
                        backtestId,
                        executionId,
                        monitor,
                        tokenToSecondPrices,
                        request,
                        strategyResponse,
                        dayRange,
                        startTime
                );

                execution.setEndTime(LocalDateTime.now(IST));
                execution.setCompleted(true);
                execution.setResponse(response);

                return response;

            } finally {
                // Re-enable live WebSocket subscription
                webSocketService.setLiveSubscriptionEnabled(true);
            }

        } catch (Exception e) {
            log.error("Backtest {} failed: {}", backtestId, e.getMessage(), e);

            LocalDateTime endTime = LocalDateTime.now(IST);
            return BacktestResponse.builder()
                    .backtestId(backtestId)
                    .strategyType(request.getStrategyType().name())
                    .instrumentType(request.getInstrumentType())
                    .backtestDate(request.getBacktestDate())
                    .startTime(startTime)
                    .endTime(endTime)
                    .durationMs(Duration.between(startTime, endTime).toMillis())
                    .status("FAILED")
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    /**
     * Determine the backtest date based on request or use latest previous trading day
     */
    private HistoricalDataService.DayRange determinBacktestDate(LocalDate requestedDate) {
        if (requestedDate != null) {
            // Use requested date
            ZonedDateTime startZdt = requestedDate.atTime(9, 15).atZone(IST);
            ZonedDateTime endZdt = requestedDate.atTime(15, 30).atZone(IST);
            return new HistoricalDataService.DayRange(
                    Date.from(startZdt.toInstant()),
                    Date.from(endZdt.toInstant()),
                    requestedDate
            );
        } else {
            // Use latest previous trading day
            return historicalDataService.mostRecentTradingDayWindow();
        }
    }

    /**
     * Convert backtest request to strategy request
     */
    private StrategyRequest convertToStrategyRequest(BacktestRequest request, HistoricalDataService.DayRange dayRange)
            throws KiteException, IOException {
        StrategyRequest strategyRequest = new StrategyRequest();
        strategyRequest.setStrategyType(request.getStrategyType());
        strategyRequest.setInstrumentType(request.getInstrumentType());
        strategyRequest.setLots(request.getLots() != null ? request.getLots() : 1);
        strategyRequest.setOrderType(request.getOrderType() != null ? request.getOrderType() : "MARKET");
        strategyRequest.setStrikeGap(request.getStrikeGap());
        strategyRequest.setStopLossPoints(request.getStopLossPoints());
        strategyRequest.setTargetPoints(request.getTargetPoints());

        // Determine expiry for the backtest date
        String expiry = determineExpiryForBacktest(request, dayRange.localDate);
        strategyRequest.setExpiry(expiry);

        return strategyRequest;
    }

    /**
     * Determine the appropriate expiry date for the backtest
     */
    private String determineExpiryForBacktest(BacktestRequest request, LocalDate backtestDate)
            throws KiteException, IOException {
        if (request.getExpiry() != null && !request.getExpiry().isEmpty()) {
            return request.getExpiry();
        }

        // Find the nearest expiry on or after the backtest date
        List<String> expiries = strategyService.getAvailableExpiries(request.getInstrumentType());

        for (String expiry : expiries) {
            LocalDate expiryDate = LocalDate.parse(expiry, DATE_FORMATTER);
            if (!expiryDate.isBefore(backtestDate)) {
                log.info("Selected expiry {} for backtest date {}", expiry, backtestDate);
                return expiry;
            }
        }

        // If no future expiry found, use the last available expiry
        if (!expiries.isEmpty()) {
            String lastExpiry = expiries.get(expiries.size() - 1);
            log.warn("No expiry found on or after backtest date {}. Using last available expiry: {}",
                    backtestDate, lastExpiry);
            return lastExpiry;
        }

        throw new RuntimeException("No expiry dates available for instrument: " + request.getInstrumentType());
    }

    /**
     * Fetch historical data for all legs
     */
    private Map<Long, NavigableMap<Long, Double>> fetchHistoricalDataForLegs(
            List<PositionMonitor.LegMonitor> legs,
            Date start,
            Date end,
            String userId) {

        Map<Long, NavigableMap<Long, Double>> tokenToSecondPrices = new ConcurrentHashMap<>();

        for (PositionMonitor.LegMonitor leg : legs) {
            long token = leg.getInstrumentToken();
            try {
                // Temporarily set user context for this operation
                String previousUser = CurrentUserContext.getUserId();
                try {
                    CurrentUserContext.setUserId(userId);
                    NavigableMap<Long, Double> prices = historicalDataService.getSecondWisePricesForToken(token, start, end);
                    tokenToSecondPrices.put(token, prices);
                    log.info("Fetched {} price points for token {}", prices.size(), token);
                } finally {
                    if (previousUser != null) {
                        CurrentUserContext.setUserId(previousUser);
                    } else {
                        CurrentUserContext.clear();
                    }
                }
            } catch (Exception e) {
                log.error("Error fetching historical data for token {}: {}", token, e.getMessage(), e);
                tokenToSecondPrices.put(token, new TreeMap<>());
            }
        }

        return tokenToSecondPrices;
    }

    /**
     * Run the backtest replay and collect detailed results
     */
    private BacktestResponse runBacktestReplay(
            String backtestId,
            String executionId,
            PositionMonitor monitor,
            Map<Long, NavigableMap<Long, Double>> tokenToSecondPrices,
            BacktestRequest request,
            StrategyExecutionResponse strategyResponse,
            HistoricalDataService.DayRange dayRange,
            LocalDateTime startTime) {

        log.info("Starting backtest replay for backtest {} execution {}", backtestId, executionId);

        List<BacktestResponse.TradeEvent> tradeEvents = new ArrayList<>();
        List<PositionMonitor.LegMonitor> legs = monitor.getLegs();

        // Record entry event
        Map<String, Double> entryPrices = new HashMap<>();
        double totalEntryPremium = 0.0;
        for (PositionMonitor.LegMonitor leg : legs) {
            entryPrices.put(leg.getSymbol(), leg.getEntryPrice());
            totalEntryPremium += leg.getEntryPrice() * leg.getQuantity();
        }

        LocalDateTime entryTime = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(dayRange.start.getTime()), IST);

        tradeEvents.add(BacktestResponse.TradeEvent.builder()
                .timestamp(entryTime)
                .eventType("ENTRY")
                .description("Strategy executed - positions entered")
                .prices(new HashMap<>(entryPrices))
                .totalValue(totalEntryPremium)
                .unrealizedPnL(0.0)
                .build());

        // Merge all seconds across tokens
        NavigableSet<Long> allSeconds = new TreeSet<>();
        for (NavigableMap<Long, Double> m : tokenToSecondPrices.values()) {
            allSeconds.addAll(m.keySet());
        }

        log.info("Replaying {} seconds of data for backtest {}", allSeconds.size(), backtestId);

        // Track metrics
        double maxProfit = 0.0;
        double maxDrawdown = 0.0;
        boolean includeDetailedLogs = request.getIncludeDetailedLogs() != null && request.getIncludeDetailedLogs();

        // Replay tick by tick
        int tickCount = 0;
        for (Long sec : allSeconds) {
            if (!monitor.isActive()) {
                log.info("Monitor inactive for execution {}. Ending replay at tick {}", executionId, tickCount);
                break;
            }

            Map<Long, Double> tickPrices = new HashMap<>();
            for (Map.Entry<Long, NavigableMap<Long, Double>> e : tokenToSecondPrices.entrySet()) {
                Map.Entry<Long, Double> floor = e.getValue().floorEntry(sec);
                if (floor != null) {
                    tickPrices.put(e.getKey(), floor.getValue());
                }
            }

            if (!tickPrices.isEmpty()) {
                monitor.updateWithTokenPrices(tickPrices);

                // Calculate current P&L
                double currentValue = 0.0;
                Map<String, Double> currentPrices = new HashMap<>();
                for (PositionMonitor.LegMonitor leg : legs) {
                    Double price = tickPrices.get(leg.getInstrumentToken());
                    if (price != null) {
                        currentValue += price * leg.getQuantity();
                        currentPrices.put(leg.getSymbol(), price);
                    }
                }

                double unrealizedPnL = currentValue - totalEntryPremium;

                // Update max profit and drawdown
                if (unrealizedPnL > maxProfit) {
                    maxProfit = unrealizedPnL;
                }
                if (unrealizedPnL < maxDrawdown) {
                    maxDrawdown = unrealizedPnL;
                }

                // Log price updates periodically or if detailed logs are enabled
                if (includeDetailedLogs || tickCount % 300 == 0) { // Every 5 minutes or if detailed
                    LocalDateTime tickTime = LocalDateTime.ofInstant(Instant.ofEpochSecond(sec), IST);
                    tradeEvents.add(BacktestResponse.TradeEvent.builder()
                            .timestamp(tickTime)
                            .eventType("PRICE_UPDATE")
                            .description("Price update")
                            .prices(new HashMap<>(currentPrices))
                            .totalValue(currentValue)
                            .unrealizedPnL(unrealizedPnL)
                            .build());
                }

                tickCount++;
            }
        }

        log.info("Backtest replay completed for backtest {}. Processed {} ticks.", backtestId, tickCount);

        // Get final status from strategy execution
        var strategyExecution = strategyService.getStrategy(executionId);
        String completionReason = strategyExecution != null && strategyExecution.getCompletionReason() != null
                ? strategyExecution.getCompletionReason().name()
                : "UNKNOWN";

        // Build leg details
        List<BacktestResponse.LegDetail> legDetails = buildLegDetails(legs, strategyResponse);

        // Calculate final performance metrics
        BacktestResponse.PerformanceMetrics metrics = calculatePerformanceMetrics(
                legDetails, totalEntryPremium, maxProfit, maxDrawdown, entryTime, LocalDateTime.now(IST));

        // Record exit event
        LocalDateTime exitTime = LocalDateTime.now(IST);
        tradeEvents.add(BacktestResponse.TradeEvent.builder()
                .timestamp(exitTime)
                .eventType("EXIT")
                .description("Strategy completed - " + completionReason)
                .prices(new HashMap<>())
                .totalValue(metrics.getTotalPremiumReceived())
                .unrealizedPnL(metrics.getGrossProfitLoss())
                .build());

        LocalDateTime endTime = LocalDateTime.now(IST);

        return BacktestResponse.builder()
                .backtestId(backtestId)
                .strategyType(request.getStrategyType().name())
                .instrumentType(request.getInstrumentType())
                .backtestDate(dayRange.localDate)
                .startTime(startTime)
                .endTime(endTime)
                .durationMs(Duration.between(startTime, endTime).toMillis())
                .executionId(executionId)
                .status("COMPLETED")
                .completionReason(completionReason)
                .spotPriceAtEntry(strategyResponse.getOrders() != null && !strategyResponse.getOrders().isEmpty()
                        ? strategyResponse.getOrders().get(0).getStrike() : null)
                .atmStrike(strategyResponse.getOrders() != null && !strategyResponse.getOrders().isEmpty()
                        ? strategyResponse.getOrders().get(0).getStrike() : null)
                .legs(legDetails)
                .performanceMetrics(metrics)
                .tradeEvents(tradeEvents)
                .build();
    }

    /**
     * Build leg details from monitor and strategy response
     */
    private List<BacktestResponse.LegDetail> buildLegDetails(
            List<PositionMonitor.LegMonitor> legs,
            StrategyExecutionResponse strategyResponse) {

        List<BacktestResponse.LegDetail> legDetails = new ArrayList<>();

        for (int i = 0; i < legs.size(); i++) {
            PositionMonitor.LegMonitor leg = legs.get(i);
            StrategyExecutionResponse.OrderDetail order = i < strategyResponse.getOrders().size()
                    ? strategyResponse.getOrders().get(i)
                    : null;

            double entryPrice = leg.getEntryPrice();
            double exitPrice = leg.getCurrentPrice();
            double pnl = (exitPrice - entryPrice) * leg.getQuantity();
            double pnlPercentage = entryPrice > 0 ? (pnl / (entryPrice * leg.getQuantity())) * 100 : 0;

            legDetails.add(BacktestResponse.LegDetail.builder()
                    .tradingSymbol(leg.getSymbol())
                    .optionType(order != null ? order.getOptionType() : "")
                    .strike(order != null ? order.getStrike() : 0.0)
                    .quantity(leg.getQuantity())
                    .entryPrice(entryPrice)
                    .exitPrice(exitPrice)
                    .entryTime(LocalDateTime.now(IST)) // Would need to track actual entry time
                    .exitTime(LocalDateTime.now(IST))  // Would need to track actual exit time
                    .profitLoss(pnl)
                    .profitLossPercentage(pnlPercentage)
                    .build());
        }

        return legDetails;
    }

    /**
     * Calculate comprehensive performance metrics
     */
    private BacktestResponse.PerformanceMetrics calculatePerformanceMetrics(
            List<BacktestResponse.LegDetail> legs,
            double totalEntryPremium,
            double maxProfit,
            double maxDrawdown,
            LocalDateTime entryTime,
            LocalDateTime exitTime) {

        double totalExitValue = legs.stream()
                .mapToDouble(leg -> leg.getExitPrice() * leg.getQuantity())
                .sum();

        double grossPnL = totalExitValue - totalEntryPremium;

        // Calculate charges (simplified - would use actual paper trading charges)
        double charges = calculateCharges(totalEntryPremium, totalExitValue);

        double netPnL = grossPnL - charges;
        double returnPercentage = totalEntryPremium > 0 ? (netPnL / totalEntryPremium) * 100 : 0;
        double roi = totalEntryPremium > 0 ? (netPnL / totalEntryPremium) * 100 : 0;

        long holdingDurationMs = Duration.between(entryTime, exitTime).toMillis();
        String holdingDurationFormatted = formatDuration(holdingDurationMs);

        return BacktestResponse.PerformanceMetrics.builder()
                .totalPremiumPaid(totalEntryPremium)
                .totalPremiumReceived(totalExitValue)
                .grossProfitLoss(grossPnL)
                .charges(charges)
                .netProfitLoss(netPnL)
                .returnPercentage(returnPercentage)
                .returnOnInvestment(roi)
                .maxDrawdown(maxDrawdown)
                .maxProfit(maxProfit)
                .numberOfTrades(legs.size() * 2) // Entry + Exit for each leg
                .holdingDurationMs(holdingDurationMs)
                .holdingDurationFormatted(holdingDurationFormatted)
                .build();
    }

    /**
     * Calculate trading charges
     */
    private double calculateCharges(double entryValue, double exitValue) {
        // Simplified charge calculation
        // In production, this should use the actual PaperTradingConfig values
        double brokerage = 40.0; // ₹20 per order * 2 orders
        double stt = (exitValue * 0.025 / 100); // 0.025% on sell side
        double transactionCharges = ((entryValue + exitValue) * 0.00325 / 100);
        double gst = (brokerage + transactionCharges) * 0.18;
        double sebiCharges = (entryValue + exitValue) * 0.0001 / 100;
        double stampDuty = entryValue * 0.003 / 100;

        return brokerage + stt + transactionCharges + gst + sebiCharges + stampDuty;
    }

    /**
     * Format duration in human-readable format
     */
    private String formatDuration(long durationMs) {
        Duration duration = Duration.ofMillis(durationMs);
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        long seconds = duration.toSecondsPart();

        if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes, seconds);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds);
        } else {
            return String.format("%ds", seconds);
        }
    }

    /**
     * Get backtest execution by ID
     */
    public BacktestExecution getBacktestExecution(String backtestId) {
        return backtestExecutions.get(backtestId);
    }

    /**
     * Internal class to track backtest execution
     */
    @lombok.Data
    public static class BacktestExecution {
        private String backtestId;
        private String userId;
        private com.tradingbot.model.StrategyType strategyType;
        private String instrumentType;
        private LocalDate backtestDate;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private String executionId;
        private boolean completed;
        private BacktestResponse response;
    }
}

