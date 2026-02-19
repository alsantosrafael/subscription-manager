package com.platform.subscription_manager.subscription.domain;

import java.util.UUID;

public record CancelSubscriptionDTO(
	UUID userId
) {
}
