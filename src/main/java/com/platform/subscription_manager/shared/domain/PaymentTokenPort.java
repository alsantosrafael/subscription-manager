package com.platform.subscription_manager.shared.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * Port that provides the decrypted payment token for a subscription.
 * Lives in shared so both the billing and subscription modules can depend on it
 * without creating a circular dependency.
 *
 * Implemented by SubscriptionFacadeImpl in the subscription module.
 */
public interface PaymentTokenPort {
	Optional<String> getPaymentToken(UUID subscriptionId);
}

