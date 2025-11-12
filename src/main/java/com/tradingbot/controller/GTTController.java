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
@Tag(name = "GTT Orders", description = "Good Till Triggered (GTT) order management endpoints")
public class GTTController {

    private final TradingService tradingService;

    @GetMapping
    @Operation(summary = "Get all GTT orders",
               description = "Fetch all active GTT orders")
    public ResponseEntity<ApiResponse<List<GTT>>> getGTTs() throws KiteException, IOException {
        List<GTT> gtts = tradingService.getGTTs();
        return ResponseEntity.ok(ApiResponse.success(gtts));
    }

    @PostMapping
    @Operation(summary = "Place a GTT order",
               description = "Create a new Good Till Triggered order with specified conditions")
    public ResponseEntity<ApiResponse<GTT>> placeGTT(@RequestBody GTTParams gttParams) throws KiteException, IOException {
        GTT gtt = tradingService.placeGTT(gttParams);
        return ResponseEntity.ok(ApiResponse.success("GTT order placed successfully", gtt));
    }

    @GetMapping("/{triggerId}")
    @Operation(summary = "Get GTT order by ID",
               description = "Fetch specific GTT order details by trigger ID")
    public ResponseEntity<ApiResponse<GTT>> getGTT(@PathVariable int triggerId) throws KiteException, IOException {
        GTT gtt = tradingService.getGTT(triggerId);
        return ResponseEntity.ok(ApiResponse.success(gtt));
    }

    @PutMapping("/{triggerId}")
    @Operation(summary = "Modify a GTT order",
               description = "Update trigger conditions or order parameters of an existing GTT order")
    public ResponseEntity<ApiResponse<GTT>> modifyGTT(
            @PathVariable int triggerId,
            @RequestBody GTTParams gttParams) throws KiteException, IOException {
        GTT gtt = tradingService.modifyGTT(triggerId, gttParams);
        return ResponseEntity.ok(ApiResponse.success("GTT order modified successfully", gtt));
    }

    @DeleteMapping("/{triggerId}")
    @Operation(summary = "Cancel a GTT order",
               description = "Delete/cancel an active GTT order by trigger ID")
    public ResponseEntity<ApiResponse<GTT>> cancelGTT(@PathVariable int triggerId) throws KiteException, IOException {
        GTT result = tradingService.cancelGTT(triggerId);
        return ResponseEntity.ok(ApiResponse.success("GTT order cancelled successfully", result));
    }
}
