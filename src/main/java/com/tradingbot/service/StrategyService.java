package com.tradingbot.service;

import com.tradingbot.dto.StrategyExecutionResponse;
import com.tradingbot.dto.StrategyRequest;
import com.tradingbot.model.StrategyExecution;
import com.tradingbot.service.strategy.StrategyFactory;
import com.tradingbot.service.strategy.TradingStrategy;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Instrument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class StrategyService {

    private final TradingService tradingService;
    private final StrategyFactory strategyFactory;
    private final Map<String, StrategyExecution> activeStrategies = new ConcurrentHashMap<>();
    private final Map<String, Integer> lotSizeCache = new ConcurrentHashMap<>();

    /**
     * Execute a trading strategy
     */
    public StrategyExecutionResponse executeStrategy(StrategyRequest request) throws KiteException, IOException {
        log.info("Executing strategy: {} for instrument: {}", request.getStrategyType(), request.getInstrumentType());

        String executionId = UUID.randomUUID().toString();
        StrategyExecution execution = new StrategyExecution();
        execution.setExecutionId(executionId);
        execution.setStrategyType(request.getStrategyType());
        execution.setInstrumentType(request.getInstrumentType());
        execution.setExpiry(request.getExpiry());
        execution.setStatus("EXECUTING");
        execution.setTimestamp(System.currentTimeMillis());

        activeStrategies.put(executionId, execution);

        try {
            // Get the appropriate strategy implementation from factory
            TradingStrategy strategy = strategyFactory.getStrategy(request.getStrategyType());

            // Execute the strategy
            StrategyExecutionResponse response = strategy.execute(request, executionId);

            execution.setStatus("COMPLETED");
            execution.setMessage("Strategy executed successfully");
            return response;

        } catch (Exception e) {
            execution.setStatus("FAILED");
            execution.setMessage("Strategy execution failed: " + e.getMessage());
            log.error("Strategy execution failed", e);
            throw e;
        }
    }

    /**
     * Get all active strategies
     */
    public List<StrategyExecution> getActiveStrategies() {
        return new ArrayList<>(activeStrategies.values());
    }

    /**
     * Get strategy by execution ID
     */
    public StrategyExecution getStrategy(String executionId) {
        return activeStrategies.get(executionId);
    }

    /**
     * Get available expiry dates for an instrument
     */
    public List<String> getAvailableExpiries(String instrumentType) throws KiteException, IOException {
        log.info("Fetching available expiries for instrument: {}", instrumentType);

        String exchange = "NFO";
        List<Instrument> allInstruments = tradingService.getInstruments(exchange);

        String instrumentName = switch (instrumentType.toUpperCase()) {
            case "NIFTY" -> "NIFTY";
            case "BANKNIFTY" -> "BANKNIFTY";
            case "FINNIFTY" -> "FINNIFTY";
            default -> instrumentType.toUpperCase();
        };

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");

        List<String> expiries = allInstruments.stream()
            .filter(i -> i.name != null && i.name.equals(instrumentName))
            .filter(i -> i.expiry != null)
            .filter(i -> i.expiry.after(new Date()))
            .map(i -> sdf.format(i.expiry))
            .distinct()
            .sorted()
            .collect(Collectors.toList());

        log.info("Found {} expiry dates for {}: {}", expiries.size(), instrumentType, expiries);

        if (expiries.isEmpty()) {
            log.warn("No expiries found for instrument: {}", instrumentType);
        }

        return expiries;
    }

    /**
     * Get available instruments with their details from Kite API
     * Returns instrument code, name, lot size, and strike interval
     */
    public List<InstrumentDetail> getAvailableInstruments() throws KiteException, IOException {
        log.info("Fetching available instruments from Kite API");

        List<InstrumentDetail> instrumentDetails = new ArrayList<>();
        String[] supportedInstruments = {"NIFTY", "BANKNIFTY", "FINNIFTY"};

        for (String instrumentCode : supportedInstruments) {
            try {
                int lotSize = getLotSize(instrumentCode);
                double strikeInterval = getStrikeInterval(instrumentCode);
                String displayName = getInstrumentDisplayName(instrumentCode);

                instrumentDetails.add(new InstrumentDetail(
                    instrumentCode,
                    displayName,
                    lotSize,
                    strikeInterval
                ));

                log.debug("Added instrument: {} with lot size: {}", instrumentCode, lotSize);

            } catch (Exception e) {
                log.error("Error fetching details for instrument {}: {}", instrumentCode, e.getMessage());
            }
        }

        log.info("Successfully fetched {} instruments", instrumentDetails.size());
        return instrumentDetails;
    }

    /**
     * Get lot size for instrument by fetching from Kite API
     * Results are cached for the session to avoid repeated API calls
     */
    private int getLotSize(String instrumentType) throws KiteException, IOException {
        String instrumentKey = instrumentType.toUpperCase();

        if (lotSizeCache.containsKey(instrumentKey)) {
            log.debug("Returning cached lot size for {}: {}", instrumentKey, lotSizeCache.get(instrumentKey));
            return lotSizeCache.get(instrumentKey);
        }

        log.info("Fetching lot size from Kite API for instrument: {}", instrumentKey);

        try {
            String exchange = "NFO";
            List<Instrument> allInstruments = tradingService.getInstruments(exchange);

            String instrumentName = switch (instrumentKey) {
                case "NIFTY" -> "NIFTY";
                case "BANKNIFTY" -> "BANKNIFTY";
                case "FINNIFTY" -> "FINNIFTY";
                default -> instrumentKey;
            };

            Optional<Instrument> instrument = allInstruments.stream()
                .filter(i -> i.name != null && i.name.equals(instrumentName))
                .filter(i -> i.lot_size > 0)
                .findFirst();

            if (instrument.isPresent()) {
                int lotSize = instrument.get().lot_size;
                log.info("Found lot size for {}: {}", instrumentKey, lotSize);
                lotSizeCache.put(instrumentKey, lotSize);
                return lotSize;
            } else {
                log.warn("Lot size not found in Kite API for {}, using fallback value", instrumentKey);
                int fallbackLotSize = getFallbackLotSize(instrumentKey);
                lotSizeCache.put(instrumentKey, fallbackLotSize);
                return fallbackLotSize;
            }

        } catch (Exception e) {
            log.error("Error fetching lot size from Kite API for {}: {}", instrumentKey, e.getMessage());
            log.warn("Using fallback lot size for {}", instrumentKey);
            int fallbackLotSize = getFallbackLotSize(instrumentKey);
            lotSizeCache.put(instrumentKey, fallbackLotSize);
            return fallbackLotSize;
        }
    }

    /**
     * Get fallback lot size when Kite API is unavailable
     */
    private int getFallbackLotSize(String instrumentType) {
        return switch (instrumentType.toUpperCase()) {
            case "NIFTY" -> 75;
            case "BANKNIFTY" -> 35;
            case "FINNIFTY" -> 40;
            default -> throw new IllegalArgumentException("Unsupported instrument type: " + instrumentType);
        };
    }

    /**
     * Get strike interval based on instrument
     */
    private double getStrikeInterval(String instrumentType) {
        return switch (instrumentType.toUpperCase()) {
            case "NIFTY" -> 50.0;
            case "BANKNIFTY" -> 100.0;
            case "FINNIFTY" -> 50.0;
            default -> 50.0;
        };
    }

    /**
     * Get display name for instrument
     */
    private String getInstrumentDisplayName(String instrumentCode) {
        return switch (instrumentCode.toUpperCase()) {
            case "NIFTY" -> "NIFTY 50";
            case "BANKNIFTY" -> "NIFTY BANK";
            case "FINNIFTY" -> "NIFTY FINSEREXBNK";
            default -> instrumentCode;
        };
    }

    /**
     * DTO for instrument details
     */
    public record InstrumentDetail(String code, String name, int lotSize, double strikeInterval) {}
}
