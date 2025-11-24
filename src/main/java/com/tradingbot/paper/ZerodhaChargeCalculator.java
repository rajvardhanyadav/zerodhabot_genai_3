package com.tradingbot.paper;

import com.tradingbot.paper.entity.OrderCharges;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

@Service
public class ZerodhaChargeCalculator {

    private static final BigDecimal GST_RATE = new BigDecimal("0.18");
    private static final BigDecimal SEBI_RATE = new BigDecimal("0.000001"); // ₹10 per crore
    private static final MathContext MC = new MathContext(10, RoundingMode.HALF_UP);

    public enum OrderType { DELIVERY, INTRADAY, FUTURES, OPTIONS }
    public enum TransactionType { BUY, SELL }

    /**
     * Calculate charges for a single order leg.
     * @param turnover For Equities/Futures: Price * Qty. For Options: Premium * Qty.
     */
    public OrderCharges calculateCharges(OrderType orderType, TransactionType txnType, BigDecimal turnover, BigDecimal qty) {

        // 1. Brokerage
        BigDecimal brokerage = calculateBrokerage(orderType, turnover);

        // 2. STT (Securities Transaction Tax)
        BigDecimal stt = calculateSTT(orderType, txnType, turnover);

        // 3. Exchange Transaction Charges (Assuming NSE rates for 2025)
        BigDecimal txnCharge = calculateTxnCharges(orderType, turnover);

        // 4. SEBI Charges
        BigDecimal sebiCharge = turnover.multiply(SEBI_RATE, MC);

        // 5. Stamp Duty (Buy side only)
        BigDecimal stampDuty = (txnType == TransactionType.BUY)
                ? calculateStampDuty(orderType, turnover)
                : BigDecimal.ZERO;

        // 6. GST (18% on Brokerage + Txn Charges + SEBI)
        BigDecimal gstBase = brokerage.add(txnCharge).add(sebiCharge);
        BigDecimal gst = gstBase.multiply(GST_RATE, MC);

        // Total
        BigDecimal totalCharges = brokerage.add(stt).add(txnCharge).add(gst).add(sebiCharge).add(stampDuty);

        // Net turnover (Money leaving/entering account)
        BigDecimal netTurnover = (txnType == TransactionType.BUY)
                ? turnover.add(totalCharges)
                : turnover.subtract(totalCharges);

        return OrderCharges.builder()
                .brokerage(round(brokerage))
                .stt(round(stt))
                .exchangeTxnCharge(round(txnCharge))
                .gst(round(gst))
                .sebiCharge(round(sebiCharge))
                .stampDuty(round(stampDuty))
                .totalCharges(round(totalCharges))
                .netTurnover(round(netTurnover))
                .build();
    }

    private BigDecimal calculateBrokerage(OrderType type, BigDecimal turnover) {
        BigDecimal flatFee = new BigDecimal("20.00");

        switch (type) {
            case DELIVERY:
                return BigDecimal.ZERO;
            case OPTIONS:
                return flatFee; // Flat 20 for options
            case INTRADAY:
            case FUTURES:
                // Lower of ₹20 or 0.03%
                BigDecimal percentageFee = turnover.multiply(new BigDecimal("0.0003"), MC);
                return percentageFee.compareTo(flatFee) < 0 ? percentageFee : flatFee;
            default:
                return BigDecimal.ZERO;
        }
    }

    private BigDecimal calculateSTT(OrderType type, TransactionType txnType, BigDecimal turnover) {
        switch (type) {
            case DELIVERY:
                return turnover.multiply(new BigDecimal("0.001"), MC); // 0.1% Buy & Sell
            case INTRADAY:
                return (txnType == TransactionType.SELL)
                        ? turnover.multiply(new BigDecimal("0.00025"), MC) // 0.025% Sell only
                        : BigDecimal.ZERO;
            case FUTURES:
                return (txnType == TransactionType.SELL)
                        ? turnover.multiply(new BigDecimal("0.0002"), MC) // 0.02% Sell only
                        : BigDecimal.ZERO;
            case OPTIONS:
                return (txnType == TransactionType.SELL)
                        ? turnover.multiply(new BigDecimal("0.001"), MC) // 0.1% on Premium Sell
                        : BigDecimal.ZERO;
            default:
                return BigDecimal.ZERO;
        }
    }

    private BigDecimal calculateTxnCharges(OrderType type, BigDecimal turnover) {
        // NSE Rates 2025
        switch (type) {
            case DELIVERY:
            case INTRADAY:
                return turnover.multiply(new BigDecimal("0.0000297"), MC); // 0.00297%
            case FUTURES:
                return turnover.multiply(new BigDecimal("0.0000173"), MC); // 0.00173%
            case OPTIONS:
                return turnover.multiply(new BigDecimal("0.0003503"), MC); // 0.03503%
            default:
                return BigDecimal.ZERO;
        }
    }

    private BigDecimal calculateStampDuty(OrderType type, BigDecimal turnover) {
        switch (type) {
            case DELIVERY:
                return turnover.multiply(new BigDecimal("0.00015"), MC); // 0.015%
            case INTRADAY:
            case OPTIONS:
                return turnover.multiply(new BigDecimal("0.00003"), MC); // 0.003%
            case FUTURES:
                return turnover.multiply(new BigDecimal("0.00002"), MC); // 0.002%
            default:
                return BigDecimal.ZERO;
        }
    }

    private BigDecimal round(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
