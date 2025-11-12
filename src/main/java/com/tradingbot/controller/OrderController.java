package com.tradingbot.controller;

import com.tradingbot.dto.ApiResponse;
import com.tradingbot.dto.OrderRequest;
import com.tradingbot.dto.OrderResponse;
import com.tradingbot.dto.OrderChargesResponse;
import com.tradingbot.service.UnifiedTradingService;
import com.tradingbot.service.TradingService;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Order;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Orders", description = "Order management endpoints - Automatically routes to Paper or Live trading")
public class OrderController {

    private final UnifiedTradingService unifiedTradingService;
    private final TradingService tradingService;

    @PostMapping
    @Operation(summary = "Place a new order",
               description = "Place order in Paper Trading or Live Trading mode based on configuration")
    public ResponseEntity<ApiResponse<OrderResponse>> placeOrder(@Valid @RequestBody OrderRequest orderRequest) {
        try {
            OrderResponse response = unifiedTradingService.placeOrder(orderRequest);
            return ResponseEntity.ok(ApiResponse.success("Order placed successfully", response));
        } catch (KiteException | IOException e) {
            log.error("Error placing order", e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PutMapping("/{orderId}")
    @Operation(summary = "Modify an existing order")
    public ResponseEntity<ApiResponse<OrderResponse>> modifyOrder(
            @PathVariable String orderId,
            @Valid @RequestBody OrderRequest orderRequest) {
        try {
            OrderResponse response = unifiedTradingService.modifyOrder(orderId, orderRequest);
            return ResponseEntity.ok(ApiResponse.success("Order modified successfully", response));
        } catch (KiteException | IOException e) {
            log.error("Error modifying order", e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @DeleteMapping("/{orderId}")
    @Operation(summary = "Cancel an order")
    public ResponseEntity<ApiResponse<OrderResponse>> cancelOrder(@PathVariable String orderId) {
        try {
            OrderResponse response = unifiedTradingService.cancelOrder(orderId);
            return ResponseEntity.ok(ApiResponse.success("Order cancelled successfully", response));
        } catch (KiteException | IOException e) {
            log.error("Error cancelling order", e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping
    @Operation(summary = "Get all orders for the day")
    public ResponseEntity<ApiResponse<List<Order>>> getOrders() {
        try {
            List<Order> orders = unifiedTradingService.getOrders();
            return ResponseEntity.ok(ApiResponse.success(orders));
        } catch (KiteException | IOException e) {
            log.error("Error fetching orders", e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/{orderId}/history")
    @Operation(summary = "Get order history")
    public ResponseEntity<ApiResponse<List<Order>>> getOrderHistory(@PathVariable String orderId) {
        try {
            List<Order> orderHistory = unifiedTradingService.getOrderHistory(orderId);
            return ResponseEntity.ok(ApiResponse.success(orderHistory));
        } catch (KiteException | IOException e) {
            log.error("Error fetching order history", e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/charges")
    @Operation(summary = "Get charges for all executed orders today",
               description = "Fetches detailed charge breakdown from Kite API for all completed orders placed today. " +
                           "Includes brokerage, STT, exchange charges, GST, SEBI charges, and stamp duty.")
    public ResponseEntity<ApiResponse<List<OrderChargesResponse>>> getOrderCharges() {
        try {
            List<OrderChargesResponse> charges = tradingService.getOrderCharges();
            return ResponseEntity.ok(ApiResponse.success("Order charges fetched successfully", charges));
        } catch (KiteException | IOException e) {
            log.error("Error fetching order charges", e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }
}
