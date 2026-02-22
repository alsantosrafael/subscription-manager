package com.platform.subscription_manager.billing.infrastructure.messaging;

import com.platform.subscription_manager.shared.domain.BillingHistoryStatus;
import com.platform.subscription_manager.billing.domain.repositories.BillingHistoryRepository;
import com.platform.subscription_manager.billing.infrastructure.web.integrations.PaymentGatewayClient;
import com.platform.subscription_manager.shared.domain.PaymentTokenPort;
import com.platform.subscription_manager.shared.infrastructure.messaging.BillingResultEvent;
import com.platform.subscription_manager.shared.infrastructure.messaging.RenewalRequestedEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Component
public class BillingWorker {

	private static final Logger log = LoggerFactory.getLogger(BillingWorker.class);
	private static final String RESULTS_TOPIC = "subscription.billing-results";

	private final BillingHistoryRepository billingRepository;
	private final PaymentGatewayClient paymentGatewayClient;
	private final TransactionTemplate transactionTemplate;
	private final KafkaTemplate<Object, Object> kafkaTemplate;
	private final PaymentTokenPort paymentTokenPort;

	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	public BillingWorker(BillingHistoryRepository billingRepository,
						 PaymentGatewayClient paymentGatewayClient,
						 TransactionTemplate transactionTemplate,
						 KafkaTemplate<Object, Object> kafkaTemplate,
						 PaymentTokenPort paymentTokenPort) {
		this.billingRepository = billingRepository;
		this.paymentGatewayClient = paymentGatewayClient;
		this.transactionTemplate = transactionTemplate;
		this.kafkaTemplate = kafkaTemplate;
		this.paymentTokenPort = paymentTokenPort;
	}

	@KafkaListener(topics = "subscription.renewals", groupId = "billing-processor-group", batch = "true")
	public void consumeRenewalRequest(List<ConsumerRecord<String, RenewalRequestedEvent>> records, Acknowledgment ack) {
		int chunkSize = 15;

		try {
			for (int i = 0; i < records.size(); i += chunkSize) {
				List<ConsumerRecord<String, RenewalRequestedEvent>> chunk =
					records.subList(i, Math.min(i + chunkSize, records.size()));

				List<CompletableFuture<Void>> futures = chunk.stream()
					.map(record -> CompletableFuture.runAsync(() -> processSingleRecord(record.value()), executor))
					.toList();

				// ⏱️ REDUZIDO PARA 20 SEGUNDOS. Se o Gateway travar, falha rápido!
				CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
					.get(20, TimeUnit.SECONDS);
			}

			ack.acknowledge();

		} catch (Exception e) {
			log.error("❌ Falha no lote! O Kafka vai reenviar as mensagens para retry.", e);
			throw new RuntimeException("Falha no lote", e);
		}
	}

	private void processSingleRecord(RenewalRequestedEvent event) {
		String key = createIdempotencyKey(event);

		// 1. IDEMPOTÊNCIA
		boolean isNew = Boolean.TRUE.equals(transactionTemplate.execute(status ->
			billingRepository.insertIfNotExist(event.subscriptionId(), key) == 1));

		if (!isNew) {
			// Key already exists — re-emit the stored result so downstream consumers stay in sync
			log.info("🔄 [IDEMPOTENCY] Key {} já processada. Re-emitindo resultado existente.", key);
			billingRepository.findByIdempotencyKey(key).ifPresent(existing -> {
				BillingHistoryStatus existingStatus = existing.getStatus();
				// If still PENDING (race: insert committed but result not yet written), treat as FAILED
				// so the subscription retry cycle continues rather than stalling forever
				if (existingStatus == BillingHistoryStatus.PENDING) {
					log.warn("⚠️ [IDEMPOTENCY] Key {} encontrada como PENDING. Emitindo FAILED para forçar retry.", key);
					existingStatus = BillingHistoryStatus.FAILED;
				}
				kafkaTemplate.send(RESULTS_TOPIC, event.subscriptionId().toString(),
					new BillingResultEvent(event.subscriptionId(), existing.getGatewayTransactionId(),
						existingStatus, event.expiringDate(), null));
			});
			return;
		}

		PaymentGatewayClient.GatewayResponse response;
		try {
			// 2. GATEWAY — token is fetched from DB here (decrypted transparently by JPA converter).
			//    It is never stored in the Kafka event to avoid plain-text exposure in the broker.
			String paymentToken = paymentTokenPort.getPaymentToken(event.subscriptionId())
				.orElseThrow(() -> new IllegalStateException("Token não encontrado para sub: " + event.subscriptionId()));

			response = paymentGatewayClient.charge(key, paymentToken, event.plan().getPrice());

		} catch (Exception e) {
			log.warn("⚠️ O Gateway falhou para a key {}. Registrando falha para não envenenar a idempotência.", key);

			transactionTemplate.executeWithoutResult(status -> {
				billingRepository.updateResult(key, BillingHistoryStatus.FAILED, null);

				kafkaTemplate.send(RESULTS_TOPIC, event.subscriptionId().toString(),
					new BillingResultEvent(event.subscriptionId(), null, BillingHistoryStatus.FAILED,
						event.expiringDate(), "Falha de comunicação com Gateway"));
			});

			return;
		}

		// 3. ATUALIZAÇÃO E RESULTADO (Sucesso ou Falha lógica)
		transactionTemplate.executeWithoutResult(status -> {
			BillingHistoryStatus finalStatus = response.isSuccess() ? BillingHistoryStatus.SUCCESS : BillingHistoryStatus.FAILED;

			billingRepository.updateResult(key, finalStatus, response.transactionId());

			kafkaTemplate.send(RESULTS_TOPIC, event.subscriptionId().toString(),
				new BillingResultEvent(event.subscriptionId(), response.transactionId(),
					finalStatus, event.expiringDate(), response.errorMessage()));
		});
	}

	private String createIdempotencyKey(RenewalRequestedEvent e) {
		return String.format("%s-%d-%02d-attempt-%d", e.subscriptionId(), e.expiringDate().getYear(), e.expiringDate().getMonthValue(), e.currentAttempt());
	}
}