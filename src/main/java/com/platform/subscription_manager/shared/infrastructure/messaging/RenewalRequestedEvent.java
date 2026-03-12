package com.platform.subscription_manager.shared.infrastructure.messaging;

import com.platform.subscription_manager.shared.domain.Plan;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Published by {@link com.platform.subscription_manager.subscription.application.services.RenewalOrchestratorService}
 * when a subscription is eligible for a billing charge.
 *
 * @param attemptNumber Zero-based index of this billing attempt — equals {@code billingAttempts}
 *                      on the subscription row at dispatch time (before the atomic increment).
 *                      Included in the idempotency key so each retry gets its own slot in
 *                      {@code billing_history}, while Kafka re-delivery of the same attempt
 *                      is still blocked.
 */
public record RenewalRequestedEvent(
	UUID subscriptionId,
	Plan plan,
	LocalDateTime expiringDate,
	int attemptNumber
) {}