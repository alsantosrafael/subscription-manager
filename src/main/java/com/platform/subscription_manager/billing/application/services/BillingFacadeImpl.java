package com.platform.subscription_manager.billing.application.services;

import com.platform.subscription_manager.billing.BillingFacade;
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

	private final PaymentGatewayClient paymentGatewayClient;
	private final BillingHistoryRepository billingHistoryRepository;

	@Autowired @Lazy
	private BillingFacadeImpl self;

	public BillingFacadeImpl(PaymentGatewayClient paymentGatewayClient,
							 BillingHistoryRepository billingHistoryRepository) {
		this.paymentGatewayClient = paymentGatewayClient;
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
	 */
	@Override
	public ChargeResult chargeForNewSubscription(UUID subscriptionId, Plan plan, String paymentToken) {
		// Key includes subscriptionId + plan + token hash so each reactivation attempt
		// (which may change plan or token) gets its own idempotency slot.
		// Using paymentToken.hashCode() avoids storing the raw token in the key.
		String idempotencyKey = subscriptionId + "-initial-" + plan.name() + "-" + Math.abs(paymentToken.hashCode());

		log.info("💳 [COBRANÇA INICIAL] Iniciando cobrança para sub {} plano {}", subscriptionId, plan);

		// 1. Insert PENDING — committed immediately so the gateway call runs with no open connection.
		self.insertPending(subscriptionId, idempotencyKey);

		// 2. If a previous committed attempt already reached a final state, return it immediately.
		Optional<com.platform.subscription_manager.billing.domain.entity.BillingHistory> existing =
			billingHistoryRepository.findByIdempotencyKey(idempotencyKey);
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
		} catch (PaymentGatewayClient.GatewayLogicalFailureException e) {
			// Gateway explicitly declined — card refused, fraud block, etc.
			log.warn("❌ [COBRANÇA INICIAL] Cartão recusado para sub {}. Motivo: {}", subscriptionId, e.getMessage());
			self.persistResult(idempotencyKey, BillingHistoryStatus.FAILED, null);
			return new ChargeResult(false, null, e.getResponse().errorMessage());
		} catch (RuntimeException e) {
			// Circuit breaker open or infra failure — gateway unreachable.
			log.warn("⚠️ [COBRANÇA INICIAL] Gateway indisponível para sub {}. Revertendo. Causa: {}", subscriptionId, e.getMessage());
			self.persistResult(idempotencyKey, BillingHistoryStatus.FAILED, null);
			return new ChargeResult(false, null, "Gateway de pagamentos indisponível. Tente novamente mais tarde.");
		}

		BillingHistoryStatus status = response.isSuccess()
			? BillingHistoryStatus.SUCCESS
			: BillingHistoryStatus.FAILED;

		// 4. Persist the final result in its own committed transaction.
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
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void insertPending(UUID subscriptionId, String idempotencyKey) {
		billingHistoryRepository.insertIfNotExist(subscriptionId, idempotencyKey);
	}

	/**
	 * Updates the billing history row to its final state inside its own committed transaction.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void persistResult(String idempotencyKey, BillingHistoryStatus status, String transactionId) {
		billingHistoryRepository.updateResult(idempotencyKey, status, transactionId);
	}
}
