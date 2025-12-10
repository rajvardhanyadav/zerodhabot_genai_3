package com.tradingbot.repository;

import com.tradingbot.entity.DeltaSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository for Delta Snapshot entity operations.
 */
@Repository
public interface DeltaSnapshotRepository extends JpaRepository<DeltaSnapshotEntity, Long> {

    // Find by execution ID
    List<DeltaSnapshotEntity> findByExecutionIdOrderBySnapshotTimestamp(String executionId);

    // Find by instrument type and date range
    List<DeltaSnapshotEntity> findByInstrumentTypeAndSnapshotTimestampBetweenOrderBySnapshotTimestamp(
            String instrumentType, LocalDateTime startTime, LocalDateTime endTime);

    // Find by trading symbol and date range
    List<DeltaSnapshotEntity> findByTradingSymbolAndSnapshotTimestampBetweenOrderBySnapshotTimestamp(
            String tradingSymbol, LocalDateTime startTime, LocalDateTime endTime);

    // Find by snapshot type
    List<DeltaSnapshotEntity> findBySnapshotTypeOrderBySnapshotTimestampDesc(String snapshotType);

    // Find latest snapshot for an instrument
    @Query("SELECT d FROM DeltaSnapshotEntity d WHERE d.instrumentType = :instrumentType " +
           "ORDER BY d.snapshotTimestamp DESC LIMIT 1")
    DeltaSnapshotEntity findLatestByInstrumentType(@Param("instrumentType") String instrumentType);

    // Find snapshots for ATM analysis
    @Query("SELECT d FROM DeltaSnapshotEntity d WHERE d.instrumentType = :instrumentType " +
           "AND d.snapshotTimestamp BETWEEN :startTime AND :endTime " +
           "AND ABS(d.delta) BETWEEN 0.45 AND 0.55 " +
           "ORDER BY d.snapshotTimestamp")
    List<DeltaSnapshotEntity> findAtmSnapshotsForDateRange(
            @Param("instrumentType") String instrumentType,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    // Delete old snapshots
    void deleteBySnapshotTimestampBefore(LocalDateTime cutoffTime);
}

