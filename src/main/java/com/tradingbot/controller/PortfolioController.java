package com.tradingbot.controller;

import com.tradingbot.dto.ApiResponse;
import com.tradingbot.service.TradingService;
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

    private final TradingService tradingService;

    @GetMapping("/positions")
    @Operation(summary = "Get all positions")
    public ResponseEntity<ApiResponse<Map<String, List<Position>>>> getPositions() {
        try {
            Map<String, List<Position>> positions = tradingService.getPositions();
            return ResponseEntity.ok(ApiResponse.success(positions));
        } catch (KiteException | IOException e) {
            log.error("Error fetching positions", e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/holdings")
    @Operation(summary = "Get all holdings")
    public ResponseEntity<ApiResponse<List<Holding>>> getHoldings() {
        try {
            List<Holding> holdings = tradingService.getHoldings();
            return ResponseEntity.ok(ApiResponse.success(holdings));
        } catch (KiteException | IOException e) {
            log.error("Error fetching holdings", e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/trades")
    @Operation(summary = "Get all trades for the day")
    public ResponseEntity<ApiResponse<List<Trade>>> getTrades() {
        try {
            List<Trade> trades = tradingService.getTrades();
            return ResponseEntity.ok(ApiResponse.success(trades));
        } catch (KiteException | IOException e) {
            log.error("Error fetching trades", e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PutMapping("/positions/convert")
    @Operation(summary = "Convert position product type")
    public ResponseEntity<ApiResponse<JSONObject>> convertPosition(
            @RequestParam String tradingSymbol,
            @RequestParam String exchange,
            @RequestParam String transactionType,
            @RequestParam String positionType,
            @RequestParam String oldProduct,
            @RequestParam String newProduct,
            @RequestParam int quantity) {
        try {
            JSONObject result = tradingService.convertPosition(tradingSymbol, exchange, transactionType,
                    positionType, oldProduct, newProduct, quantity);
            return ResponseEntity.ok(ApiResponse.success("Position converted successfully", result));
        } catch (KiteException | IOException e) {
            log.error("Error converting position", e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }
}
