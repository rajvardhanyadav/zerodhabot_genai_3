package com.tradingbot.repository;

import com.tradingbot.entity.OrderLegEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for Order Leg entity operations.
 */
@Repository
public interface OrderLegRepository extends JpaRepository<OrderLegEntity, Long> {

    // Find by order ID
    Optional<OrderLegEntity> findByOrderId(String orderId);

    // Find by exit order ID
    Optional<OrderLegEntity> findByExitOrderId(String exitOrderId);

    // Find by strategy execution
    List<OrderLegEntity> findByStrategyExecutionIdOrderByCreatedAt(Long strategyExecutionId);

    // Find open legs (not yet exited)
    List<OrderLegEntity> findByLifecycleState(String lifecycleState);
}

