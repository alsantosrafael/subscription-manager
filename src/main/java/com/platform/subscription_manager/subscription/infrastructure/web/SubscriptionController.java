package com.platform.subscription_manager.subscription.infrastructure.web;

import com.platform.subscription_manager.subscription.application.dtos.CreateSubscriptionDTO;
import com.platform.subscription_manager.subscription.application.dtos.SubscriptionResponseDTO;
import com.platform.subscription_manager.subscription.application.services.SubscriptionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/subscriptions")
@RequiredArgsConstructor
public class SubscriptionController {
	private final SubscriptionService subscriptionService;

	@PostMapping
	public ResponseEntity<SubscriptionResponseDTO> create(@Valid @RequestBody CreateSubscriptionDTO subscriptionPayload) {
		return ResponseEntity.status(201).body(subscriptionService.create(subscriptionPayload));
	}

	@GetMapping("/{subscriptionId}")
	public ResponseEntity<SubscriptionResponseDTO> get(@PathVariable UUID subscriptionId) {
		return subscriptionService.get(subscriptionId)
			.map(ResponseEntity::ok)
			.orElse(ResponseEntity.notFound().build());
	}

	@PatchMapping("/{subscriptionId}/cancel")
	public ResponseEntity<Void> cancel(
		@PathVariable("subscriptionId") UUID subscriptionId,
		@RequestHeader("X-User-Id") UUID userId) {
		subscriptionService.cancel(subscriptionId, userId);
		return ResponseEntity.noContent().build();
	}
}
