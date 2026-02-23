package com.platform.subscription_manager.subscription.application.services;

import com.platform.subscription_manager.billing.BillingFacade;
import com.platform.subscription_manager.shared.domain.SubscriptionStatus;
import com.platform.subscription_manager.shared.domain.exceptions.ConflictException;
import com.platform.subscription_manager.shared.domain.exceptions.ResourceNotFoundException;
import com.platform.subscription_manager.shared.domain.exceptions.UnprocessableEntityException;
import com.platform.subscription_manager.shared.infrastructure.messaging.SubscriptionUpdatedEvent;
import com.platform.subscription_manager.subscription.application.dtos.CreateSubscriptionDTO;
import com.platform.subscription_manager.subscription.domain.entity.Subscription;
import com.platform.subscription_manager.subscription.domain.repositories.SubscriptionRepository;
import com.platform.subscription_manager.user.UserFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Owns the transactional boundary for subscription writes.
 * Returns a SubscriptionUpdatedEvent from every mutating method so the caller
 * (SubscriptionService) can publish it inside its own transactionTemplate wrapper —
 * giving @TransactionalEventListener(AFTER_COMMIT) a boundary to bind to.
 */
@Service
public class SubscriptionWriteService {

	private static final Logger log = LoggerFactory.getLogger(SubscriptionWriteService.class);

	private final SubscriptionRepository subscriptionRepository;
	private final UserFacade userFacade;
	private final BillingFacade billingFacade;

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
		SaveResult result = self.saveSubscription(payload);
		Subscription savedSub = result.subscription();
		log.info("📋 [ASSINATURA] Sub {} {} para user {} plano {}. Iniciando cobrança.",
			savedSub.getId(), result.wasReactivated() ? "reativada" : "criada",
			savedSub.getUserId(), savedSub.getPlan());

		BillingFacade.ChargeResult charge = billingFacade.chargeForNewSubscription(
			savedSub.getId(), savedSub.getPlan(), savedSub.getPaymentToken());

		if (!charge.success()) {
			SubscriptionStatus rollbackTo = result.wasReactivated()
				? result.previousStatus()
				: SubscriptionStatus.INACTIVE;
			self.revertReactivation(savedSub.getId(), rollbackTo);
			log.warn("⚠️ [ASSINATURA] Cobrança recusada para sub {}. Revertido para {}. Motivo: {}",
				savedSub.getId(), rollbackTo, charge.errorMessage());
			throw new UnprocessableEntityException("Payment declined: " + charge.errorMessage());
		}

		log.info("🎉 [ASSINATURA] Sub {} ativada com sucesso. Válida até {}", savedSub.getId(), savedSub.getExpiringDate());
		return new SubscriptionUpdatedEvent(
			savedSub.getId(), savedSub.getUserId(), savedSub.getStatus(),
			savedSub.getPlan(), savedSub.getStartDate(), savedSub.getExpiringDate(), savedSub.isAutoRenew());
	}

	/** Carries the saved entity plus enough context for a clean rollback. */
	public record SaveResult(Subscription subscription, boolean wasReactivated, SubscriptionStatus previousStatus) {}

	@Transactional
	public SaveResult saveSubscription(CreateSubscriptionDTO payload) {
		if (!userFacade.exists(payload.userId())) {
			throw new ResourceNotFoundException("User not found");
		}

		Optional<Subscription> existing = subscriptionRepository.findByUserId(payload.userId());

		if (existing.isPresent()) {
			Subscription sub = existing.get();

			if (sub.getStatus() == SubscriptionStatus.ACTIVE) {
				throw new ConflictException("User already has an active subscription");
			}

			SubscriptionStatus previous = sub.getStatus();
			sub.reactivate(payload.plan(), payload.paymentToken());
			return new SaveResult(subscriptionRepository.save(sub), true, previous);
		}

		Subscription sub = Subscription.create(payload.userId(), payload.plan(), payload.paymentToken());
		return new SaveResult(subscriptionRepository.save(sub), false, null);
	}

	@Transactional
	public SubscriptionUpdatedEvent revertReactivation(UUID subscriptionId, SubscriptionStatus restoreTo) {
		Subscription sub = subscriptionRepository.findById(subscriptionId)
			.orElseThrow(() -> new IllegalStateException("Sub not found during revert: " + subscriptionId));

		sub.restoreStatus(restoreTo);
		subscriptionRepository.save(sub);

		return new SubscriptionUpdatedEvent(
			sub.getId(), sub.getUserId(), sub.getStatus(),
			sub.getPlan(), sub.getStartDate(), sub.getExpiringDate(), sub.isAutoRenew());
	}

	@Transactional
	public SubscriptionUpdatedEvent cancelSubscription(UUID subscriptionId, UUID userId) {
		Subscription sub = subscriptionRepository.findByIdAndUserId(subscriptionId, userId)
			.orElseThrow(() -> new ResourceNotFoundException("Subscription not found or belongs to another user"));

		if (sub.getStatus() != SubscriptionStatus.ACTIVE) {
			throw new UnprocessableEntityException(
				"Only ACTIVE subscriptions can be canceled. Current status: " + sub.getStatus());
		}

		sub.cancelRenewal();
		sub.markAsCanceled();
		subscriptionRepository.save(sub);

		log.info("🚫 [ASSINATURA] Sub {} cancelada para user {}. Acesso mantido até {}. autoRenew=false.",
			sub.getId(), sub.getUserId(), sub.getExpiringDate());

		return new SubscriptionUpdatedEvent(
			sub.getId(), sub.getUserId(), sub.getStatus(),
			sub.getPlan(), sub.getStartDate(), sub.getExpiringDate(), sub.isAutoRenew());
	}
}
