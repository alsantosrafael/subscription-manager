package com.platform.subscription_manager.billing.domain.repositories;

import com.platform.subscription_manager.billing.domain.entity.BillingHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface BillingHistoryRepository extends JpaRepository<BillingHistory, UUID> {
	boolean existsByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);
	Optional<BillingHistory> findByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);
}