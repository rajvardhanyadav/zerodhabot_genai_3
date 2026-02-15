package com.tradingbot.service.strategy.monitoring.exit;

import com.tradingbot.service.strategy.monitoring.LegMonitor;
import lombok.extern.slf4j.Slf4j;

/**
 * HFT-optimized premium-based exit strategy for SELL straddles.
 * <p>
 * Monitors combined premium (CE + PE) relative to entry premium and triggers
 * exits based on percentage decay (profit) or expansion (loss).
 *
 * <h2>Exit Logic (for SHORT positions)</h2>
 * <ul>
 *   <li><b>Target (Profit)</b>: Combined LTP decays to targetPremiumLevel = entryPremium * (1 - targetDecayPct)</li>
 *   <li><b>Stop Loss</b>: Combined LTP expands to slPremiumLevel = entryPremium * (1 + stopLossExpansionPct)</li>
 *   <li><b>Leg Adjustment</b>: When combinedLTP reaches halfThreshold, exit profitable leg and replace</li>
 * </ul>
 *
 * <h2>HFT Optimizations</h2>
 * <ul>
 *   <li>Pre-computed target and SL levels (passed via ExitContext)</li>
 *   <li>Single pass calculation of combined LTP</li>
 *   <li>Pre-built exit reason prefixes</li>
 *   <li>Early return on first matched condition</li>
 * </ul>
 *
 * @see ExitStrategy
 */
@Slf4j
public class PremiumBasedExitStrategy extends AbstractExitStrategy {

    /** Priority: 50 (premium exits evaluated after forced time exit) */
    private static final int PRIORITY = 50;

    // HFT: Pre-built exit reason components
    private static final String EXIT_PREFIX_DECAY_TARGET = "PREMIUM_DECAY_TARGET_HIT (Combined LTP: ";
    private static final String EXIT_PREFIX_EXPANSION_SL = "PREMIUM_EXPANSION_SL_HIT (Combined LTP: ";
    private static final String EXIT_PREFIX_LEG_ADJUSTMENT = "PREMIUM_LEG_ADJUSTMENT (Profitable leg: ";
    private static final String EXIT_SUFFIX_ENTRY = ", Entry: ";
    private static final String EXIT_SUFFIX_TARGET_LEVEL = ", TargetLevel: ";
    private static final String EXIT_SUFFIX_SL_LEVEL = ", SL Level: ";
    private static final String EXIT_SUFFIX_HALF_THRESHOLD = ", HalfThreshold: ";
    private static final String EXIT_SUFFIX_COMBINED_LTP = ", CombinedLTP: ";
    private static final String EXIT_SUFFIX_CLOSE = ")";

    @Override
    public int getPriority() {
        return PRIORITY;
    }

    @Override
    public String getName() {
        return "PremiumBasedExit";
    }

    @Override
    public ExitResult evaluate(ExitContext ctx) {
        final LegMonitor[] legs = ctx.getLegs();
        final int count = ctx.getLegsCount();

        if (count == 0) {
            return ExitResult.noExit();
        }

        // Calculate combined current premium (sum of all leg current prices)
        double combinedLTP = 0.0;
        for (int i = 0; i < count; i++) {
            combinedLTP += legs[i].getCurrentPrice();
        }
        // Store in context for potential reuse by other strategies
        ctx.setCombinedLTP(combinedLTP);

        // HFT: Cache pre-computed threshold levels for fast comparison
        final double targetLevel = ctx.getTargetPremiumLevel();
        final double slLevel = ctx.getStopLossPremiumLevel();
        final double entryPremium = ctx.getEntryPremium();
        final double dirMult = ctx.getDirectionMultiplier();

        // HFT: Lazy debug logging
        if (log.isDebugEnabled()) {
            log.debug("Premium check for {}: combinedLTP={}, entryPremium={}, targetLevel={}, slLevel={}",
                    ctx.getExecutionId(), formatDouble(combinedLTP), formatDouble(entryPremium),
                    formatDouble(targetLevel), formatDouble(slLevel));
        }

        // PREMIUM TARGET: Combined LTP has decayed below target level (profit for SHORT)
        // For SHORT straddle: We sold premium, so lower LTP = profit
        if (combinedLTP <= targetLevel) {
            log.warn("PREMIUM_DECAY_TARGET_HIT for execution {}: combinedLTP={}, targetLevel={}, entryPremium={} - Closing ALL legs",
                    ctx.getExecutionId(), formatDouble(combinedLTP), formatDouble(targetLevel), formatDouble(entryPremium));
            return ExitResult.exitAll(buildExitReasonDecayTarget(combinedLTP, entryPremium, targetLevel));
        }

        // PREMIUM STOP LOSS: Combined LTP has expanded above stop loss level (loss for SHORT)
        // For SHORT straddle: We sold premium, so higher LTP = loss
        if (combinedLTP >= slLevel) {
            log.warn("PREMIUM_EXPANSION_SL_HIT for execution {}: combinedLTP={}, slLevel={}, entryPremium={} - Closing ALL legs",
                    ctx.getExecutionId(), formatDouble(combinedLTP), formatDouble(slLevel), formatDouble(entryPremium));
            return ExitResult.exitAll(buildExitReasonExpansionSL(combinedLTP, entryPremium, slLevel));
        }

        // PREMIUM-BASED INDIVIDUAL LEG ADJUSTMENT
        // When combinedLTP reaches half the distance between entryPremium and slLevel:
        // 1. Exit the profitable leg (the one with lower current price for SHORT)
        // 2. Request new leg with similar premium to the loss-making leg
        if (count >= 2 && ctx.hasIndividualLegExitCallback()) {
            final double halfThreshold = entryPremium + (slLevel - entryPremium) / 2.0;

            if (combinedLTP >= halfThreshold) {
                // Find profitable and loss-making legs
                // For SHORT: profitable leg has lower current price (premium decayed)
                //            loss-making leg has higher current price (premium expanded)
                LegMonitor profitableLeg = null;
                LegMonitor lossMakingLeg = null;
                double profitableLegPnl = Double.NEGATIVE_INFINITY;
                double lossMakingLegPnl = Double.POSITIVE_INFINITY;

                for (int i = 0; i < count; i++) {
                    final LegMonitor leg = legs[i];
                    // For SHORT: P&L = (current - entry) * directionMultiplier
                    // Since directionMultiplier = -1 for SHORT:
                    // P&L = (current - entry) * (-1) = entry - current
                    final double legPnl = (leg.getCurrentPrice() - leg.getEntryPrice()) * dirMult;

                    if (legPnl > profitableLegPnl) {
                        profitableLegPnl = legPnl;
                        profitableLeg = leg;
                    }
                    if (legPnl < lossMakingLegPnl) {
                        lossMakingLegPnl = legPnl;
                        lossMakingLeg = leg;
                    }
                }

                // Only proceed if we found distinct profitable and loss-making legs
                if (profitableLeg != null && lossMakingLeg != null && profitableLeg != lossMakingLeg) {
                    log.warn("PREMIUM_LEG_ADJUSTMENT for execution {}: combinedLTP={} reached halfThreshold={} " +
                                    "(slLevel={}, entryPremium={}) - Exiting profitable leg {} and adding replacement",
                            ctx.getExecutionId(), formatDouble(combinedLTP), formatDouble(halfThreshold),
                            formatDouble(slLevel), formatDouble(entryPremium), profitableLeg.getSymbol());

                    // Calculate target premium for the new leg (similar to loss-making leg's current price)
                    final double targetPremiumForNewLeg = lossMakingLeg.getCurrentPrice();
                    final String exitedLegType = profitableLeg.getType();
                    final String newLegType = exitedLegType; // Same type (CE or PE) as the exited leg

                    String exitReason = buildExitReasonLegAdjustment(
                            profitableLeg.getSymbol(), combinedLTP, halfThreshold, entryPremium);

                    return ExitResult.adjustLeg(exitReason, profitableLeg.getSymbol(),
                            newLegType, targetPremiumForNewLeg, lossMakingLeg.getSymbol());
                }
            }
        }

        return ExitResult.noExit();
    }

    // ==================== EXIT REASON BUILDERS ====================

    /**
     * Build exit reason for premium decay target hit.
     */
    private String buildExitReasonDecayTarget(double combinedLTP, double entry, double targetLevel) {
        StringBuilder sb = getExitReasonBuilder();
        sb.append(EXIT_PREFIX_DECAY_TARGET);
        appendDouble(sb, combinedLTP);
        sb.append(EXIT_SUFFIX_ENTRY);
        appendDouble(sb, entry);
        sb.append(EXIT_SUFFIX_TARGET_LEVEL);
        appendDouble(sb, targetLevel);
        sb.append(EXIT_SUFFIX_CLOSE);
        return sb.toString();
    }

    /**
     * Build exit reason for premium expansion stop loss hit.
     */
    private String buildExitReasonExpansionSL(double combinedLTP, double entry, double slLevel) {
        StringBuilder sb = getExitReasonBuilder();
        sb.append(EXIT_PREFIX_EXPANSION_SL);
        appendDouble(sb, combinedLTP);
        sb.append(EXIT_SUFFIX_ENTRY);
        appendDouble(sb, entry);
        sb.append(EXIT_SUFFIX_SL_LEVEL);
        appendDouble(sb, slLevel);
        sb.append(EXIT_SUFFIX_CLOSE);
        return sb.toString();
    }

    /**
     * Build exit reason for premium-based leg adjustment.
     */
    private String buildExitReasonLegAdjustment(String profitableLegSymbol, double combinedLTP,
                                                 double halfThreshold, double entry) {
        StringBuilder sb = getExitReasonBuilder();
        sb.append(EXIT_PREFIX_LEG_ADJUSTMENT);
        sb.append(profitableLegSymbol);
        sb.append(EXIT_SUFFIX_COMBINED_LTP);
        appendDouble(sb, combinedLTP);
        sb.append(EXIT_SUFFIX_HALF_THRESHOLD);
        appendDouble(sb, halfThreshold);
        sb.append(EXIT_SUFFIX_ENTRY);
        appendDouble(sb, entry);
        sb.append(EXIT_SUFFIX_CLOSE);
        return sb.toString();
    }
}




