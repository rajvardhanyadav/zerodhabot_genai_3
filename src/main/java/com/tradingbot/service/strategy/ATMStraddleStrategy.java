package com.tradingbot.service.strategy;

import com.tradingbot.dto.OrderRequest;
import com.tradingbot.dto.StrategyExecutionResponse;
import com.tradingbot.dto.StrategyRequest;
import com.tradingbot.service.TradingService;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Instrument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ATM Straddle Strategy
 * Buy 1 ATM Call + Buy 1 ATM Put
 * Non-directional strategy that profits from high volatility
 */
@Slf4j
@Component
public class ATMStraddleStrategy extends BaseStrategy {

    public ATMStraddleStrategy(TradingService tradingService, Map<String, Integer> lotSizeCache) {
        super(tradingService, lotSizeCache);
    }

    @Override
    public StrategyExecutionResponse execute(StrategyRequest request, String executionId)
            throws KiteException, IOException {

        log.info("Executing ATM Straddle for {}", request.getInstrumentType());

        // Get current spot price
        double spotPrice = getCurrentSpotPrice(request.getInstrumentType());
        log.info("Current spot price: {}", spotPrice);

        // Calculate ATM strike
        double atmStrike = getATMStrike(spotPrice, request.getInstrumentType());
        log.info("ATM Strike: {}", atmStrike);

        // Get option instruments
        List<Instrument> instruments = getOptionInstruments(
            request.getInstrumentType(),
            request.getExpiry()
        );
        log.info("Found {} option instruments for {}", instruments.size(), request.getInstrumentType());

        // Find ATM Call and Put
        Instrument atmCall = findOptionInstrument(instruments, atmStrike, "CE");
        Instrument atmPut = findOptionInstrument(instruments, atmStrike, "PE");
        log.info("ATM Call: {}, ATM Put: {}",
            atmCall != null ? atmCall.tradingsymbol : "null",
            atmPut != null ? atmPut.tradingsymbol : "null");

        if (atmCall == null || atmPut == null) {
            throw new RuntimeException("ATM options not found for strike: " + atmStrike);
        }

        List<StrategyExecutionResponse.OrderDetail> orderDetails = new ArrayList<>();
        int quantity = request.getQuantity() != null ? request.getQuantity() : getLotSize(request.getInstrumentType());
        String orderType = request.getOrderType() != null ? request.getOrderType() : "MARKET";

        // Place Call order
        log.info("Placing CALL order for {}", atmCall.tradingsymbol);
        OrderRequest callOrder = createOrderRequest(atmCall.tradingsymbol, "BUY", quantity, orderType, null);
        var callOrderResponse = tradingService.placeOrder(callOrder);

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
            atmCall.tradingsymbol,
            "CE",
            atmStrike,
            quantity,
            callPrice,
            "COMPLETED"
        ));

        // Place Put order
        log.info("Placing PUT order for {}", atmPut.tradingsymbol);
        OrderRequest putOrder = createOrderRequest(atmPut.tradingsymbol, "BUY", quantity, orderType, null);
        var putOrderResponse = tradingService.placeOrder(putOrder);

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
            atmPut.tradingsymbol,
            "PE",
            atmStrike,
            quantity,
            putPrice,
            "COMPLETED"
        ));

        double totalPremium = (callPrice + putPrice) * quantity;

        StrategyExecutionResponse response = new StrategyExecutionResponse();
        response.setExecutionId(executionId);
        response.setStatus("COMPLETED");
        response.setMessage("ATM Straddle executed successfully");
        response.setOrders(orderDetails);
        response.setTotalPremium(totalPremium);
        response.setCurrentValue(totalPremium);
        response.setProfitLoss(0.0);
        response.setProfitLossPercentage(0.0);

        log.info("ATM Straddle executed successfully. Total Premium: {}", totalPremium);
        return response;
    }

    @Override
    public String getStrategyName() {
        return "ATM Straddle";
    }

    @Override
    public String getStrategyDescription() {
        return "Buy ATM Call + Buy ATM Put (Non-directional strategy)";
    }
}

