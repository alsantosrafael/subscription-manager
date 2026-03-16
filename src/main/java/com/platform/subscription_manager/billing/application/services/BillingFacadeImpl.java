package com.platform.subscription_manager.billing.application.services;

import com.platform.subscription_manager.billing.BillingFacade;
import com.platform.subscription_manager.billing.domain.entity.BillingHistory;
import com.platform.subscription_manager.shared.domain.BillingHistoryStatus;
import com.platform.subscription_manager.billing.domain.repositories.BillingHistoryRepository;
import com.platform.subscription_manager.billing.infrastructure.web.integrations.PaymentGatewayClient;
import com.platform.subscription_manager.shared.domain.Plan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class BillingFacadeImpl implements BillingFacade {

	private static final Logger log = LoggerFactory.getLogger(BillingFacadeImpl.class);

	private final GatewayAccesssControl gatewayAccessControl;
	private final BillingHistoryRepository billingHistoryRepository;

	@Autowired @Lazy
	private BillingFacadeImpl self;

	public BillingFacadeImpl(GatewayAccesssControl gatewayAccessControl,
							 BillingHistoryRepository billingHistoryRepository) {
		this.gatewayAccessControl = gatewayAccessControl;
		this.billingHistoryRepository = billingHistoryRepository;
	}

	/**
	 * Charges the user synchronously on subscription creation.
	 * Two dedicated @Transactional(REQUIRES_NEW) methods — one to write PENDING before the
	 * HTTP call, one to write the final result after — ensure no DB connection is held
	 * during the gateway I/O.
	 *
	 * We use self-injection (Spring AOP proxy) instead of TransactionTemplate lambdas so that
	 * the transaction context is correctly propagated even under virtual threads, where
	 * ThreadLocal-based TransactionSynchronizationManager may lose context inside lambdas.
	 *
	 * Gateway calls are made through {@link GatewayAccesssControl} which uses a semaphore
	 * to limit concurrent calls (default: 5 permits).
	 */
	@Override
	public ChargeResult chargeForNewSubscription(UUID subscriptionId, Plan plan, String paymentToken) {
		String idempotencyKey = subscriptionId + "-initial-" + plan.name() + "-" + Math.abs(paymentToken.hashCode());

		log.info("💳 [COBRANÇA INICIAL] Iniciando cobrança para sub {} plano {}", subscriptionId, plan);

		int inserted = self.insertPending(subscriptionId, idempotencyKey);

		Optional<BillingHistory> existing =
			billingHistoryRepository.findByIdempotencyKey(idempotencyKey);
		if (existing.isPresent() && existing.get().getStatus() != BillingHistoryStatus.PENDING) {
			BillingHistoryStatus finalStatus = existing.get().getStatus();
			log.info("🔁 [IDEMPOTENCY] Resultado já existente para sub {}: {}. Reutilizando.", subscriptionId, finalStatus);
			boolean success = finalStatus == BillingHistoryStatus.SUCCESS;
			return new ChargeResult(success, existing.get().getGatewayTransactionId(), success ? null : "Tentativa anterior recusada");
		}

		if (inserted == 0) {
			log.warn("⚠️ [COBRANÇA INICIAL] Cobrança concorrente detectada para sub {} — " +
				"outra requisição já está processando a mesma chave. Abortando para evitar dupla cobrança.", subscriptionId);
			return new ChargeResult(false, null, "Cobrança em andamento. Tente novamente em instantes.");
		}
		PaymentGatewayClient.GatewayResponse response;
		try {
			response = gatewayAccessControl.chargeWithRateLimit(idempotencyKey, paymentToken, plan.getPrice());
		} catch (PaymentGatewayClient.GatewayLogicalFailureException e) {
			log.warn("❌ [COBRANÇA INICIAL] Cartão recusado para sub {}. Motivo: {}", subscriptionId, e.getMessage());
			self.persistResult(idempotencyKey, BillingHistoryStatus.FAILED, null);
			return new ChargeResult(false, null, e.getResponse().errorMessage());
		} catch (RuntimeException e) {
			log.warn("⚠️ [COBRANÇA INICIAL] Gateway indisponível para sub {}. Revertendo. Causa: {}", subscriptionId, e.getMessage());
			self.persistResult(idempotencyKey, BillingHistoryStatus.FAILED, null);
			return new ChargeResult(false, null, "Gateway de pagamentos indisponível. Tente novamente mais tarde.");
		}

		BillingHistoryStatus status = response.isSuccess()
			? BillingHistoryStatus.SUCCESS
			: BillingHistoryStatus.FAILED;

		self.persistResult(idempotencyKey, status, response.transactionId());

		if (response.isSuccess()) {
			log.info("✅ [COBRANÇA INICIAL] Sub {} aprovada pelo gateway. txId={}", subscriptionId, response.transactionId());
		} else {
			log.warn("❌ [COBRANÇA INICIAL] Sub {} recusada pelo gateway: {}", subscriptionId, response.errorMessage());
		}

		return new ChargeResult(response.isSuccess(), response.transactionId(), response.errorMessage());
	}

	/**
	 * Inserts a PENDING billing history row inside its own committed transaction.
	 * REQUIRES_NEW guarantees the row is visible to subsequent reads even if the
	 * calling method has no active transaction.
	 *
	 * @return 1 if this call inserted the row, 0 if another concurrent caller already did
	 *         (ON CONFLICT DO NOTHING).  The caller uses this to decide whether it owns
	 *         the gateway call — only the inserting thread should proceed.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public int insertPending(UUID subscriptionId, String idempotencyKey) {
		return billingHistoryRepository.insertIfNotExist(subscriptionId, idempotencyKey);
	}

	/**
	 * Updates the billing history row to its final state inside its own committed transaction.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void persistResult(String idempotencyKey, BillingHistoryStatus status, String transactionId) {
		int updated = billingHistoryRepository.updateResult(idempotencyKey, status, transactionId);
		if (updated == 0) {
			log.warn("[BILLING] No PENDING billing_history row found for key {}. Status {} not recorded.", idempotencyKey, status);
		}
	}
}
