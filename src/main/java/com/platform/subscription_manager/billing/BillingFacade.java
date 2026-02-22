package com.platform.subscription_manager.billing;

import com.platform.subscription_manager.shared.domain.Plan;

import java.util.UUID;

/**
 * Public API of the billing module.
 * Other modules must only depend on this interface — never on billing internals.
 */
public interface BillingFacade {

	/**
	 * Synchronously charges the user for a brand-new subscription.
	 * Persists a BillingHistory record and returns whether the charge succeeded.
	 *
	 * @throws RuntimeException if the payment gateway is unavailable (circuit open / network error).
	 *                          The caller's transaction will roll back, so no subscription is saved.
	 */
	ChargeResult chargeForNewSubscription(UUID subscriptionId, Plan plan, String paymentToken);

	record ChargeResult(boolean success, String gatewayTransactionId, String errorMessage) {}
}

