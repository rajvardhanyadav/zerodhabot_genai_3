package com.tradingbot.service.strategy;

import com.tradingbot.dto.OrderRequest;
import com.tradingbot.dto.StrategyExecutionResponse;
import com.tradingbot.dto.StrategyRequest;
import com.tradingbot.service.TradingService;
import com.tradingbot.service.UnifiedTradingService;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Instrument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ATM Strangle Strategy
 * Buy 1 OTM Call + Buy 1 OTM Put
 * Lower cost than straddle with wider breakeven points
 * Supports both Paper Trading and Live Trading modes
 */
@Slf4j
@Component
public class ATMStrangleStrategy extends BaseStrategy {

    public ATMStrangleStrategy(TradingService tradingService,
                               UnifiedTradingService unifiedTradingService,
                               Map<String, Integer> lotSizeCache) {
        super(tradingService, unifiedTradingService, lotSizeCache);
    }

    @Override
    public StrategyExecutionResponse execute(StrategyRequest request, String executionId)
            throws KiteException, IOException {

        String tradingMode = unifiedTradingService.isPaperTradingEnabled() ? "PAPER" : "LIVE";
        log.info("[{} MODE] Executing ATM Strangle for {}", tradingMode, request.getInstrumentType());

        // Get current spot price
        double spotPrice = getCurrentSpotPrice(request.getInstrumentType());
        log.info("Current spot price: {}", spotPrice);

        // Calculate ATM strike
        double atmStrike = getATMStrike(spotPrice, request.getInstrumentType());

        // Get strike gap (default: 100 for NIFTY, 200 for BANKNIFTY)
        double strikeGap = request.getStrikeGap() != null ? request.getStrikeGap() :
            getDefaultStrikeGap(request.getInstrumentType());

        double callStrike = atmStrike + strikeGap;
        double putStrike = atmStrike - strikeGap;

        log.info("Strangle Strikes - Call: {}, Put: {}", callStrike, putStrike);

        // Get option instruments
        List<Instrument> instruments = getOptionInstruments(
            request.getInstrumentType(),
            request.getExpiry()
        );

        // Find OTM Call and Put
        Instrument otmCall = findOptionInstrument(instruments, callStrike, "CE");
        Instrument otmPut = findOptionInstrument(instruments, putStrike, "PE");

        if (otmCall == null || otmPut == null) {
            throw new RuntimeException("OTM options not found for strikes: " + callStrike + ", " + putStrike);
        }

        List<StrategyExecutionResponse.OrderDetail> orderDetails = new ArrayList<>();
        int quantity = request.getQuantity() != null ? request.getQuantity() : getLotSize(request.getInstrumentType());
        String orderType = request.getOrderType() != null ? request.getOrderType() : "MARKET";

        // Place Call order using UnifiedTradingService (supports paper trading)
        log.info("[{} MODE] Placing OTM CALL order for {}", tradingMode, otmCall.tradingsymbol);
        OrderRequest callOrder = createOrderRequest(otmCall.tradingsymbol, "BUY", quantity, orderType, null);
        var callOrderResponse = unifiedTradingService.placeOrder(callOrder);

        // Validate Call order response
        if (callOrderResponse == null || callOrderResponse.getOrderId() == null ||
            !"SUCCESS".equals(callOrderResponse.getStatus())) {
            String errorMsg = callOrderResponse != null ? callOrderResponse.getMessage() : "No response received";
            log.error("Call order placement failed: {}", errorMsg);
            throw new RuntimeException("Call order placement failed: " + errorMsg);
        }

        double callPrice = getOrderPrice(callOrderResponse.getOrderId());
        orderDetails.add(new StrategyExecutionResponse.OrderDetail(
            callOrderResponse.getOrderId(),
            otmCall.tradingsymbol,
            "CE",
            callStrike,
            quantity,
            callPrice,
            "COMPLETED"
        ));

        // Place Put order using UnifiedTradingService (supports paper trading)
        log.info("[{} MODE] Placing OTM PUT order for {}", tradingMode, otmPut.tradingsymbol);
        OrderRequest putOrder = createOrderRequest(otmPut.tradingsymbol, "BUY", quantity, orderType, null);
        var putOrderResponse = unifiedTradingService.placeOrder(putOrder);

        // Validate Put order response
        if (putOrderResponse == null || putOrderResponse.getOrderId() == null ||
            !"SUCCESS".equals(putOrderResponse.getStatus())) {
            String errorMsg = putOrderResponse != null ? putOrderResponse.getMessage() : "No response received";
            log.error("Put order placement failed: {}", errorMsg);
            throw new RuntimeException("Put order placement failed: " + errorMsg);
        }

        double putPrice = getOrderPrice(putOrderResponse.getOrderId());
        orderDetails.add(new StrategyExecutionResponse.OrderDetail(
            putOrderResponse.getOrderId(),
            otmPut.tradingsymbol,
            "PE",
            putStrike,
            quantity,
            putPrice,
            "COMPLETED"
        ));

        double totalPremium = (callPrice + putPrice) * quantity;

        StrategyExecutionResponse response = new StrategyExecutionResponse();
        response.setExecutionId(executionId);
        response.setStatus("COMPLETED");
        response.setMessage(String.format("[%s MODE] ATM Strangle executed successfully", tradingMode));
        response.setOrders(orderDetails);
        response.setTotalPremium(totalPremium);
        response.setCurrentValue(totalPremium);
        response.setProfitLoss(0.0);
        response.setProfitLossPercentage(0.0);

        log.info("[{} MODE] ATM Strangle executed successfully. Total Premium: {}", tradingMode, totalPremium);
        return response;
    }

    /**
     * Get default strike gap for strangle
     */
    private double getDefaultStrikeGap(String instrumentType) {
        return switch (instrumentType.toUpperCase()) {
            case "NIFTY" -> 100.0;
            case "BANKNIFTY" -> 200.0;
            case "FINNIFTY" -> 100.0;
            default -> 100.0;
        };
    }

    @Override
    public String getStrategyName() {
        return "ATM Strangle";
    }

    @Override
    public String getStrategyDescription() {
        return "Buy OTM Call + Buy OTM Put (Lower cost than straddle) - Supports Paper & Live Trading";
    }
}
