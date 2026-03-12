package com.platform.subscription_manager.billing.infrastructure.messaging;

import com.platform.subscription_manager.shared.infrastructure.messaging.BillingResultEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import java.util.concurrent.CompletableFuture;

@Component
public class BillingResultEventDispatcher {

	private static final Logger log = LoggerFactory.getLogger(BillingResultEventDispatcher.class);
	private static final String RESULTS_TOPIC = "subscription.billing-results";
	private final KafkaTemplate<Object, Object> kafkaTemplate;

	public BillingResultEventDispatcher(KafkaTemplate<Object, Object> kafkaTemplate) {
		this.kafkaTemplate = kafkaTemplate;
	}

	@ApplicationModuleListener
	public CompletableFuture<Void> onBillingResult(BillingResultEvent event) {
		log.info("🚀 [OUTBOX DISPATCHER] Lendo resultado de billing do banco e enviando para o Kafka. Assinatura: {}", event.subscriptionId());

		return kafkaTemplate.send(RESULTS_TOPIC, event.subscriptionId().toString(), event)
			.whenComplete((result, ex) -> {
				if (ex != null) {
					log.error("❌ Falha crítica ao enviar BillingResult para o Kafka. Modulith fará retry. Erro: {}", ex.getMessage());
				} else {
					log.info("✅ Resultado entregue no tópico [{}] partição [{}] offset [{}]",
						result.getRecordMetadata().topic(),
						result.getRecordMetadata().partition(),
						result.getRecordMetadata().offset());
				}
			})
			.thenAccept(result -> {});
	}
}
