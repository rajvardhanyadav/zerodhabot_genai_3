package com.tradingbot.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * Request DTO for running multiple backtests
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BatchBacktestRequest {

    @NotEmpty(message = "At least one backtest request is required")
    private List<BacktestRequest> backtests;

    private LocalDate startDate; // Start date for batch backtest (optional)
    private LocalDate endDate; // End date for batch backtest (optional)

    private Boolean runSequentially; // Run backtests sequentially (default: false, parallel)
}

