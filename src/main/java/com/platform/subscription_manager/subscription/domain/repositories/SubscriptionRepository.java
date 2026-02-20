package com.platform.subscription_manager.subscription.domain.repositories;

import com.platform.subscription_manager.subscription.domain.entity.Subscription;
import com.platform.subscription_manager.subscription.domain.enums.SubscriptionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

	boolean existsByUserIdAndStatus(UUID userId, SubscriptionStatus status);

	Optional<Subscription> findByIdAndUserId(UUID id, UUID userId);
}