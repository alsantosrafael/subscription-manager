package com.platform.subscription_manager.subscription.domain.repositories;

import com.platform.subscription_manager.subscription.domain.entity.Subscription;
import com.platform.subscription_manager.subscription.domain.enums.SubscriptionStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
          AND (s.lastBillingAttempt IS NULL OR s.lastBillingAttempt <= :threshold)
        """)
	Slice<Subscription> findEligibleForRenewal(
		@Param("status") SubscriptionStatus status,
		@Param("now") LocalDateTime now,
		@Param("threshold") LocalDateTime threshold,
		@Param("maxAttempts") int maxAttempts,
		Pageable pageable
	);
	// Revoke canceled subscriptions
	@Modifying(clearAutomatically = true) // offloading memory upon update
	@Query("""
        UPDATE Subscription s 
        SET s.status = 'EXPIRED' 
        WHERE s.status = 'ACTIVE' 
          AND s.autoRenew = false 
          AND s.expiringDate <= :now
    """)
	int expireCanceledSubscriptions(@Param("now") LocalDateTime now);
}