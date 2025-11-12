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
    public static final String STATUS_COMPLETE = "COMPLETE";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    // Instrument Names
    public static final String INSTRUMENT_NIFTY = "NIFTY";
    public static final String INSTRUMENT_BANKNIFTY = "BANKNIFTY";
    public static final String INSTRUMENT_FINNIFTY = "FINNIFTY";

    // Option Types
    public static final String OPTION_TYPE_CE = "CE";
    public static final String OPTION_TYPE_PE = "PE";

    // Private constructor to prevent instantiation
    private TradingConstants() {
        throw new AssertionError("Cannot instantiate constants class");
    }
}

