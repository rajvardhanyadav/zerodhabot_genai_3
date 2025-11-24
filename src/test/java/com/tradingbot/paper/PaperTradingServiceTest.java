package com.tradingbot.paper;

import com.tradingbot.config.PaperTradingConfig;
import com.tradingbot.dto.OrderRequest;
import com.tradingbot.service.TradingService;
import com.tradingbot.paper.ZerodhaChargeCalculator;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.LTPQuote;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;

public class PaperTradingServiceTest {

    private PaperTradingService paperTradingService;
    private TradingService tradingService;
    private ZerodhaChargeCalculator chargeCalculator;

    @BeforeEach
    void setup() {
        PaperTradingConfig config = new PaperTradingConfig();
        // Configure defaults suitable for tests
        config.setInitialBalance(1_000_000.0);
        config.setApplyBrokerageCharges(false);
        config.setEnableExecutionDelay(false);
        config.setEnableOrderRejection(false);

        tradingService = Mockito.mock(TradingService.class);
        chargeCalculator = Mockito.mock(ZerodhaChargeCalculator.class);
        Mockito.when(chargeCalculator.calculateCharges(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(com.tradingbot.paper.entity.OrderCharges.builder()
                        .brokerage(java.math.BigDecimal.ZERO)
                        .stt(java.math.BigDecimal.ZERO)
                        .exchangeTxnCharge(java.math.BigDecimal.ZERO)
                        .gst(java.math.BigDecimal.ZERO)
                        .sebiCharge(java.math.BigDecimal.ZERO)
                        .stampDuty(java.math.BigDecimal.ZERO)
                        .totalCharges(java.math.BigDecimal.ZERO)
                        .build());
        paperTradingService = new PaperTradingService(config, tradingService, chargeCalculator);
    }

    private void stubLtp(double price) {
        try {
            Map<String, LTPQuote> ltpMap = new HashMap<>();
            LTPQuote q = new LTPQuote();
            q.lastPrice = price;
            // The key is constructed as exchange:tradingSymbol in PaperTradingService
            ltpMap.put("NFO:TESTSYM", q);
            Mockito.when(tradingService.getLTP(any(String[].class))).thenReturn(ltpMap);
        } catch (KiteException | IOException e) {
            fail("Stub LTP should not throw: " + e.getMessage());
        }
    }

    private OrderRequest marketBuy(String symbol, int qty) {
        return OrderRequest.builder()
                .tradingSymbol(symbol)
                .exchange("NFO")
                .transactionType("BUY")
                .quantity(qty)
                .product("MIS")
                .orderType("MARKET")
                .validity("DAY")
                .build();
    }

    private OrderRequest marketSell(String symbol, int qty) {
        return OrderRequest.builder()
                .tradingSymbol(symbol)
                .exchange("NFO")
                .transactionType("SELL")
                .quantity(qty)
                .product("MIS")
                .orderType("MARKET")
                .validity("DAY")
                .build();
    }

    @Test
    void positionsArePerUserAndResetIsScoped() throws Exception {
        stubLtp(100.0);

        // Place orders for two users
        paperTradingService.placeOrder(marketBuy("TESTSYM", 50), "U1");
        paperTradingService.placeOrder(marketBuy("TESTSYM", 30), "U2");

        List<PaperPosition> u1Positions = paperTradingService.getPositions("U1");
        List<PaperPosition> u2Positions = paperTradingService.getPositions("U2");

        assertEquals(1, u1Positions.size(), "U1 should have one position");
        assertEquals(1, u2Positions.size(), "U2 should have one position");
        assertEquals(50, u1Positions.get(0).getQuantity());
        assertEquals(30, u2Positions.get(0).getQuantity());

        // Reset only U1
        paperTradingService.resetAccount("U1");
        List<PaperPosition> u1AfterReset = paperTradingService.getPositions("U1");
        List<PaperPosition> u2AfterReset = paperTradingService.getPositions("U2");

        assertTrue(u1AfterReset.isEmpty(), "U1 positions should be cleared after reset");
        assertEquals(1, u2AfterReset.size(), "U2 positions should remain intact after U1 reset");
    }

    @Test
    void realisedPnLIsCorrectWhenClosingShortPositionWithBuy() throws Exception {
        String userId = "U_SHORT";

        // 1) Open short: SELL at 110
        stubLtp(110.0);
        paperTradingService.placeOrder(marketSell("TESTSYM", 50), userId);

        // 2) Close short: BUY at 100
        stubLtp(100.0);
        paperTradingService.placeOrder(marketBuy("TESTSYM", 50), userId);

        List<PaperPosition> positions = paperTradingService.getPositions(userId);
        assertEquals(1, positions.size(), "User should have one position record");

        PaperPosition pos = positions.get(0);

        // Net quantity should be zero after fully closing the short
        assertEquals(0, pos.getQuantity());

        // Realised PnL for short: (entrySellPrice - exitBuyPrice) * qty = (110 - 100) * 50 = 500
        assertEquals(500.0, pos.getRealised(), 1e-6, "Realised PnL for closed short should be positive 500");
        assertEquals(500.0, pos.getPnl(), 1e-6, "Total PnL should match realised PnL for closed short");
    }
}
