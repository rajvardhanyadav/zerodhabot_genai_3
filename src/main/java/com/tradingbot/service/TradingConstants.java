package com.tradingbot.service;

/**
 * Constants used across trading services
 * Centralizes commonly used string literals to avoid duplication and typos
 */
public final class TradingConstants {

    // Exchanges
    public static final String EXCHANGE_NFO = "NFO";
    public static final String EXCHANGE_NSE = "NSE";

    // Products
    public static final String PRODUCT_MIS = "MIS";
    public static final String PRODUCT_CNC = "CNC";
    public static final String PRODUCT_NRML = "NRML";

    // Order Varieties
    public static final String VARIETY_REGULAR = "regular";
    public static final String VARIETY_AMO = "amo";

    // Order Validity
    public static final String VALIDITY_DAY = "DAY";
    public static final String VALIDITY_IOC = "IOC";

    // Transaction Types
    public static final String TRANSACTION_BUY = "BUY";
    public static final String TRANSACTION_SELL = "SELL";

    // Order Types
    public static final String ORDER_TYPE_MARKET = "MARKET";
    public static final String ORDER_TYPE_LIMIT = "LIMIT";
    public static final String ORDER_TYPE_SL = "SL";
    public static final String ORDER_TYPE_SL_M = "SL-M";

    // Order Status
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_COMPLETE = "COMPLETE";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    // Instrument Names
    public static final String INSTRUMENT_NIFTY = "NIFTY";
    public static final String INSTRUMENT_BANKNIFTY = "BANKNIFTY";

    // Option Types
    public static final String OPTION_TYPE_CE = "CE";
    public static final String OPTION_TYPE_PE = "PE";

    // Position Types
    public static final String POSITION_NET = "net";
    public static final String POSITION_DAY = "day";

    // Statuses
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_PARTIAL = "PARTIAL";

    // Messages
    public static final String MSG_ORDER_PLACED_SUCCESS = "Order placed successfully";
    public static final String MSG_ORDER_MODIFIED_SUCCESS = "Order modified successfully";
    public static final String MSG_ORDER_CANCELLED_SUCCESS = "Order cancelled successfully";
    public static final String MSG_ORDER_VALIDATION_PENDING = "Order validation pending";
    public static final String MSG_ORDER_OPEN_WAITING_FOR_LIMIT = "Order open - waiting for limit price";
    public static final String MSG_TRIGGER_PENDING = "Trigger pending";
    public static final String MSG_ORDER_COMPLETED = "Order completed";
    public static final String MSG_ORDER_CANCELLED_BY_USER = "Order cancelled by user";

    public static final String ERR_ORDER_PLACEMENT_FAILED_NO_ID = "Order placement failed - no order ID returned";
    public static final String ERR_ORDER_PLACEMENT_FAILED = "Order placement failed: ";
    public static final String ERR_ORDER_CANCELLATION_FAILED = "Order cancellation failed";
    public static final String ERR_UNSUPPORTED_ORDER_TYPE = "Unsupported order type: ";
    public static final String ERR_INSUFFICIENT_FUNDS = "Insufficient funds. Required: %.2f, Available: %.2f";
    public static final String ERR_ORDER_NOT_FOUND = "Order not found";
    public static final String ERR_UNAUTHORIZED = "Unauthorized";
    public static final String ERR_ORDER_CANNOT_BE_CANCELLED = "Order cannot be cancelled";
    public static final String ERR_ORDER_CANNOT_BE_MODIFIED = "Order cannot be modified";

    public static final String ERR_NETWORK = "Network error: ";
    public static final String ERR_UNEXPECTED = "Unexpected error: ";

    // Validation messages
    public static final String VALIDATION_INVALID_QUANTITY = "Invalid quantity";
    public static final String VALIDATION_INVALID_LIMIT_PRICE = "Invalid limit price";
    public static final String VALIDATION_INVALID_TRIGGER_PRICE = "Invalid trigger price";

    // Transaction tax default type
    public static final String TRANSACTION_TAX_TYPE_STT = "stt";

    // Private constructor to prevent instantiation
    private TradingConstants() {
        throw new AssertionError("Cannot instantiate constants class");
    }
}
