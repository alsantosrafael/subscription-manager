package com.platform.subscription_manager.shared.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * Cross-module port that provides the decrypted payment token for a subscription.
 *
 * <p>Lives in {@code shared} so the {@code billing} module can depend on it without
 * creating a circular dependency back into {@code subscription}.</p>
 *
 * <p>The payment token is intentionally kept out of Kafka messages to avoid transmitting
 * sensitive data over an unencrypted bus. Instead, the {@code billing} worker fetches the
 * token at charge time through this port — mirroring exactly what a service-to-service
 * REST call would look like if these modules were split into microservices.</p>
 *
 * <p>Implemented by {@code SubscriptionFacadeImpl} in the {@code subscription} module.</p>
 */
public interface PaymentTokenPort {
	Optional<String> getPaymentToken(UUID subscriptionId);
}

