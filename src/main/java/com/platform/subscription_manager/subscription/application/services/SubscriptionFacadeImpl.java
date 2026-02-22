package com.platform.subscription_manager.subscription.application.services;

import com.platform.subscription_manager.shared.domain.PaymentTokenPort;
import com.platform.subscription_manager.subscription.domain.repositories.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SubscriptionFacadeImpl implements PaymentTokenPort {

	private final SubscriptionRepository subscriptionRepository;

	@Override
	public Optional<String> getPaymentToken(UUID subscriptionId) {
		return subscriptionRepository.findById(subscriptionId)
			.map(sub -> sub.getPaymentToken());
	}
}
