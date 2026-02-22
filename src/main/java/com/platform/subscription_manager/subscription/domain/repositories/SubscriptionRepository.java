package com.platform.subscription_manager.subscription.domain.repositories;

import com.platform.subscription_manager.shared.domain.SubscriptionStatus;
import com.platform.subscription_manager.subscription.domain.entity.Subscription;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

	boolean existsByUserIdAndStatus(UUID userId, SubscriptionStatus status);

	Optional<Subscription> findByIdAndUserId(UUID id, UUID userId);

	// Batch collection of renewals
	@Query("""
        SELECT s FROM Subscription s 
        WHERE s.status = :status 
          AND s.autoRenew = true 
          AND s.expiringDate <= :now 
          AND s.billingAttempts < :maxAttempts 
          AND s.nextRetryAt <= :now
        """)
	Slice<Subscription> findEligibleForRenewal(
		@Param("status") SubscriptionStatus status,
		@Param("now") LocalDateTime now,
		@Param("maxAttempts") int maxAttempts,
		Pageable pageable
	);
	// Revoke canceled subscriptions
	@Transactional
	@Modifying(clearAutomatically = true) // offloading memory upon update
	@Query("""
        UPDATE Subscription s 
        SET s.status = 'CANCELED' 
        WHERE s.status in ('ACTIVE')
        AND s.autoRenew = false 
        AND s.expiringDate <= :now
    """)
	int expireCanceledSubscriptions(@Param("now") LocalDateTime now);

	/**
	 * RENOVAÇÃO ATÔMICA (O xeque-mate na duplicação)
	 * Este método garante que a assinatura só avance se ela ainda estiver no ciclo
	 * que originou a cobrança.
	 * * @return 1 se renovou com sucesso, 0 se a assinatura já foi renovada por outra thread.
	 */
	@Modifying(clearAutomatically = true)
	@Query("""
        UPDATE Subscription s 
        SET s.expiringDate = :nextDate, 
            s.status = 'ACTIVE', 
            s.billingAttempts = 0, 
            s.lastBillingAttempt = null,
            s.nextRetryAt = :nextDate
        WHERE s.id = :id 
          AND s.expiringDate = :currentExpiringDate
    """)
	int renewSubscriptionAtomic(
		@Param("id") UUID id,
		@Param("currentExpiringDate") LocalDateTime currentExpiringDate,
		@Param("nextDate") LocalDateTime nextDate
	);

	/**
	 * REGISTRO DE FALHA ATÔMICO
	 * Evita que retries atrasados incrementem o contador de forma errada.
	 */
	@Modifying(clearAutomatically = true)
	@Query("""
        UPDATE Subscription s 
        SET s.billingAttempts = s.billingAttempts + 1,
            s.lastBillingAttempt = :now
        WHERE s.id = :id 
          AND s.expiringDate = :currentExpiringDate
          AND s.status = 'ACTIVE'
    """)
	int incrementFailureAtomic(
		@Param("id") UUID id,
		@Param("currentExpiringDate") LocalDateTime currentExpiringDate,
		@Param("now") LocalDateTime now
	);
}