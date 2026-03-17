package com.platform.subscription_manager.billing.application.services;

import com.platform.subscription_manager.billing.infrastructure.web.integrations.PaymentGatewayClient;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.concurrent.Semaphore;

/**
 * Controls concurrent access to the Payment Gateway using a Semaphore.
 *
 * <p>Strategy: Use blocking {@code semaphore.acquire()} (not timeout-based rejection).
 * This allows V-threads to wait naturally and cheaply, providing backpressure without
 * artificial error generation. The semaphore acts as a rate-limiter, ensuring the
 * Gateway never receives more than N concurrent requests.
 *
 * <p>Why blocking instead of timeout?
 * <ul>
 *   <li>V-threads are cheap: they can wait without blocking OS threads.
 *   <li>Timeout-based rejection creates errors unnecessarily.
 *   <li>Natural backpressure is better: Kafka will slow down on its own.
 *   <li>Resilience4j Circuit Breaker handles infrastructure failures (timeout, 500s, etc).
 * </ul>
 *
 * <p>Layers of protection:
 * <ol>
 *   <li>Semaphore: Limits concurrent Gateway calls (preventive)
 *   <li>HTTP Timeout (15s): Detects slow Gateway (reactive)
 *   <li>Retry (3x): Recovers from transient failures
 *   <li>Circuit Breaker (50% failure): Stops cascading failures
 * </ol>
 */
@Component
public class GatewayAccesssControl {
	private static final Logger log = LoggerFactory.getLogger(GatewayAccesssControl.class);

	private final PaymentGatewayClient client;
	private final Semaphore semaphore;
	private final int permits;

	public GatewayAccesssControl(
			PaymentGatewayClient gatewayClient,
			@Value("${payment.gateway.semaphore.permits:20}") int permits) {
		this.client = gatewayClient;
		this.permits = permits;
		this.semaphore = new Semaphore(permits, false);
	}

	@PostConstruct
	public void start() {
		log.info("GatewayAccesssControl started with {} permits", permits);
	}


	/**
	 * Charges a subscription through the Gateway with bounded concurrency.
	 *
	 * <p>Blocks (V-thread-friendly) until a permit is available, then calls the Gateway.
	 * No timeout, no rejection: just natural backpressure.
	 *
	 * @param idempotencyKey unique key for idempotency
	 * @param paymentToken payment token (masked in logs)
	 * @param amount amount to charge
	 * @return Gateway response with transaction details
	 * @throws PaymentGatewayClient.GatewayLogicalFailureException if Gateway declines
	 * @throws org.springframework.web.client.RestClientException if infra failure
	 */
	public PaymentGatewayClient.GatewayResponse chargeWithRateLimit(
			String idempotencyKey,
			String paymentToken,
			BigDecimal amount) {

		semaphore.acquireUninterruptibly();

		try {
			int activePermits = permits - semaphore.availablePermits();
			log.debug("🔓 [GATEWAY] Permit acquired. Active={}/{}", activePermits, permits);
			return client.charge(idempotencyKey, paymentToken, amount);
		} finally {
			semaphore.release();
			int activePermits = permits - semaphore.availablePermits();
			log.debug("🔒 [GATEWAY] Permit released. Active={}/{}", activePermits, permits);
		}
	}
}
