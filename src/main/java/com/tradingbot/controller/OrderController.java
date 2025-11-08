package com.tradingbot.controller;

import com.tradingbot.dto.ApiResponse;
import com.tradingbot.dto.OrderRequest;
import com.tradingbot.dto.OrderResponse;
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
@Tag(name = "Orders", description = "Order management endpoints")
public class OrderController {

    private final TradingService tradingService;

    @PostMapping
    @Operation(summary = "Place a new order")
    public ResponseEntity<ApiResponse<OrderResponse>> placeOrder(@Valid @RequestBody OrderRequest orderRequest) {
        try {
            OrderResponse response = tradingService.placeOrder(orderRequest);
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
            OrderResponse response = tradingService.modifyOrder(orderId, orderRequest);
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
            OrderResponse response = tradingService.cancelOrder(orderId);
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
            List<Order> orders = tradingService.getOrders();
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
            List<Order> orderHistory = tradingService.getOrderHistory(orderId);
            return ResponseEntity.ok(ApiResponse.success(orderHistory));
        } catch (KiteException | IOException e) {
            log.error("Error fetching order history", e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }
}

