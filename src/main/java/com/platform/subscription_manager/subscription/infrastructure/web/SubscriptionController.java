package com.platform.subscription_manager.subscription.infrastructure.web;

import com.platform.subscription_manager.subscription.application.dtos.CreateSubscriptionDTO;
import com.platform.subscription_manager.subscription.application.dtos.SubscriptionResponseDTO;
import com.platform.subscription_manager.subscription.domain.BillingCyclePolicy;
import com.platform.subscription_manager.subscription.domain.enums.SubscriptionStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/v1/subscriptions")
public class SubscriptionController {

	@PostMapping
	public ResponseEntity<SubscriptionResponseDTO> create(@RequestBody CreateSubscriptionDTO subscriptionPayload) {
		LocalDateTime now = LocalDateTime.now();
		// TODO: check if customer exists 404
		// TODO: check if there are any ACTIVE subscriptions attached to the user (user may only have one) 422
		// TODO: store paymentToken on Subscription table
		return ResponseEntity.status(201).body(new SubscriptionResponseDTO(
			UUID.randomUUID(),
			SubscriptionStatus.ACTIVE,
			subscriptionPayload.plan(),
			now,
			BillingCyclePolicy.calculateNextExpiration(now),
			true
		));
	}

	@PatchMapping("/{subscriptionId}/cancel")
	public ResponseEntity<Void> cancel(
		@PathVariable("subscriptionId") UUID subscriptionId,
		@RequestHeader("X-User-Id") UUID userId) {
		// TODO: check if customer exists 422
		// TODO: check if subscription exists and is attached to userId -> 422 / 403
		// TODO: Update autoRenew to false and status to CANCELED
		return ResponseEntity.noContent().build();
	}

}
