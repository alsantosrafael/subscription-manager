package com.platform.subscription_manager.subscription.application.dtos;

import com.platform.subscription_manager.shared.domain.Plan;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateSubscriptionDTO(
	@NotNull UUID userId,
	@NotNull Plan plan,
	@NotBlank String paymentToken
) {
}
