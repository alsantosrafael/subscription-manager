package com.platform.subscription_manager.billing.domain.repositories;

import com.platform.subscription_manager.billing.domain.entity.BillingHistory;
import com.platform.subscription_manager.shared.domain.BillingHistoryStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

public interface BillingHistoryRepository extends JpaRepository<BillingHistory, UUID> {
	boolean existsByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);
	Optional<BillingHistory> findByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

	@Transactional
	@Modifying(clearAutomatically = true)
	@Query(value = """
        INSERT INTO billing_history (id, subscription_id, idempotency_key, status, processed_at)
        VALUES (gen_random_uuid(), :subId, :key, 'PENDING', NOW())
        ON CONFLICT (idempotency_key) DO NOTHING
    """, nativeQuery = true)
	int insertIfNotExist(@Param("subId") UUID subId, @Param("key") String key);

	@Transactional
	@Modifying(clearAutomatically = true)
	@Query("""
        UPDATE BillingHistory b
        SET b.status = :status, b.gatewayTransactionId = :txId
        WHERE b.idempotencyKey = :key AND b.status = com.platform.subscription_manager.shared.domain.BillingHistoryStatus.PENDING
    """)
	void updateResult(@Param("key") String key, @Param("status") BillingHistoryStatus status, @Param("txId") String txId);
}