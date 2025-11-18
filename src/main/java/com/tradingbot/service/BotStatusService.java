package com.tradingbot.service;

import com.tradingbot.dto.BotStatusResponse;
import com.tradingbot.model.BotStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

@Service
@Slf4j
public class BotStatusService {

    private final AtomicReference<BotStatus> state = new AtomicReference<>(BotStatus.STOPPED);
    private volatile Instant lastUpdated = Instant.now();

    public void markRunning() {
        state.set(BotStatus.RUNNING);
        lastUpdated = Instant.now();
        log.info("Bot status updated to RUNNING at {}", lastUpdated);
    }

    public void markStopped() {
        state.set(BotStatus.STOPPED);
        lastUpdated = Instant.now();
        log.info("Bot status updated to STOPPED at {}", lastUpdated);
    }

    public BotStatusResponse getStatus() {
        return new BotStatusResponse(state.get().name(), lastUpdated);
    }
}

