package com.tradingbot.backtest.engine;

/**
 * Exception for backtest-specific errors.
 * Carries a structured error code for API responses.
 */
public class BacktestException extends RuntimeException {

    public enum ErrorCode {
        INVALID_DATE,
        INSTRUMENT_NOT_FOUND,
        DATA_FETCH_FAILED,
        SIMULATION_ERROR,
        BACKTEST_DISABLED
    }

    private final ErrorCode errorCode;

    public BacktestException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BacktestException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}

