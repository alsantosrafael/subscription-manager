package com.platform.subscription_manager.subscription.infrastructure.web;

import com.platform.subscription_manager.shared.utils.DateHelper;
import com.platform.subscription_manager.subscription.domain.CancelSubscriptionDTO;
import com.platform.subscription_manager.subscription.domain.CreateSubscriptionDTO;
import com.platform.subscription_manager.subscription.domain.SubscriptionResponseDTO;
import com.platform.subscription_manager.subscription.domain.enums.SubscriptionStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
			now.toLocalDate(),
			DateHelper.nextExpiringDate(now),
			true
		));
	}

	@PatchMapping("/{subscriptionId}")
	public ResponseEntity<String> cancel(@PathVariable("subscriptionId") UUID subscriptionId,
										 @RequestBody CancelSubscriptionDTO subscriptionPayload) {
		// TODO: check if customer exists 422
		// TODO: check if subscription exists and is attached to userId -> 422 / 403
		// TODO: Update autoRenew to false and status to CANCELED
		return ResponseEntity.ok("Your subscription has been cancelled");
	}

}
