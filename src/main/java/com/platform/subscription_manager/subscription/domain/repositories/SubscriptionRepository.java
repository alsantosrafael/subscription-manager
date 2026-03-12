package com.platform.subscription_manager.subscription.domain.repositories;

import com.platform.subscription_manager.shared.domain.SubscriptionStatus;
import com.platform.subscription_manager.subscription.domain.entity.Subscription;
import com.platform.subscription_manager.subscription.domain.projections.ExpiringSubscriptionRow;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

	Optional<Subscription> findByUserId(UUID userId);

	Optional<Subscription> findByIdAndUserId(UUID id, UUID userId);

	@Query("""
		SELECT s.id FROM Subscription s
		WHERE s.status = :status
		  AND s.autoRenew = true
		  AND s.expiringDate <= :now
		  AND s.billingAttempts < :maxAttempts
		  AND (s.nextRetryAt IS NULL OR s.nextRetryAt <= :now)
		ORDER BY s.nextRetryAt ASC, s.id ASC
		""")
	Slice<UUID> findEligibleForRenewal(
		@Param("status") SubscriptionStatus status,
		@Param("now") LocalDateTime now,
		@Param("maxAttempts") int maxAttempts,
		Pageable pageable
	);

	/**
	 * Returns one page of {@link ExpiringSubscriptionRow} for CANCELED subscriptions
	 * whose access period has ended. Always call with {@code PageRequest.of(0, N)}: after the
	 * caller moves the returned rows to INACTIVE they leave this result set, so the next call
	 * to page 0 naturally surfaces the following batch without skipping any rows.
	 */
	@Query("""
        SELECT NEW com.platform.subscription_manager.subscription.domain.projections.ExpiringSubscriptionRow(
            s.id, s.userId, s.plan, s.startDate, s.expiringDate)
        FROM Subscription s
        WHERE s.status = 'CANCELED'
          AND s.autoRenew = false
          AND s.expiringDate <= :now
        ORDER BY s.expiringDate ASC, s.id ASC
    """)
	Slice<ExpiringSubscriptionRow> findExpiringSubscriptionIds(@Param("now") LocalDateTime now, Pageable pageable);

	/**
	 * Moves a specific batch of IDs from CANCELED → INACTIVE.
	 * The {@code AND s.status = 'CANCELED'} guard makes this idempotent: a row that was
	 * already moved by a previous iteration is silently skipped.
	 */
	@Transactional
	@Modifying(clearAutomatically = true)
	@Query("""
        UPDATE Subscription s
        SET s.status = 'INACTIVE'
        WHERE s.id IN :ids
          AND s.status = 'CANCELED'
    """)
	int expireCanceledSubscriptionsByIds(@Param("ids") List<UUID> ids);

	@Transactional
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
          AND s.status = 'ACTIVE'
    """)
	int renewSubscriptionAtomic(
		@Param("id") UUID id,
		@Param("currentExpiringDate") LocalDateTime currentExpiringDate,
		@Param("nextDate") LocalDateTime nextDate
	);

	/**
	 * Atomically increments the failure counter and stamps nextRetryAt for the retry backoff.
	 * Uses no @Version field — safe to call from a large Kafka batch without
	 * OptimisticLockException blowing up the entire batch.
	 * The WHERE guard (expiringDate = :currentExpiringDate AND status = 'ACTIVE') ensures
	 * late or duplicate result events are silently ignored.
	 */
	@Transactional
	@Modifying(clearAutomatically = true)
	@Query("""
        UPDATE Subscription s
        SET s.billingAttempts = s.billingAttempts + 1,
            s.lastBillingAttempt = :now,
            s.nextRetryAt = :nextRetryAt
        WHERE s.id = :id
          AND s.expiringDate = :currentExpiringDate
          AND s.status = 'ACTIVE'
          AND s.billingAttempts = :expectedAttempts
    """)
	int incrementFailureAtomic(
		@Param("id") UUID id,
		@Param("currentExpiringDate") LocalDateTime currentExpiringDate,
		@Param("now") LocalDateTime now,
		@Param("nextRetryAt") LocalDateTime nextRetryAt,
		@Param("expectedAttempts") int expectedAttempts
	);

	/**
	 * Atomically suspends a subscription once the billing failure threshold is reached.
	 * Sets status = SUSPENDED, autoRenew = false, nextRetryAt = null.
	 * The WHERE guard ensures this is idempotent and only fires once.
	 */
	@Transactional
	@Modifying(clearAutomatically = true)
	@Query("""
        UPDATE Subscription s
        SET s.status = 'SUSPENDED',
            s.autoRenew = false,
            s.nextRetryAt = null,
            s.lastBillingAttempt = :now,
            s.billingAttempts = s.billingAttempts + 1
        WHERE s.id = :id
          AND s.expiringDate = :currentExpiringDate
          AND s.status = 'ACTIVE'
          AND s.billingAttempts = :expectedAttempts
    """)
	int suspendSubscriptionAtomic(
		@Param("id") UUID id,
		@Param("currentExpiringDate") LocalDateTime currentExpiringDate,
		@Param("now") LocalDateTime now,
		@Param("expectedAttempts") int expectedAttempts
	);
}