package com.platform.subscription_manager.billing.infrastructure.web.integrations;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;

@Component
public class PaymentGatewayClient {

	private static final Logger log = LoggerFactory.getLogger(PaymentGatewayClient.class);
	private final RestClient restClient;

	/**
	 * Represents a logical refusal from the gateway (HTTP 200, but status=failed).
	 * Distinct from RestClientException (infrastructure failure).
	 *
	 * - CircuitBreaker: records it as a failure → contributes to opening the circuit
	 *   when many cards are being declined (may indicate gateway-side issue).
	 * - Retry: ignores it → retrying a declined card immediately makes no sense.
	 * - BillingWorker: catches it and records FAILED in billing_history normally.
	 */
	public static class GatewayLogicalFailureException extends RuntimeException {
		private final GatewayResponse response;
		public GatewayLogicalFailureException(GatewayResponse response) {
			super("Gateway recusou a cobrança: " + response.errorMessage());
			this.response = response;
		}
		public GatewayResponse getResponse() { return response; }
	}

	public PaymentGatewayClient(
		@Value("${payment.gateway.url:http://localhost:8081}") String baseUrl,
		@Value("${payment.gateway.api-key:sk_test_123}") String apiKey) {

		HttpClient nativeClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.build();

		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(nativeClient);
		requestFactory.setReadTimeout(Duration.ofSeconds(15));

		this.restClient = RestClient.builder()
			.baseUrl(baseUrl)
			.requestFactory(requestFactory)
			.defaultHeader("Authorization", "Bearer " + apiKey)
			.build();
	}

	@CircuitBreaker(name = "gatewayPayment", fallbackMethod = "chargeFallback")
	public GatewayResponse charge(String idempotencyKey, String paymentToken, BigDecimal amount) {
		long amountInCents = amount.multiply(new BigDecimal("100")).longValueExact();
		String maskedToken = paymentToken.length() > 4
			? "****" + paymentToken.substring(paymentToken.length() - 4)
			: "****";
		log.info("💳 Enviando cobrança ao Gateway. Token: {}, Valor: {} centavos", maskedToken, amountInCents);

		try {
			GatewayResponse response = restClient.post()
				.uri("/v1/charges")
				.header("Idempotency-Key", idempotencyKey)
				.contentType(MediaType.APPLICATION_JSON)
				.body(new ChargeRequest(paymentToken, amountInCents, "BRL"))
				.retrieve()
				.body(GatewayResponse.class);

			if (response != null && !response.isSuccess()) {
				log.warn("⚠️ Gateway recusou a cobrança logicamente (HTTP 200, status={}): {}", response.status(), response.errorMessage());
				throw new GatewayLogicalFailureException(response);
			}

			return response;

		} catch (HttpClientErrorException e) {
			log.warn("❌ Gateway recusou a requisição HTTP ({}): {}", e.getStatusCode(), e.getResponseBodyAsString());
			// 4xx = gateway-side business rejection (bad request, auth failure).
			// Re-throw as logical failure so CB records it, but Retry won't repeat it.
			throw new GatewayLogicalFailureException(new GatewayResponse(null, "FAILED", e.getResponseBodyAsString()));

		} catch (RestClientException e) {
			log.error("🔥 Falha de infraestrutura de rede com o Gateway. {}", e.getMessage());
			// Network/infra failure — CB records it, Retry will attempt again.
			throw e;
		}
	}

	public record ChargeRequest(String payment_method, long amount, String currency) {}

	public record GatewayResponse(String transactionId, String status, String errorMessage) {
		public boolean isSuccess() {
			return "succeeded".equalsIgnoreCase(status) || "paid".equalsIgnoreCase(status) || "SUCCESS".equalsIgnoreCase(status);
		}
	}

	private GatewayResponse chargeFallback(String idempotencyKey, String paymentToken, BigDecimal amount, Throwable t) {
		log.error("🔴 [CIRCUIT BREAKER] Gateway indisponível ou circuito aberto. Rejeitando a chamada para o Kafka retentar mais tarde. Causa: {}", t.getMessage());
		throw new RuntimeException("Infraestrutura do Gateway de Pagamentos indisponível.", t);
	}
}
