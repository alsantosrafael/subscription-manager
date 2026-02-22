package com.platform.subscription_manager.billing.application.services;

import com.platform.subscription_manager.billing.BillingFacade;
import com.platform.subscription_manager.shared.domain.BillingHistoryStatus;
import com.platform.subscription_manager.billing.domain.repositories.BillingHistoryRepository;
import com.platform.subscription_manager.billing.infrastructure.web.integrations.PaymentGatewayClient;
import com.platform.subscription_manager.shared.domain.Plan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class BillingFacadeImpl implements BillingFacade {

	private static final Logger log = LoggerFactory.getLogger(BillingFacadeImpl.class);

	private final PaymentGatewayClient paymentGatewayClient;
	private final BillingHistoryRepository billingHistoryRepository;

	public BillingFacadeImpl(PaymentGatewayClient paymentGatewayClient,
							 BillingHistoryRepository billingHistoryRepository) {
		this.paymentGatewayClient = paymentGatewayClient;
		this.billingHistoryRepository = billingHistoryRepository;
	}

	/**
	 * Charges the user synchronously on subscription creation.
	 * The idempotency key uses a dedicated "initial" suffix so it never collides
	 * with the renewal key format ({subscriptionId}-{year}-{month}-attempt-{n}).
	 * Retries on infrastructure failures are handled by Resilience4j @Retry on the caller (SubscriptionService.create).
	 * The underlying charge() call is also protected by @CircuitBreaker, so after enough consecutive
	 * failures the circuit opens and retries stop immediately.
	 * A 4xx / logical decline is NOT retried — the gateway returns GatewayResponse with isSuccess()==false,
	 * which we propagate as ChargeResult(success=false) and the caller throws UnprocessableEntityException.
	 */
	@Override
	public ChargeResult chargeForNewSubscription(UUID subscriptionId, Plan plan, String paymentToken) {
		String idempotencyKey = subscriptionId + "-initial";

		log.info("💳 [COBRANÇA INICIAL] Iniciando cobrança para sub {} plano {}", subscriptionId, plan);

		// 1. Write the PENDING record before calling the gateway.
		//    ON CONFLICT DO NOTHING silently skips if a concurrent or duplicate request already inserted it.
		//    Isolated in its own transaction so we don't hold a DB connection during the HTTP call below.
		billingHistoryRepository.insertIfNotExist(subscriptionId, idempotencyKey);

		// 2. If a previous *committed* attempt already reached a final state, return it immediately.
		//    This fires when the client sends the same request twice (not on @Retry retries, because
		//    a rolled-back transaction leaves no row behind).
		var existing = billingHistoryRepository.findByIdempotencyKey(idempotencyKey);
		if (existing.isPresent() && existing.get().getStatus() != BillingHistoryStatus.PENDING) {
			BillingHistoryStatus finalStatus = existing.get().getStatus();
			log.info("🔁 [IDEMPOTENCY] Resultado já existente para sub {}: {}. Reutilizando.", subscriptionId, finalStatus);
			boolean success = finalStatus == BillingHistoryStatus.SUCCESS;
			return new ChargeResult(success, existing.get().getGatewayTransactionId(), success ? null : "Tentativa anterior recusada");
		}

		// 3. Call the gateway OUTSIDE any transaction — an HTTP call to an external service must
		//    never hold a DB connection open. The Idempotency-Key header ensures the gateway deduplicates.
		PaymentGatewayClient.GatewayResponse response =
			paymentGatewayClient.charge(idempotencyKey, paymentToken, plan.getPrice());

		BillingHistoryStatus status = response.isSuccess()
			? BillingHistoryStatus.SUCCESS
			: BillingHistoryStatus.FAILED;

		// 4. Persist the result in its own transaction. Only updates the row if still PENDING —
		//    guards against a race where a previous timed-out attempt already wrote SUCCESS.
		billingHistoryRepository.updateResult(idempotencyKey, status, response.transactionId());

		if (response.isSuccess()) {
			log.info("✅ [COBRANÇA INICIAL] Sub {} aprovada pelo gateway. txId={}", subscriptionId, response.transactionId());
		} else {
			log.warn("❌ [COBRANÇA INICIAL] Sub {} recusada pelo gateway: {}", subscriptionId, response.errorMessage());
		}

		return new ChargeResult(response.isSuccess(), response.transactionId(), response.errorMessage());
	}
}









