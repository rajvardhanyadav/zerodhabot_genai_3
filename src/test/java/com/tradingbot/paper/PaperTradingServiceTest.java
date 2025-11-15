package com.tradingbot.paper;

import com.tradingbot.config.PaperTradingConfig;
import com.tradingbot.dto.OrderRequest;
import com.tradingbot.service.TradingService;
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

    @BeforeEach
    void setup() {
        PaperTradingConfig config = new PaperTradingConfig();
        // Configure defaults suitable for tests
        config.setInitialBalance(1_000_000.0);
        config.setApplyBrokerageCharges(false);
        config.setEnableExecutionDelay(false);
        config.setEnableOrderRejection(false);

        tradingService = Mockito.mock(TradingService.class);
        paperTradingService = new PaperTradingService(config, tradingService);
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
}
