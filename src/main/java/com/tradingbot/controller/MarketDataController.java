package com.tradingbot.controller;

import com.tradingbot.dto.ApiResponse;
import com.tradingbot.service.TradingService;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/market")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Market Data", description = "Market data and quotes endpoints")
public class MarketDataController {

    private final TradingService tradingService;

    @GetMapping("/quote")
    @Operation(summary = "Get quote for instruments")
    public ResponseEntity<ApiResponse<Map<String, Quote>>> getQuote(@RequestParam String[] instruments) {
        try {
            Map<String, Quote> quotes = tradingService.getQuote(instruments);
            return ResponseEntity.ok(ApiResponse.success(quotes));
        } catch (KiteException | IOException e) {
            log.error("Error fetching quotes", e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/ohlc")
    @Operation(summary = "Get OHLC data for instruments")
    public ResponseEntity<ApiResponse<Map<String, OHLCQuote>>> getOHLC(@RequestParam String[] instruments) {
        try {
            Map<String, OHLCQuote> ohlc = tradingService.getOHLC(instruments);
            return ResponseEntity.ok(ApiResponse.success(ohlc));
        } catch (KiteException | IOException e) {
            log.error("Error fetching OHLC data", e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/ltp")
    @Operation(summary = "Get Last Traded Price (LTP) for instruments")
    public ResponseEntity<ApiResponse<Map<String, LTPQuote>>> getLTP(@RequestParam String[] instruments) {
        try {
            Map<String, LTPQuote> ltp = tradingService.getLTP(instruments);
            return ResponseEntity.ok(ApiResponse.success(ltp));
        } catch (KiteException | IOException e) {
            log.error("Error fetching LTP", e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/historical")
    @Operation(summary = "Get historical data for an instrument")
    public ResponseEntity<ApiResponse<HistoricalData>> getHistoricalData(
            @RequestParam String instrumentToken,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam String interval,
            @RequestParam(defaultValue = "false") boolean continuous,
            @RequestParam(defaultValue = "false") boolean oi) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
            Date fromDate = sdf.parse(from);
            Date toDate = sdf.parse(to);

            HistoricalData historicalData = tradingService.getHistoricalData(
                    fromDate, toDate, instrumentToken, interval, continuous, oi);
            return ResponseEntity.ok(ApiResponse.success(historicalData));
        } catch (ParseException e) {
            log.error("Error parsing date", e);
            return ResponseEntity.badRequest().body(ApiResponse.error("Invalid date format. Use yyyy-MM-dd"));
        } catch (KiteException | IOException e) {
            log.error("Error fetching historical data", e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/instruments")
    @Operation(summary = "Get all instruments")
    public ResponseEntity<ApiResponse<List<Instrument>>> getInstruments() {
        try {
            List<Instrument> instruments = tradingService.getInstruments();
            return ResponseEntity.ok(ApiResponse.success(instruments));
        } catch (KiteException | IOException e) {
            log.error("Error fetching instruments", e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/instruments/{exchange}")
    @Operation(summary = "Get instruments for a specific exchange")
    public ResponseEntity<ApiResponse<List<Instrument>>> getInstrumentsByExchange(@PathVariable String exchange) {
        try {
            List<Instrument> instruments = tradingService.getInstruments(exchange);
            return ResponseEntity.ok(ApiResponse.success(instruments));
        } catch (KiteException | IOException e) {
            log.error("Error fetching instruments for exchange: {}", exchange, e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }
}
