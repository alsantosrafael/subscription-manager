package com.platform.subscription_manager.subscription.application.services;

import com.platform.subscription_manager.shared.config.ResourceNotFoundException;
import com.platform.subscription_manager.shared.config.UnprocessableEntityException;
import com.platform.subscription_manager.subscription.application.dtos.CreateSubscriptionDTO;
import com.platform.subscription_manager.subscription.application.dtos.SubscriptionResponseDTO;
import com.platform.subscription_manager.subscription.domain.entity.Subscription;
import com.platform.subscription_manager.subscription.domain.enums.SubscriptionStatus;
import com.platform.subscription_manager.subscription.domain.repositories.SubscriptionRepository;
import com.platform.subscription_manager.user.UserFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SubscriptionService {

	private final SubscriptionRepository subscriptionRepository;
	private final UserFacade userFacade; // Modulith permite isso pois UserService é público

	@Transactional
	public SubscriptionResponseDTO create(CreateSubscriptionDTO payload) {

		// 1. Valida se usuário existe (Cross-module call) -> 404
		if (!userFacade.exists(payload.userId())) {
			throw new ResourceNotFoundException("User not found");
		}

		if (subscriptionRepository.existsByUserIdAndStatus(payload.userId(), SubscriptionStatus.ACTIVE)) {
			throw new UnprocessableEntityException("User already has an active subscription");
		}

		// 3. Aplica a Factory com a Policy de data que fizemos
		Subscription sub = Subscription.create(payload.userId(), payload.plan(), payload.paymentToken());
		Subscription savedSub = subscriptionRepository.save(sub);

		return new SubscriptionResponseDTO(
			savedSub.getId(),
			savedSub.getStatus(),
			savedSub.getPlan(),
			savedSub.getStartDate(),
			savedSub.getExpiringDate(),
			savedSub.isAutoRenew()
		);
	}

	@Transactional
	public void cancel(UUID subscriptionId, UUID userId) {
		Subscription subscription = subscriptionRepository.findByIdAndUserId(subscriptionId, userId)
			.orElseThrow(() -> new ResourceNotFoundException("Subscription not found or belongs to another user"));

		subscription.cancelRenewal();

		subscriptionRepository.save(subscription);
	}

	//TODO: Criar regra para erro de renovação / SUSPEND

}
