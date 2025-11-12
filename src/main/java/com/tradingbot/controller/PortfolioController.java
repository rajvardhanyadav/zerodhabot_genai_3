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
@Tag(name = "Portfolio", description = "Portfolio and position management endpoints - Automatically routes to Paper or Live trading")
public class PortfolioController {

    private final UnifiedTradingService unifiedTradingService;

    @GetMapping("/positions")
    @Operation(summary = "Get all positions",
               description = "Returns positions from Paper Trading or Live Trading based on configuration")
    public ResponseEntity<ApiResponse<Map<String, List<Position>>>> getPositions() {
        try {
            Map<String, List<Position>> positions = unifiedTradingService.getPositions();
            return ResponseEntity.ok(ApiResponse.success(positions));
        } catch (KiteException | IOException e) {
            log.error("Error fetching positions", e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/holdings")
    @Operation(summary = "Get all holdings",
               description = "Returns holdings (only available in Live Trading mode)")
    public ResponseEntity<ApiResponse<List<Holding>>> getHoldings() {
        try {
            List<Holding> holdings = unifiedTradingService.getHoldings();
            return ResponseEntity.ok(ApiResponse.success(holdings));
        } catch (KiteException | IOException e) {
            log.error("Error fetching holdings", e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/trades")
    @Operation(summary = "Get all trades for the day",
               description = "Returns trades from Paper Trading or Live Trading based on configuration")
    public ResponseEntity<ApiResponse<List<Trade>>> getTrades() {
        try {
            List<Trade> trades = unifiedTradingService.getTrades();
            return ResponseEntity.ok(ApiResponse.success(trades));
        } catch (KiteException | IOException e) {
            log.error("Error fetching trades", e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PutMapping("/positions/convert")
    @Operation(summary = "Convert position product type",
               description = "Convert position from one product type to another (only available in Live Trading mode)")
    public ResponseEntity<ApiResponse<JSONObject>> convertPosition(
            @RequestParam String tradingSymbol,
            @RequestParam String exchange,
            @RequestParam String transactionType,
            @RequestParam String positionType,
            @RequestParam String oldProduct,
            @RequestParam String newProduct,
            @RequestParam int quantity) {
        try {
            JSONObject result = unifiedTradingService.convertPosition(tradingSymbol, exchange, transactionType,
                    positionType, oldProduct, newProduct, quantity);
            return ResponseEntity.ok(ApiResponse.success("Position converted successfully", result));
        } catch (KiteException | IOException e) {
            log.error("Error converting position", e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/pnl/day")
    @Operation(summary = "Get total day P&L",
               description = "Returns total realized and unrealized P&L for the day from all positions. Works with both Paper and Live trading modes.")
    public ResponseEntity<ApiResponse<DayPnLResponse>> getDayPnL() {
        try {
            DayPnLResponse dayPnL = unifiedTradingService.getDayPnL();
            return ResponseEntity.ok(ApiResponse.success(dayPnL));
        } catch (KiteException | IOException e) {
            log.error("Error fetching day P&L", e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }
}
