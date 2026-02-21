package com.platform.subscription_manager.billing.infrastructure.messaging;


import com.platform.subscription_manager.billing.domain.entity.BillingHistory;
import com.platform.subscription_manager.billing.domain.enums.BillingHistoryStatus;
import com.platform.subscription_manager.billing.domain.repositories.BillingHistoryRepository;
import com.platform.subscription_manager.billing.infrastructure.web.integrations.PaymentGatewayClient;
import com.platform.subscription_manager.shared.infrastructure.messaging.BillingResultEvent;
import com.platform.subscription_manager.shared.infrastructure.messaging.RenewalRequestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Component
public class BillingWorker {

	private static final Logger log = LoggerFactory.getLogger(BillingWorker.class);
	private final BillingHistoryRepository billingRepository;
	private final KafkaTemplate<Object, Object> kafkaTemplate;
	private final PaymentGatewayClient paymentGatewayClient;

	public BillingWorker(BillingHistoryRepository billingRepository, KafkaTemplate<Object, Object> kafkaTemplate,
						 PaymentGatewayClient paymentGatewayClient) {
		this.billingRepository = billingRepository;
		this.kafkaTemplate = kafkaTemplate;
		this.paymentGatewayClient = paymentGatewayClient;
	}

	@KafkaListener(topics = "subscription.renewals", groupId = "billing-processor-group")
	@Transactional
	public void consumeRenewalRequest(RenewalRequestedEvent event) {
		log.info("📥 [BILLING] Recebendo evento de cobrança para assinatura: {}", event.subscriptionId());
		BillingHistory chargeRecord;
		String idempotencyKey = String.format("%s-%d-%d-attempt-%d",
			event.subscriptionId().toString(),
			event.expiringDate().getYear(),
			event.expiringDate().getMonthValue(),
			event.currentAttempt());

		Optional<BillingHistory> existingBillingHistory = billingRepository.findByIdempotencyKey(idempotencyKey);
		if (existingBillingHistory.isPresent()) {
			log.info("⏭️ Chave {} já processada. Reenviando evento de resposta...", idempotencyKey);
			chargeRecord = existingBillingHistory.get();
			if (!BillingHistoryStatus.PENDING.equals(chargeRecord.getStatus())) {
				log.info("🔄 Estado terminal alcançado para chave {}. Reemitindo resultado para o Kafka.", idempotencyKey);
				boolean wasSuccess = "SUCCESS".equals(chargeRecord.getStatus());

				BillingResultEvent retryEvent = new BillingResultEvent(
					event.subscriptionId(), chargeRecord.getGatewayTransactionId(), chargeRecord.getStatus(), null
				);
				kafkaTemplate.send("subscription.billing-results", event.subscriptionId().toString(), retryEvent);
				return;
			}
		}	else {
			try {
				chargeRecord = BillingHistory.createPending(event.subscriptionId(), idempotencyKey);
				billingRepository.saveAndFlush(chargeRecord);
			} catch (DataIntegrityViolationException e) {
				log.warn("⚠️ Mensagem duplicada interceptada pelo banco! Chave [{}] já processada. Descartando.", idempotencyKey);
				return;
			}
		}
		try {
			PaymentGatewayClient.GatewayResponse response = paymentGatewayClient.charge(
				idempotencyKey,
				event.paymentToken(),
				event.plan().getPrice()
			);
			BillingResultEvent resultEvent;

			if (response.isSuccess()) {
				chargeRecord.markAsSuccess(response.transactionId());
				resultEvent = new BillingResultEvent(event.subscriptionId(), response.transactionId(), BillingHistoryStatus.SUCCESS, null);
				log.info("✅ [GATEWAY MOCK] Cobrança Aprovada!");
			} else {
				chargeRecord.markAsFailed();
				resultEvent = new BillingResultEvent(event.subscriptionId(), null, BillingHistoryStatus.FAILED, "Cartão Recusado - Saldo Insuficiente");
				log.warn("❌ [GATEWAY MOCK] Cobrança Recusada.");
			}

			billingRepository.save(chargeRecord);
			kafkaTemplate.send("subscription.billing-results", event.subscriptionId().toString(), resultEvent);
			log.info("✅ Cobrança finalizada e resultado emitido!");

		} catch (Exception e) {
			log.error("❌ Erro inesperado de infraestrutura ao processar a cobrança.", e);
			throw e;
		}
	}
}
