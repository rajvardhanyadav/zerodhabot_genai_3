package com.tradingbot.controller;

import com.tradingbot.dto.ApiResponse;
import com.tradingbot.dto.StrategyExecutionResponse;
import com.tradingbot.dto.StrategyRequest;
import com.tradingbot.service.HistoricalReplayService;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@RequestMapping("/api/historical")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Historical Replay", description = "Backtest-like execution using recent day's historical data (per-second replay)")
public class HistoricalController {

    private final HistoricalReplayService historicalReplayService;

    @PostMapping("/execute")
    @Operation(summary = "Execute strategy with historical replay",
               description = "Accepts the same StrategyRequest as live execution, but runs in paper mode and replays the most recent trading day's data per-second.")
    public ResponseEntity<ApiResponse<StrategyExecutionResponse>> executeHistorical(
            @Valid @RequestBody StrategyRequest request) throws KiteException, IOException {
        log.info("Historical execution request received for strategy: {} / instrument: {}", request.getStrategyType(), request.getInstrumentType());
        StrategyExecutionResponse resp = historicalReplayService.executeWithHistoricalReplay(request);
        return ResponseEntity.ok(ApiResponse.success("Historical execution started", resp));
    }
}

