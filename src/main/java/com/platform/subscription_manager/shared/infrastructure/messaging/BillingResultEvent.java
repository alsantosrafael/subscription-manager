package com.platform.subscription_manager.shared.infrastructure.messaging;

import com.platform.subscription_manager.billing.domain.enums.BillingHistoryStatus;
import org.springframework.modulith.events.Externalized;

import java.util.UUID;

@Externalized("subscription.billing-results")
public record BillingResultEvent(
    UUID subscriptionId,
    String gatewayTransactionId,
	BillingHistoryStatus status,
    String errorMessage
) {}