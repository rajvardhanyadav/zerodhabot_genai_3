package com.tradingbot.controller;

import com.tradingbot.dto.ApiResponse;
import com.tradingbot.service.TradingService;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.GTT;
import com.zerodhatech.models.GTTParams;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/gtt")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "GTT Orders", description = "Good Till Triggered (GTT) order endpoints")
public class GTTController {

    private final TradingService tradingService;

    @GetMapping
    @Operation(summary = "Get all GTT orders")
    public ResponseEntity<ApiResponse<List<GTT>>> getGTTs() {
        try {
            List<GTT> gtts = tradingService.getGTTs();
            return ResponseEntity.ok(ApiResponse.success(gtts));
        } catch (KiteException | IOException e) {
            log.error("Error fetching GTT orders", e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping
    @Operation(summary = "Place a GTT order")
    public ResponseEntity<ApiResponse<GTT>> placeGTT(@RequestBody GTTParams gttParams) {
        try {
            GTT gtt = tradingService.placeGTT(gttParams);
            return ResponseEntity.ok(ApiResponse.success("GTT order placed successfully", gtt));
        } catch (KiteException | IOException e) {
            log.error("Error placing GTT order", e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/{triggerId}")
    @Operation(summary = "Get GTT order by ID")
    public ResponseEntity<ApiResponse<GTT>> getGTT(@PathVariable int triggerId) {
        try {
            GTT gtt = tradingService.getGTT(triggerId);
            return ResponseEntity.ok(ApiResponse.success(gtt));
        } catch (KiteException | IOException e) {
            log.error("Error fetching GTT order: {}", triggerId, e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PutMapping("/{triggerId}")
    @Operation(summary = "Modify a GTT order")
    public ResponseEntity<ApiResponse<GTT>> modifyGTT(
            @PathVariable int triggerId,
            @RequestBody GTTParams gttParams) {
        try {
            GTT gtt = tradingService.modifyGTT(triggerId, gttParams);
            return ResponseEntity.ok(ApiResponse.success("GTT order modified successfully", gtt));
        } catch (KiteException | IOException e) {
            log.error("Error modifying GTT order: {}", triggerId, e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @DeleteMapping("/{triggerId}")
    @Operation(summary = "Cancel a GTT order")
    public ResponseEntity<ApiResponse<GTT>> cancelGTT(@PathVariable int triggerId) {
        try {
            GTT result = tradingService.cancelGTT(triggerId);
            return ResponseEntity.ok(ApiResponse.success("GTT order cancelled successfully", result));
        } catch (KiteException | IOException e) {
            log.error("Error cancelling GTT order: {}", triggerId, e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }
}
