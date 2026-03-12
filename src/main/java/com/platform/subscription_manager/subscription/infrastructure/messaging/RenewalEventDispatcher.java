package com.platform.subscription_manager.subscription.infrastructure.messaging;


import com.platform.subscription_manager.shared.infrastructure.messaging.RenewalRequestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import java.util.concurrent.CompletableFuture;

@Component
public class RenewalEventDispatcher {

	private static final Logger log = LoggerFactory.getLogger(RenewalEventDispatcher.class);
	private static final String TOPIC_NAME = "subscription.renewals";
	private final KafkaTemplate<Object, Object> kafkaTemplate;

	public RenewalEventDispatcher(KafkaTemplate<Object, Object> kafkaTemplate) {
		this.kafkaTemplate = kafkaTemplate;
	}
	@ApplicationModuleListener
	public CompletableFuture<Void> onRenewalRequested(RenewalRequestedEvent event) {
		log.info("🚀 [OUTBOX DISPATCHER] Lendo evento do banco e enviando para o Kafka. Assinatura: {}", event.subscriptionId());

		return kafkaTemplate.send(TOPIC_NAME, event.subscriptionId().toString(), event)
			.whenComplete((result, ex) -> {
				if (ex != null) {
					log.error("❌ Falha crítica ao enviar para o Kafka. Modulith fará o retry. Erro: {}", ex.getMessage());
				} else {
					log.info("✅ Mensagem entregue no tópico [{}] partição [{}] offset [{}]",
						result.getRecordMetadata().topic(),
						result.getRecordMetadata().partition(),
						result.getRecordMetadata().offset());
				}
			})
			.thenAccept(result -> {});
	}
}