	package com.platform.subscription_manager.shared.config;

	import org.apache.kafka.clients.consumer.ConsumerConfig;
	import org.apache.kafka.clients.producer.ProducerConfig;
	import org.apache.kafka.common.serialization.StringDeserializer;
	import org.apache.kafka.common.serialization.StringSerializer;
	import org.springframework.beans.factory.annotation.Value;
	import org.springframework.context.annotation.Bean;
	import org.springframework.context.annotation.Configuration;
	import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
	import org.springframework.kafka.core.ConsumerFactory;
	import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
	import org.springframework.kafka.core.DefaultKafkaProducerFactory;
	import org.springframework.kafka.core.KafkaOperations;
	import org.springframework.kafka.core.KafkaTemplate;
	import org.springframework.kafka.core.ProducerFactory;
	import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
	import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

	import java.util.HashMap;
	import java.util.Map;

	@Configuration
	public class KafkaConfig {
		@Value("${spring.kafka.bootstrap-servers:localhost:9092}")
		private String bootstrapServers;

		@Value("${spring.kafka.consumer.auto-offset-reset:latest}")
		private String offsetReset;

		@Bean
		public KafkaTemplate<Object, Object> kafkaTemplate() {
			Map<String, Object> props = new HashMap<>();
			props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
			props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
			props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class);
			props.put(JacksonJsonSerializer.ADD_TYPE_INFO_HEADERS, true);

			return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
		}

		@Bean
		public ConsumerFactory<String, Object> consumerFactory() {
			Map<String, Object> props = new HashMap<>();
			props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

			props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, offsetReset);

			props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
			props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JacksonJsonDeserializer.class);

			props.put(JacksonJsonDeserializer.TRUSTED_PACKAGES, "*");
			return new DefaultKafkaConsumerFactory<>(props);
		}

		@Bean
		public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory() {
			ConcurrentKafkaListenerContainerFactory<String, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
			factory.setConsumerFactory(consumerFactory());
			return factory;
		}
	}