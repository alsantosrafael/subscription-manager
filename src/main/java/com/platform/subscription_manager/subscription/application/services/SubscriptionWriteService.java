package com.platform.subscription_manager.subscription.application.services;

import com.platform.subscription_manager.billing.BillingFacade;
import com.platform.subscription_manager.shared.domain.exceptions.ResourceNotFoundException;
import com.platform.subscription_manager.shared.domain.exceptions.UnprocessableEntityException;
import com.platform.subscription_manager.shared.infrastructure.messaging.SubscriptionUpdatedEvent;
import com.platform.subscription_manager.subscription.application.dtos.CreateSubscriptionDTO;
import com.platform.subscription_manager.subscription.domain.entity.Subscription;
import com.platform.subscription_manager.shared.domain.SubscriptionStatus;
import com.platform.subscription_manager.subscription.domain.repositories.SubscriptionRepository;
import com.platform.subscription_manager.user.UserFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Owns the transactional boundary for subscription creation.
 * Kept separate from SubscriptionService so that the post-commit cache publish
 * in SubscriptionService.create() is a true inter-bean call through the AOP proxy —
 * avoiding the Spring self-invocation problem where @Transactional would be ignored.
 *
 * The gateway HTTP call is intentionally placed between two short transactions so
 * no DB connection is held open during the external network call.
 */
@Service
public class SubscriptionWriteService {

	private static final Logger log = LoggerFactory.getLogger(SubscriptionWriteService.class);

	private final SubscriptionRepository subscriptionRepository;
	private final UserFacade userFacade;
	private final BillingFacade billingFacade;

	// Self-reference through the proxy so @Transactional on the helper methods is respected
	@Autowired @Lazy
	private SubscriptionWriteService self;

	public SubscriptionWriteService(SubscriptionRepository subscriptionRepository,
									UserFacade userFacade,
									BillingFacade billingFacade) {
		this.subscriptionRepository = subscriptionRepository;
		this.userFacade = userFacade;
		this.billingFacade = billingFacade;
	}

	/**
	 * Full creation flow — intentionally NOT @Transactional at this level.
	 * Each step opens and closes its own short transaction so the DB connection
	 * is never held open during the gateway HTTP call.
	 */
	public SubscriptionUpdatedEvent createAndCharge(CreateSubscriptionDTO payload) {
		// TX 1: validate + persist subscription row, then release the connection
		Subscription savedSub = self.saveSubscription(payload);
		log.info("📋 [ASSINATURA] Sub {} criada para user {} plano {}. Iniciando cobrança.", savedSub.getId(), savedSub.getUserId(), savedSub.getPlan());

		// Gateway call — outside any transaction, no DB connection held here
		BillingFacade.ChargeResult charge = billingFacade.chargeForNewSubscription(
			savedSub.getId(), savedSub.getPlan(), savedSub.getPaymentToken());

		if (!charge.success()) {
			// TX 2a: delete the subscription row on decline, then release the connection
			self.deleteSubscription(savedSub.getId());
			log.warn("⚠️ [ASSINATURA] Cobrança recusada para sub {}. Revertendo criação. Motivo: {}", savedSub.getId(), charge.errorMessage());
			throw new UnprocessableEntityException("Payment declined: " + charge.errorMessage());
		}

		log.info("🎉 [ASSINATURA] Sub {} ativada com sucesso. Válida até {}", savedSub.getId(), savedSub.getExpiringDate());
		return new SubscriptionUpdatedEvent(
			savedSub.getId(), savedSub.getUserId(), savedSub.getStatus(),
			savedSub.getPlan(), savedSub.getStartDate(), savedSub.getExpiringDate(), savedSub.isAutoRenew());
	}

	@Transactional
	public Subscription saveSubscription(CreateSubscriptionDTO payload) {
		if (!userFacade.exists(payload.userId())) {
			throw new ResourceNotFoundException("User not found");
		}
		if (subscriptionRepository.existsByUserIdAndStatus(payload.userId(), SubscriptionStatus.ACTIVE)) {
			throw new UnprocessableEntityException("User already has an active subscription");
		}
		Subscription sub = Subscription.create(payload.userId(), payload.plan(), payload.paymentToken());
		return subscriptionRepository.save(sub);
	}

	@Transactional
	public void deleteSubscription(UUID subscriptionId) {
		subscriptionRepository.deleteById(subscriptionId);
	}
}
