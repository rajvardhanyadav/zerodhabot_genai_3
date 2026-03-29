package com.tradingbot.controller;

import com.tradingbot.dto.ApiResponse;
import com.tradingbot.entity.NeutralMarketLogEntity;
import com.tradingbot.config.NeutralMarketV3Config;
import com.tradingbot.config.PersistenceConfig;
import com.tradingbot.service.persistence.NeutralMarketLogService;
import com.tradingbot.service.session.UserSessionManager;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * REST controller for querying persisted V3 neutral market detection logs.
 *
 * <p>Provides date-based analysis endpoints for retrospective evaluation
 * of the 3-layer detection engine's signal patterns, accuracy, and performance.</p>
 *
 * @since 4.3
 */
@RestController
@RequestMapping("/api/market-analysis")
@Slf4j
@Tag(name = "Market Analysis", description = "Historical neutral market detection logs and analysis")
public class NeutralMarketLogController {

    private final NeutralMarketLogService neutralMarketLogService;
    private final UserSessionManager userSessionManager;
    private final NeutralMarketV3Config neutralMarketV3Config;
    private final PersistenceConfig persistenceConfig;

    public NeutralMarketLogController(NeutralMarketLogService neutralMarketLogService,
                                      UserSessionManager userSessionManager,
                                      NeutralMarketV3Config neutralMarketV3Config,
                                      PersistenceConfig persistenceConfig) {
        this.neutralMarketLogService = neutralMarketLogService;
        this.userSessionManager = userSessionManager;
        this.neutralMarketV3Config = neutralMarketV3Config;
        this.persistenceConfig = persistenceConfig;
    }

    // ==================== LOG RETRIEVAL ====================

    @GetMapping("/neutral-market-logs")
    @Operation(
            summary = "Get neutral market detection logs",
            description = "Retrieve all V3 neutral market evaluation logs for a specific date or date range. " +
                    "Each log contains the complete 3-layer detection result: regime signals, microstructure signals, " +
                    "breakout risk, scores, confidence, and the final tradable decision. " +
                    "Use 'date' for a single day, or 'from'/'to' for a range."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Logs retrieved successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid date parameters")
    })
    public ResponseEntity<ApiResponse<List<NeutralMarketLogEntity>>> getLogs(
            @Parameter(description = "Single date to query (ISO format: yyyy-MM-dd)", example = "2026-03-29")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,

            @Parameter(description = "Start date for range query (inclusive)", example = "2026-03-25")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,

            @Parameter(description = "End date for range query (inclusive)", example = "2026-03-29")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,

            @Parameter(description = "Filter by instrument (e.g., NIFTY)", example = "NIFTY")
            @RequestParam(required = false) String instrument) {

        List<NeutralMarketLogEntity> logs;

        if (date != null) {
            // Single date query
            if (instrument != null && !instrument.isBlank()) {
                logs = neutralMarketLogService.getLogsByInstrumentAndDate(instrument, date);
            } else {
                logs = neutralMarketLogService.getLogsByDate(date);
            }
            log.debug("Returning {} neutral market logs for date={}, instrument={}",
                    logs.size(), date, instrument);
        } else if (from != null && to != null) {
            // Date range query
            if (from.isAfter(to)) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("'from' date must be before or equal to 'to' date"));
            }
            logs = neutralMarketLogService.getLogsByDateRange(from, to);
            log.debug("Returning {} neutral market logs for range {} to {}", logs.size(), from, to);
        } else {
            // Default: today
            LocalDate today = LocalDate.now();
            logs = neutralMarketLogService.getLogsByDate(today);
            log.debug("Returning {} neutral market logs for today ({})", logs.size(), today);
        }

        return ResponseEntity.ok(ApiResponse.success(
                "Retrieved " + logs.size() + " neutral market evaluation logs", logs));
    }

    // ==================== SUMMARY / ANALYTICS ====================

    @GetMapping("/neutral-market-summary")
    @Operation(
            summary = "Get neutral market detection summary",
            description = "Returns aggregated statistics for a specific date: total evaluations, " +
                    "tradable/skipped counts and percentages, average scores, regime distribution, " +
                    "veto reason breakdown, and per-signal pass rates. " +
                    "Useful for understanding market behavior patterns on a given day."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Summary retrieved successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid date parameter")
    })
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSummary(
            @Parameter(description = "Date to summarize (ISO format: yyyy-MM-dd). Defaults to today.",
                    example = "2026-03-29")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        LocalDate targetDate = (date != null) ? date : LocalDate.now();
        Map<String, Object> summary = neutralMarketLogService.getSummaryByDate(targetDate);
        log.debug("Returning neutral market summary for date={}", targetDate);
        return ResponseEntity.ok(ApiResponse.success(
                "Neutral market summary for " + targetDate, summary));
    }

    // ==================== DIAGNOSTIC STATUS ====================

    @GetMapping("/neutral-market-status")
    @Operation(
            summary = "Check neutral market log persistence status",
            description = "Diagnostic endpoint that shows whether neutral market evaluations are being " +
                    "persisted to the database. Returns the current state of all prerequisites: " +
                    "V3 detector enabled, active Kite sessions, market hours, persistence enabled, " +
                    "and today's log count. Use this to diagnose why logs may be empty."
    )
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPersistenceStatus() {
        Map<String, Object> status = new java.util.LinkedHashMap<>();

        // Check all prerequisites
        boolean v3Enabled = neutralMarketV3Config.isEnabled();
        int activeSessionCount = userSessionManager.getActiveSessionCount();
        boolean persistenceEnabled = persistenceConfig.isEnabled();

        java.time.ZonedDateTime nowIst = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        java.time.LocalTime timeIst = nowIst.toLocalTime();
        boolean isWeekday = nowIst.getDayOfWeek() != java.time.DayOfWeek.SATURDAY
                && nowIst.getDayOfWeek() != java.time.DayOfWeek.SUNDAY;
        boolean isMarketHours = isWeekday
                && !timeIst.isBefore(java.time.LocalTime.of(9, 15))
                && !timeIst.isAfter(java.time.LocalTime.of(15, 10));

        boolean allPrerequisitesMet = v3Enabled && activeSessionCount > 0 && isMarketHours && persistenceEnabled;

        status.put("allPrerequisitesMet", allPrerequisitesMet);
        status.put("v3DetectorEnabled", v3Enabled);
        status.put("activeKiteSessions", activeSessionCount);
        status.put("isWithinMarketHours", isMarketHours);
        status.put("currentTimeIST", timeIst.toString());
        status.put("dayOfWeek", nowIst.getDayOfWeek().toString());
        status.put("persistenceEnabled", persistenceEnabled);

        // Today's log count
        java.time.LocalDate today = nowIst.toLocalDate();
        long todayLogCount = 0;
        try {
            todayLogCount = neutralMarketLogService.getLogsByDate(today).size();
        } catch (Exception e) {
            log.warn("Failed to count today's neutral market logs: {}", e.getMessage());
        }
        status.put("todayLogCount", todayLogCount);
        status.put("todayDate", today.toString());

        // Diagnostic messages
        java.util.List<String> issues = new java.util.ArrayList<>();
        if (!v3Enabled) {
            issues.add("V3 neutral market detector is DISABLED (neutral-market-v3.enabled=false)");
        }
        if (activeSessionCount == 0) {
            issues.add("No active Kite sessions — log in via POST /auth/login to activate evaluations");
        }
        if (!isMarketHours) {
            issues.add("Outside market hours (IST 09:15-15:10, weekdays only). Current: " + timeIst + " " + nowIst.getDayOfWeek());
        }
        if (!persistenceEnabled) {
            issues.add("Persistence is DISABLED (persistence.enabled=false)");
        }
        status.put("issues", issues);

        String message = allPrerequisitesMet
                ? "All prerequisites met — neutral market logs are being persisted"
                : "Neutral market log persistence is INACTIVE — " + issues.size() + " issue(s) found";

        return ResponseEntity.ok(ApiResponse.success(message, status));
    }
}

