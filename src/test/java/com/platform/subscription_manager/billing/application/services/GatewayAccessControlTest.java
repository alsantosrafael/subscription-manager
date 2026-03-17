package com.platform.subscription_manager.billing.application.services;

import com.platform.subscription_manager.billing.infrastructure.web.integrations.PaymentGatewayClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GatewayAccesssControl")
class GatewayAccessControlTest {

	@Mock private PaymentGatewayClient paymentGatewayClient;

	private GatewayAccesssControl accessControl;

	@BeforeEach
	void setUp() {
		accessControl = new GatewayAccesssControl(paymentGatewayClient, 5);
	}

	// ─────────────────────────────────────────────────────────────────────────
	// Basic functionality: acquire, call, release
	// ─────────────────────────────────────────────────────────────────────────
	@Nested
	@DisplayName("Basic charge with rate limiting")
	class BasicCharge {

		@Test
		@DisplayName("Single charge completes successfully with permit acquire and release")
		void singleCharge_succeeds() {
			PaymentGatewayClient.GatewayResponse response = 
				new PaymentGatewayClient.GatewayResponse("txn123", "SUCCESS", null);
			when(paymentGatewayClient.charge(anyString(), anyString(), any()))
				.thenReturn(response);

			PaymentGatewayClient.GatewayResponse result = accessControl.chargeWithRateLimit(
				"idempotency-key", 
				"tok_valid", 
				BigDecimal.TEN
			);

			assertEquals("txn123", result.transactionId());
			assertEquals("SUCCESS", result.status());
			verify(paymentGatewayClient, times(1)).charge(anyString(), anyString(), any());
		}

		@Test
		@DisplayName("Gateway logical failure is thrown and permit released")
		void gatewayLogicalFailure_throws() {
			PaymentGatewayClient.GatewayLogicalFailureException exception = 
				new PaymentGatewayClient.GatewayLogicalFailureException(
					new PaymentGatewayClient.GatewayResponse(null, "FAILED", "card declined")
				);
			when(paymentGatewayClient.charge(anyString(), anyString(), any()))
				.thenThrow(exception);

			assertThrows(PaymentGatewayClient.GatewayLogicalFailureException.class, () ->
				accessControl.chargeWithRateLimit("key", "tok", BigDecimal.TEN)
			);

			verify(paymentGatewayClient, times(1)).charge(anyString(), anyString(), any());
		}

		@Test
		@DisplayName("Infrastructure failure is thrown and permit released")
		void infraFailure_throws() {
			when(paymentGatewayClient.charge(anyString(), anyString(), any()))
				.thenThrow(new RuntimeException("connection refused"));

			assertThrows(RuntimeException.class, () ->
				accessControl.chargeWithRateLimit("key", "tok", BigDecimal.TEN)
			);

			verify(paymentGatewayClient, times(1)).charge(anyString(), anyString(), any());
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// Concurrency: semaphore limits concurrent calls
	// ─────────────────────────────────────────────────────────────────────────
	@Nested
	@DisplayName("Concurrency control with semaphore")
	class ConcurrencyControl {

		@Test
		@DisplayName("Max 5 permits: 10 parallel calls limits concurrent Gateway calls to 5")
		void semaphore_limits_concurrent_calls() throws Exception {
			int permits = 5;
			accessControl = new GatewayAccesssControl(paymentGatewayClient, permits);

			int numThreads = 10;
			PaymentGatewayClient.GatewayResponse response = 
				new PaymentGatewayClient.GatewayResponse("txn", "SUCCESS", null);

			AtomicInteger maxConcurrent = new AtomicInteger(0);
			AtomicInteger currentConcurrent = new AtomicInteger(0);

			when(paymentGatewayClient.charge(anyString(), anyString(), any()))
				.thenAnswer(invocation -> {
					int now = currentConcurrent.incrementAndGet();
					maxConcurrent.accumulateAndGet(now, Math::max);
					Thread.sleep(100);
					currentConcurrent.decrementAndGet();
					return response;
				});

			try (ExecutorService executor = Executors.newFixedThreadPool(numThreads)) {
				CountDownLatch latch = new CountDownLatch(numThreads);

				for (int i = 0; i < numThreads; i++) {
					final int id = i;
					executor.submit(() -> {
						try {
							accessControl.chargeWithRateLimit("key-" + id, "tok-" + id, BigDecimal.TEN);
						} finally {
							latch.countDown();
						}
					});
				}

				if (!latch.await(30, TimeUnit.SECONDS)) {
					throw new AssertionError("Test timed out");
				}
			}

			assertEquals(5, maxConcurrent.get(), 
				"Semaphore should limit concurrent calls to " + permits);
		}

		@Test
		@DisplayName("Threads wait for permits naturally without errors")
		void sequential_queue_no_errors() throws Exception {
			int permits = 1;
			accessControl = new GatewayAccesssControl(paymentGatewayClient, permits);

			AtomicInteger callCount = new AtomicInteger(0);
			PaymentGatewayClient.GatewayResponse response = 
				new PaymentGatewayClient.GatewayResponse("txn", "SUCCESS", null);
			
			when(paymentGatewayClient.charge(anyString(), anyString(), any()))
				.thenAnswer(invocation -> {
					callCount.incrementAndGet();
					Thread.sleep(50);
					return response;
				});

			int numThreads = 5;
			try (ExecutorService executor = Executors.newFixedThreadPool(numThreads)) {
				CountDownLatch latch = new CountDownLatch(numThreads);

				for (int i = 0; i < numThreads; i++) {
					final int id = i;
					executor.submit(() -> {
						try {
							accessControl.chargeWithRateLimit("key-" + id, "tok", BigDecimal.TEN);
						} finally {
							latch.countDown();
						}
					});
				}

				if (!latch.await(30, TimeUnit.SECONDS)) {
					throw new AssertionError("Test timed out");
				}
			}

			assertEquals(5, callCount.get(), "All 5 calls should complete");
			verify(paymentGatewayClient, times(5)).charge(anyString(), anyString(), any());
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// Configuration: different permit counts
	// ─────────────────────────────────────────────────────────────────────────
	@Nested
	@DisplayName("Semaphore configuration")
	class SemaphoreConfiguration {

		@Test
		@DisplayName("Constructor with 3 permits limits concurrency to 3")
		void constructor_permits_3_limits_to_3() throws Exception {
			accessControl = new GatewayAccesssControl(paymentGatewayClient, 3);

			AtomicInteger maxConcurrent = new AtomicInteger(0);
			AtomicInteger currentConcurrent = new AtomicInteger(0);

			PaymentGatewayClient.GatewayResponse response = 
				new PaymentGatewayClient.GatewayResponse("txn", "SUCCESS", null);
			
			when(paymentGatewayClient.charge(anyString(), anyString(), any()))
				.thenAnswer(invocation -> {
					int now = currentConcurrent.incrementAndGet();
					maxConcurrent.accumulateAndGet(now, Math::max);
					Thread.sleep(100);
					currentConcurrent.decrementAndGet();
					return response;
				});

			try (ExecutorService executor = Executors.newFixedThreadPool(10)) {
				CountDownLatch latch = new CountDownLatch(10);

				for (int i = 0; i < 10; i++) {
					final int id = i;
					executor.submit(() -> {
						try {
							accessControl.chargeWithRateLimit("key-" + id, "tok", BigDecimal.TEN);
						} finally {
							latch.countDown();
						}
					});
				}

				if (!latch.await(30, TimeUnit.SECONDS)) {
					throw new AssertionError("Test timed out");
				}
			}

			assertEquals(3, maxConcurrent.get(), "Should limit to 3 permits");
		}

		@Test
		@DisplayName("Default constructor uses 5 permits")
		void default_permits_is_5() throws Exception {
			AtomicInteger maxConcurrent = new AtomicInteger(0);
			AtomicInteger currentConcurrent = new AtomicInteger(0);

			PaymentGatewayClient.GatewayResponse response = 
				new PaymentGatewayClient.GatewayResponse("txn", "SUCCESS", null);
			
			when(paymentGatewayClient.charge(anyString(), anyString(), any()))
				.thenAnswer(invocation -> {
					int now = currentConcurrent.incrementAndGet();
					maxConcurrent.accumulateAndGet(now, Math::max);
					Thread.sleep(100);
					currentConcurrent.decrementAndGet();
					return response;
				});

			try (ExecutorService executor = Executors.newFixedThreadPool(10)) {
				CountDownLatch latch = new CountDownLatch(10);

				for (int i = 0; i < 10; i++) {
					final int id = i;
					executor.submit(() -> {
						try {
							accessControl.chargeWithRateLimit("key-" + id, "tok", BigDecimal.TEN);
						} finally {
							latch.countDown();
						}
					});
				}

				if (!latch.await(30, TimeUnit.SECONDS)) {
					throw new AssertionError("Test timed out");
				}
			}

			assertEquals(5, maxConcurrent.get(), "Default should be 5 permits");
		}
	}
}
