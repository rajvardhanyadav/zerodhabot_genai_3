package com.tradingbot.controller;

import com.tradingbot.dto.ApiResponse;
import com.tradingbot.dto.DayPnLResponse;
import com.tradingbot.service.UnifiedTradingService;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Holding;
import com.zerodhatech.models.Position;
import com.zerodhatech.models.Trade;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Positions fetched successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Missing X-User-Id header"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Kite API error")
    })
    public ResponseEntity<ApiResponse<Map<String, List<Position>>>> getPositions() throws KiteException, IOException {
        log.debug("API Request - Get all positions");
        Map<String, List<Position>> positions = unifiedTradingService.getPositions();
        log.debug("API Response - Returning {} net positions, {} day positions",
            positions.get("net") != null ? positions.get("net").size() : 0,
            positions.get("day") != null ? positions.get("day").size() : 0);
        return ResponseEntity.ok(ApiResponse.success(positions));
    }

    @GetMapping("/holdings")
    @Operation(summary = "Get all holdings",
               description = "Returns long-term holdings (only available in Live Trading mode)")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Holdings fetched successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Missing X-User-Id header"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Kite API error")
    })
    public ResponseEntity<ApiResponse<List<Holding>>> getHoldings() throws KiteException, IOException {
        log.debug("API Request - Get all holdings");
        List<Holding> holdings = unifiedTradingService.getHoldings();
        log.debug("API Response - Returning {} holdings", holdings.size());
        return ResponseEntity.ok(ApiResponse.success(holdings));
    }

    @GetMapping("/trades")
    @Operation(summary = "Get all trades for the day",
               description = "Returns completed trades in Live Trading mode. Not supported in Paper Trading mode (responds 400).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Trades fetched successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Paper trading mode does not support trades or missing header"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Kite API error")
    })
    public ResponseEntity<ApiResponse<List<Trade>>> getTrades() throws KiteException, IOException {
        log.debug("API Request - Get all trades");
        if (unifiedTradingService.isPaperTradingEnabled()) {
            // Provide a clear and explicit response instead of server error
            String msg = "Trades are not supported in paper trading mode yet. Please switch to live mode.";
            log.warn("{}", msg);
            return ResponseEntity.badRequest().body(ApiResponse.error(msg));
        }
        List<Trade> trades = unifiedTradingService.getTrades();
        log.debug("API Response - Returning {} trades", trades.size());
        return ResponseEntity.ok(ApiResponse.success(trades));
    }

    @PutMapping("/positions/convert")
    @Operation(summary = "Convert position product type",
               description = "Convert position from one product type to another (MIS to NRML or vice versa). Only available in Live Trading mode.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Position converted successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid parameters or missing header"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Kite API error")
    })
    public ResponseEntity<ApiResponse<JSONObject>> convertPosition(
            @Parameter(description = "Trading symbol", required = true, example = "NIFTY2430621000CE")
            @RequestParam String tradingSymbol,
            @Parameter(description = "Exchange segment", required = true, example = "NFO")
            @RequestParam String exchange,
            @Parameter(description = "Transaction type: BUY or SELL", required = true, example = "BUY")
            @RequestParam String transactionType,
            @Parameter(description = "Position type: net or day", required = true, example = "net")
            @RequestParam String positionType,
            @Parameter(description = "Current product type: CNC, MIS, NRML", required = true, example = "MIS")
            @RequestParam String oldProduct,
            @Parameter(description = "Target product type: CNC, MIS, NRML", required = true, example = "NRML")
            @RequestParam String newProduct,
            @Parameter(description = "Quantity to convert", required = true, example = "50")
            @RequestParam int quantity) throws KiteException, IOException {
        log.info("API Request - Convert position: {} from {} to {}", tradingSymbol, oldProduct, newProduct);
        JSONObject result = unifiedTradingService.convertPosition(tradingSymbol, exchange, transactionType,
                positionType, oldProduct, newProduct, quantity);
        log.info("API Response - Position converted successfully: {}", tradingSymbol);
        return ResponseEntity.ok(ApiResponse.success("Position converted successfully", result));
    }

    @GetMapping("/pnl/day")
    @Operation(summary = "Get total day P&L",
               description = "Returns total realized and unrealized profit/loss for the day from all positions. Works with both Paper and Live trading modes.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Day P&L returned successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Missing X-User-Id header"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Kite API error or internal error")
    })
    public ResponseEntity<ApiResponse<DayPnLResponse>> getDayPnL() throws KiteException, IOException {
        log.debug("API Request - Get day P&L");
        DayPnLResponse dayPnL = unifiedTradingService.getDayPnL();
        log.debug("API Response - Day P&L: Total={}, Realised={}, Unrealised={}",
            dayPnL.getTotalDayPnL(), dayPnL.getTotalRealised(), dayPnL.getTotalUnrealised());
        return ResponseEntity.ok(ApiResponse.success(dayPnL));
    }
}
