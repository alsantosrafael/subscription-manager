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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConfig {
	// ========== Bootstrap & Connection ==========
	@Value("${spring.kafka.bootstrap-servers:localhost:9092}")
	private String bootstrapServers;

	@Value("${spring.kafka.client-id:subscription-manager}")
	private String clientId;

	// ========== Consumer Configuration ==========
	@Value("${spring.kafka.consumer.auto-offset-reset:earliest}")
	private String offsetReset;

	@Value("${spring.kafka.consumer.properties.session.timeout.ms:45000}")
	private int sessionTimeoutMs;

	@Value("${spring.kafka.consumer.properties.heartbeat.interval.ms:10000}")
	private int heartbeatIntervalMs;

	@Value("${spring.kafka.consumer.properties.max.poll.interval.ms:300000}")
	private int maxPollIntervalMs;

	@Value("${spring.kafka.consumer.properties.fetch.min.bytes:1024}")
	private int fetchMinBytes;

	@Value("${spring.kafka.consumer.properties.fetch.max.wait.ms:500}")
	private int fetchMaxWaitMs;

	@Value("${spring.kafka.consumer.properties.isolation.level:read_committed}")
	private String isolationLevel;

	@Value("${spring.kafka.consumer.max.poll.records:50}")
	private int maxPollRecords;

	// ========== Producer Configuration ==========
	@Value("${spring.kafka.producer.delivery.timeout.ms:120000}")
	private int producerDeliveryTimeoutMs;

	@Value("${spring.kafka.producer.retries:5}")
	private int producerRetries;

	@Value("${spring.kafka.producer.retry.backoff.ms:500}")
	private int producerRetryBackoffMs;

	@Value("${spring.kafka.producer.linger.ms:5}")
	private int lingerMs;

	@Value("${spring.kafka.producer.batch.size:65536}")
	private int batchSize;

	@Value("${spring.kafka.producer.compression.type:snappy}")
	private String compressionType;

	@Value("${spring.kafka.properties.request.timeout.ms:60000}")
	private int requestTimeoutMs;

	@Value("${spring.kafka.properties.connections.max.idle.ms:540000}")
	private int connectionsMaxIdleMs;

	@Value("${spring.kafka.listener.concurrency:3}")
	private int listenerConcurrency;

	@Value("${spring.kafka.listener.poll.timeout:3000}")
	private int listenerPollTimeoutMs;

	@Value("${spring.kafka.listener.renewals-concurrency:10}")
	private int renewalsConcurrency;


	@Bean
	public KafkaTemplate<Object, Object> kafkaTemplate() {
		Map<String, Object> props = new HashMap<>();
		
		// --- Bootstrap & Client ID ---
		props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
		props.put(ProducerConfig.CLIENT_ID_CONFIG, clientId + "-producer");

		// --- Serialization ---
		props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class);
		props.put(JacksonJsonSerializer.ADD_TYPE_INFO_HEADERS, true);

		// --- Performance: Batching & Compression ---
		props.put(ProducerConfig.LINGER_MS_CONFIG, lingerMs); // 5ms for batching
		props.put(ProducerConfig.BATCH_SIZE_CONFIG, batchSize); // 64KB batches
		props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, compressionType); // snappy

		// --- Reliability: Idempotence & Acks ---
		props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true); // prevent duplicates
		props.put(ProducerConfig.ACKS_CONFIG, "all"); // wait for all in-sync replicas
		props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5); // ordered + fast
		props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE); // retry indefinitely

		// --- Timeouts ---
		props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, producerDeliveryTimeoutMs); // 120s total
		props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, requestTimeoutMs); // 60s per request
		props.put("connections.max.idle.ms", connectionsMaxIdleMs); // 9 minutes

		// --- Metadata & Request Handling ---
		props.put(ProducerConfig.METADATA_MAX_AGE_CONFIG, 300000); // refresh metadata every 5 min
		props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 67108864); // 64MB buffer

		return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
	}

	@Bean
	public ConsumerFactory<String, Object> consumerFactory() {
		Map<String, Object> props = new HashMap<>();
		
		// --- Bootstrap & Client ID ---
		props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
		props.put(ConsumerConfig.CLIENT_ID_CONFIG, clientId + "-consumer");

		// --- Serialization ---
		props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JacksonJsonDeserializer.class);
		props.put(JacksonJsonDeserializer.TRUSTED_PACKAGES, "com.platform.subscription_manager.*");
		props.put(JacksonJsonDeserializer.USE_TYPE_INFO_HEADERS, true);

		// --- Offset & Commit Strategy ---
		props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, offsetReset); // 'earliest' for critical data
		props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false); // manual ACK only
		props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPollRecords); // batch size

		// --- Session Management (prevent rebalancing during processing) ---
		props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, sessionTimeoutMs); // 45s
		props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, heartbeatIntervalMs); // 10s
		props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, maxPollIntervalMs); // 300s (5 min)

		// --- Fetch Tuning (balance latency vs throughput) ---
		props.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, fetchMinBytes); // 1KB minimum
		props.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, fetchMaxWaitMs); // 500ms wait

		// --- Isolation Level (ensure transactional safety) ---
		props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, isolationLevel); // read_committed

		// --- Connection Management ---
		props.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, requestTimeoutMs); // 60s
		props.put("connections.max.idle.ms", connectionsMaxIdleMs); // 9 minutes

		// --- Partition Offset Strategy ---
		props.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, "org.apache.kafka.clients.consumer.RangeAssignor");

		return new DefaultKafkaConsumerFactory<>(props);
	}

	@Bean
	public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory() {
		ConcurrentKafkaListenerContainerFactory<String, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
		factory.setConsumerFactory(consumerFactory());
		
		// --- Batch Processing ---
		factory.setBatchListener(true);
		
		// --- Concurrency: should match max partition count (billing-results has 3) ---
		factory.setConcurrency(listenerConcurrency); // 3 threads
		
		// --- Manual Acknowledgment ---
		factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
		
		// --- Poll Timeout (time spent polling for records before returning empty) ---
		factory.getContainerProperties().setPollTimeout(listenerPollTimeoutMs); // 3s
		
		// --- Error Handling: log errors and continue processing ---
		factory.setCommonErrorHandler(new DefaultErrorHandler());
		
		// --- Shutdown Behavior ---
		factory.getContainerProperties().setShutdownTimeout(30000); // 30s graceful shutdown
		
		return factory;
	}

	@Bean
	public ConcurrentKafkaListenerContainerFactory<String, Object> renewalsKafkaListenerContainerFactory() {
		ConcurrentKafkaListenerContainerFactory<String, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
		factory.setConsumerFactory(consumerFactory());
		factory.setBatchListener(true);
		factory.setConcurrency(renewalsConcurrency); // 10 threads
		factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
		factory.getContainerProperties().setPollTimeout(listenerPollTimeoutMs);
		factory.setCommonErrorHandler(new DefaultErrorHandler());
		factory.getContainerProperties().setShutdownTimeout(30000);
		return factory;
	}
}
