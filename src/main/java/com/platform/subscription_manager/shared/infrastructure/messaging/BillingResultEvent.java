package com.platform.subscription_manager.shared.infrastructure.messaging;

import com.platform.subscription_manager.shared.domain.BillingHistoryStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record BillingResultEvent(
    UUID subscriptionId,
    String gatewayTransactionId,
	BillingHistoryStatus status,
	LocalDateTime referenceExpiringDate,
    String errorMessage
) {}