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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@Component
public class BillingWorker {

	private static final Logger log = LoggerFactory.getLogger(BillingWorker.class);
	private static final String RESULTS_TOPIC = "subscription.billing-results";

	private final BillingHistoryRepository billingRepository;
	private final PaymentGatewayClient paymentGatewayClient;
	private final TransactionTemplate transactionTemplate;
	private final ApplicationEventPublisher eventPublisher;
	private final PaymentTokenPort paymentTokenPort;

	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	public BillingWorker(BillingHistoryRepository billingRepository,
						 PaymentGatewayClient paymentGatewayClient,
						 TransactionTemplate transactionTemplate,
						 ApplicationEventPublisher eventPublisher,
						 PaymentTokenPort paymentTokenPort) {
		this.billingRepository = billingRepository;
		this.paymentGatewayClient = paymentGatewayClient;
		this.transactionTemplate = transactionTemplate;
		this.eventPublisher = eventPublisher;
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

		boolean isNew = Boolean.TRUE.equals(transactionTemplate.execute(status ->
			billingRepository.insertIfNotExist(event.subscriptionId(), key) == 1));

		if (!isNew) {
			log.info("🔄 [IDEMPOTENCY] Key {} já processada. Re-emitindo resultado existente.", key);
			billingRepository.findByIdempotencyKey(key).ifPresent(existing -> {
				if (existing.getStatus() == BillingHistoryStatus.PENDING) {
					log.warn("⚠️ [IDEMPOTENCY] Key {} encontrada como PENDING — worker anterior não concluiu. " +
						"Emitindo FAILED para forçar retry.", key);
					eventPublisher.publishEvent(new BillingResultEvent(
						event.subscriptionId(),
						null,
						BillingHistoryStatus.FAILED,
						event.expiringDate(),
						"Cobrança anterior não finalizada — forçando retry"));
				} else {
					eventPublisher.publishEvent(new BillingResultEvent(
						event.subscriptionId(),
						existing.getGatewayTransactionId(),
						existing.getStatus(),
						event.expiringDate(),
						null));
				}
			});
			return;
		}

		PaymentGatewayClient.GatewayResponse response;
		try {
			String paymentToken = paymentTokenPort.getPaymentToken(event.subscriptionId())
				.orElseThrow(() -> new IllegalStateException("Token não encontrado para sub: " + event.subscriptionId()));

			response = paymentGatewayClient.charge(key, paymentToken, event.plan().getPrice());

		} catch (PaymentGatewayClient.GatewayLogicalFailureException e) {
			log.warn("💳 [GATEWAY] Cobrança recusada logicamente para key {}. Motivo: {}", key, e.getMessage());
			PaymentGatewayClient.GatewayResponse declined = e.getResponse();
			transactionTemplate.executeWithoutResult(status -> {
				LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
				billingRepository.updateResult(key, BillingHistoryStatus.FAILED, declined.transactionId(), now);
				eventPublisher.publishEvent(new BillingResultEvent(event.subscriptionId(), declined.transactionId(),
						BillingHistoryStatus.FAILED, event.expiringDate(), declined.errorMessage()));
			});
			return;

		} catch (Exception e) {
			log.warn("⚠️ O Gateway falhou para a key {}. Registrando falha para não envenenar a idempotência.", key);
			transactionTemplate.executeWithoutResult(status -> {
				LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
				billingRepository.updateResult(key, BillingHistoryStatus.FAILED, null, now);
				eventPublisher.publishEvent(new BillingResultEvent(event.subscriptionId(), null, BillingHistoryStatus.FAILED,
						event.expiringDate(), "Falha de comunicação com Gateway"));
			});

			return;
		}

		transactionTemplate.executeWithoutResult(status -> {
			LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
			billingRepository.updateResult(key, BillingHistoryStatus.SUCCESS, response.transactionId(), now);
			eventPublisher.publishEvent(new BillingResultEvent(event.subscriptionId(), response.transactionId(),
					BillingHistoryStatus.SUCCESS, event.expiringDate(), null));
		});
	}

	private String createIdempotencyKey(RenewalRequestedEvent e) {
		return String.format("%s-%d-%02d-attempt-%d",
			e.subscriptionId(),
			e.expiringDate().getYear(),
			e.expiringDate().getMonthValue(),
			e.attemptNumber());
	}
}