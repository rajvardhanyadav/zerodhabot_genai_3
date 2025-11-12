package com.tradingbot.controller;

import com.tradingbot.dto.ApiResponse;
import com.tradingbot.dto.DayPnLResponse;
import com.tradingbot.service.UnifiedTradingService;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Holding;
import com.zerodhatech.models.Position;
import com.zerodhatech.models.Trade;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/portfolio")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Portfolio", description = "Portfolio and position management endpoints")
public class PortfolioController {

    private final UnifiedTradingService unifiedTradingService;

    @GetMapping("/positions")
    @Operation(summary = "Get all positions",
               description = "Returns open positions from Paper Trading or Live Trading based on configuration")
    public ResponseEntity<ApiResponse<Map<String, List<Position>>>> getPositions() throws KiteException, IOException {
        Map<String, List<Position>> positions = unifiedTradingService.getPositions();
        return ResponseEntity.ok(ApiResponse.success(positions));
    }

    @GetMapping("/holdings")
    @Operation(summary = "Get all holdings",
               description = "Returns long-term holdings (only available in Live Trading mode)")
    public ResponseEntity<ApiResponse<List<Holding>>> getHoldings() throws KiteException, IOException {
        List<Holding> holdings = unifiedTradingService.getHoldings();
        return ResponseEntity.ok(ApiResponse.success(holdings));
    }

    @GetMapping("/trades")
    @Operation(summary = "Get all trades for the day",
               description = "Returns completed trades from Paper Trading or Live Trading based on configuration")
    public ResponseEntity<ApiResponse<List<Trade>>> getTrades() throws KiteException, IOException {
        List<Trade> trades = unifiedTradingService.getTrades();
        return ResponseEntity.ok(ApiResponse.success(trades));
    }

    @PutMapping("/positions/convert")
    @Operation(summary = "Convert position product type",
               description = "Convert position from one product type to another (MIS to NRML or vice versa). Only available in Live Trading mode.")
    public ResponseEntity<ApiResponse<JSONObject>> convertPosition(
            @RequestParam String tradingSymbol,
            @RequestParam String exchange,
            @RequestParam String transactionType,
            @RequestParam String positionType,
            @RequestParam String oldProduct,
            @RequestParam String newProduct,
            @RequestParam int quantity) throws KiteException, IOException {
        JSONObject result = unifiedTradingService.convertPosition(tradingSymbol, exchange, transactionType,
                positionType, oldProduct, newProduct, quantity);
        return ResponseEntity.ok(ApiResponse.success("Position converted successfully", result));
    }

    @GetMapping("/pnl/day")
    @Operation(summary = "Get total day P&L",
               description = "Returns total realized and unrealized profit/loss for the day from all positions. Works with both Paper and Live trading modes.")
    public ResponseEntity<ApiResponse<DayPnLResponse>> getDayPnL() throws KiteException, IOException {
        DayPnLResponse dayPnL = unifiedTradingService.getDayPnL();
        return ResponseEntity.ok(ApiResponse.success(dayPnL));
    }
}
