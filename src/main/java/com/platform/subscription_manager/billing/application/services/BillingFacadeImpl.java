package com.platform.subscription_manager.billing.application.services;

import com.platform.subscription_manager.billing.BillingFacade;
import com.platform.subscription_manager.shared.domain.BillingHistoryStatus;
import com.platform.subscription_manager.billing.domain.repositories.BillingHistoryRepository;
import com.platform.subscription_manager.billing.infrastructure.web.integrations.PaymentGatewayClient;
import com.platform.subscription_manager.shared.domain.Plan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Service
public class BillingFacadeImpl implements BillingFacade {

	private static final Logger log = LoggerFactory.getLogger(BillingFacadeImpl.class);

	private final PaymentGatewayClient paymentGatewayClient;
	private final BillingHistoryRepository billingHistoryRepository;
	private final TransactionTemplate transactionTemplate;

	public BillingFacadeImpl(PaymentGatewayClient paymentGatewayClient,
							 BillingHistoryRepository billingHistoryRepository,
							 TransactionTemplate transactionTemplate) {
		this.paymentGatewayClient = paymentGatewayClient;
		this.billingHistoryRepository = billingHistoryRepository;
		this.transactionTemplate = transactionTemplate;
	}

	/**
	 * Charges the user synchronously on subscription creation.
	 * Two dedicated transactions — one to write PENDING before the HTTP call,
	 * one to write the final result after — ensure no DB connection is held
	 * during the gateway I/O.
	 */
	@Override
	public ChargeResult chargeForNewSubscription(UUID subscriptionId, Plan plan, String paymentToken) {
		String idempotencyKey = subscriptionId + "-initial";

		log.info("💳 [COBRANÇA INICIAL] Iniciando cobrança para sub {} plano {}", subscriptionId, plan);

		// 1. Insert PENDING — committed immediately so the gateway call runs with no open connection.
		transactionTemplate.executeWithoutResult(tx ->
			billingHistoryRepository.insertIfNotExist(subscriptionId, idempotencyKey)
		);

		// 2. If a previous committed attempt already reached a final state, return it immediately.
		var existing = billingHistoryRepository.findByIdempotencyKey(idempotencyKey);
		if (existing.isPresent() && existing.get().getStatus() != BillingHistoryStatus.PENDING) {
			BillingHistoryStatus finalStatus = existing.get().getStatus();
			log.info("🔁 [IDEMPOTENCY] Resultado já existente para sub {}: {}. Reutilizando.", subscriptionId, finalStatus);
			boolean success = finalStatus == BillingHistoryStatus.SUCCESS;
			return new ChargeResult(success, existing.get().getGatewayTransactionId(), success ? null : "Tentativa anterior recusada");
		}

		// 3. Call the gateway — no open DB connection at this point.
		PaymentGatewayClient.GatewayResponse response;
		try {
			response = paymentGatewayClient.charge(idempotencyKey, paymentToken, plan.getPrice());
		} catch (RuntimeException e) {
			// Circuit breaker open or infra failure — clean up PENDING row and surface as a declined charge.
			// The caller (SubscriptionWriteService) will revert the subscription and return 422 to the client.
			log.warn("⚠️ [COBRANÇA INICIAL] Gateway indisponível para sub {}. Revertendo. Causa: {}", subscriptionId, e.getMessage());
			transactionTemplate.executeWithoutResult(tx ->
				billingHistoryRepository.updateResult(idempotencyKey, BillingHistoryStatus.FAILED, null)
			);
			return new ChargeResult(false, null, "Gateway de pagamentos indisponível. Tente novamente mais tarde.");
		}

		BillingHistoryStatus status = response.isSuccess()
			? BillingHistoryStatus.SUCCESS
			: BillingHistoryStatus.FAILED;

		// 4. Persist the final result in its own committed transaction.
		transactionTemplate.executeWithoutResult(tx ->
			billingHistoryRepository.updateResult(idempotencyKey, status, response.transactionId())
		);

		if (response.isSuccess()) {
			log.info("✅ [COBRANÇA INICIAL] Sub {} aprovada pelo gateway. txId={}", subscriptionId, response.transactionId());
		} else {
			log.warn("❌ [COBRANÇA INICIAL] Sub {} recusada pelo gateway: {}", subscriptionId, response.errorMessage());
		}

		return new ChargeResult(response.isSuccess(), response.transactionId(), response.errorMessage());
	}
}
