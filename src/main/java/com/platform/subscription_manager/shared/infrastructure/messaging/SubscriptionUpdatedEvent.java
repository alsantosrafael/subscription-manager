package com.platform.subscription_manager.shared.infrastructure.messaging;

import com.platform.subscription_manager.shared.domain.Plan;
import com.platform.subscription_manager.shared.domain.SubscriptionStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record SubscriptionUpdatedEvent(	UUID id,
										   UUID userId,
										   SubscriptionStatus status,
										   Plan plan,
										   LocalDateTime startDate,
										   LocalDateTime expiringDate,
										   boolean autoRenew) {}
