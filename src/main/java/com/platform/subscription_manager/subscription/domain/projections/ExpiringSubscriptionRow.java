package com.platform.subscription_manager.subscription.domain.projections;

import com.platform.subscription_manager.shared.domain.Plan;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Lightweight read-only projection of the fields needed to expire a CANCELED subscription.
 *
 * <p>Used by {@code SubscriptionRepository#findExpiringSubscriptionIds} to avoid loading
 * full {@code Subscription} entities (which carry encrypted {@code paymentToken},
 * {@code billingAttempts}, etc.) for a path that only needs to bulk-UPDATE status and
 * publish a cache-invalidation event.
 *
 * <p>Field order matches the {@code SELECT NEW} clause in the repository query — do not
 * reorder without updating the JPQL.
 */
public record ExpiringSubscriptionRow(
		UUID id,
		UUID userId,
		Plan plan,
		LocalDateTime startDate,
		LocalDateTime expiringDate
) {}

