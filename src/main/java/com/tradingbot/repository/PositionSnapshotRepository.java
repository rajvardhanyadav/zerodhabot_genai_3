package com.tradingbot.repository;

import com.tradingbot.entity.PositionSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * Repository for Position Snapshot entity operations.
 */
@Repository
public interface PositionSnapshotRepository extends JpaRepository<PositionSnapshotEntity, Long> {

    // Find by user and date
    List<PositionSnapshotEntity> findByUserIdAndSnapshotDateOrderByTradingSymbol(
            String userId, LocalDate snapshotDate);

    // Find by user and date range
    List<PositionSnapshotEntity> findByUserIdAndSnapshotDateBetweenOrderBySnapshotDateDescTradingSymbol(
            String userId, LocalDate startDate, LocalDate endDate);

    // Find by symbol and date range
    List<PositionSnapshotEntity> findByUserIdAndTradingSymbolAndSnapshotDateBetweenOrderBySnapshotDate(
            String userId, String tradingSymbol, LocalDate startDate, LocalDate endDate);

    // Find by trading mode
    List<PositionSnapshotEntity> findByUserIdAndTradingModeAndSnapshotDateOrderByTradingSymbol(
            String userId, String tradingMode, LocalDate snapshotDate);

    // Delete old snapshots
    void deleteBySnapshotDateBefore(LocalDate cutoffDate);
}

