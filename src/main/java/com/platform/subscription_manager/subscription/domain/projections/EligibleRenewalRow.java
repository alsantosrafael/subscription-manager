package com.platform.subscription_manager.subscription.domain.projections;

import com.platform.subscription_manager.shared.domain.Plan;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Lightweight read-only projection of the fields needed to dispatch a renewal event.
 *
 * <p>Used by {@code SubscriptionRepository#findEligibleForRenewal} to avoid loading full
 * {@code Subscription} entities (which carry encrypted {@code paymentToken}, etc.) for
 * a path that only needs to claim the row atomically and publish a Kafka event.
 *
 * <p>Field order matches the {@code SELECT NEW} clause — do not reorder without
 * updating the JPQL query.
 */
public record EligibleRenewalRow(
		UUID id,
		UUID userId,
		Plan plan,
		LocalDateTime expiringDate,
		int billingAttempts
) {}

