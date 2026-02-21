package com.platform.subscription_manager.billing.infrastructure.web.integrations;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Duration;

@Component
public class PaymentGatewayClient {

	private static final Logger log = LoggerFactory.getLogger(PaymentGatewayClient.class);
	private final RestClient restClient;

	public PaymentGatewayClient(
		@Value("${payment.gateway.url:http://localhost:8081}") String baseUrl,
		@Value("${payment.gateway.api-key:sk_test_123}") String apiKey){
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
		requestFactory.setReadTimeout((int) Duration.ofSeconds(10).toMillis());

		this.restClient = RestClient.builder()
			.baseUrl(baseUrl)
			.requestFactory(requestFactory)
			.defaultHeader("Authorization", "Bearer " + apiKey)
			.build();
	}

	public GatewayResponse charge(String idempotencyKey, String paymentToken, BigDecimal amount) {
		long amountInCents = amount.multiply(new BigDecimal("100")).longValueExact();
		log.info("💳 Enviando cobrança ao Gateway. Token: {}, Valor: {} centavos", paymentToken, amountInCents);

		try {
			GatewayResponse response = restClient.post()
				.uri("/v1/charges")
				.header("Idempotency-Key", idempotencyKey)
				.contentType(MediaType.APPLICATION_JSON)
				.body(new ChargeRequest(paymentToken, amountInCents, "BRL"))
				.retrieve()
				.body(GatewayResponse.class);

			if (response != null && !response.isSuccess()) {
				log.warn("❌ Gateway retornou 200 OK, mas a cobrança falhou logicamente: {}", response.status());
				return new GatewayResponse(response.transactionId(), "FAILED", "Cartão recusado pelo banco emissor.");
			}

			return response;

		} catch (HttpClientErrorException e) {
			log.warn("❌ Gateway recusou a requisição HTTP ({}): {}", e.getStatusCode(), e.getResponseBodyAsString());
			return new GatewayResponse(null, "FAILED", "Falha de validação ou recusa direta.");
		} catch (RestClientException e) {
			log.error("🔥 Falha de infraestrutura de rede com o Gateway. Lançando exceção para o Kafka retentar.", e);
			throw e;
		}
	}

	public record ChargeRequest(String payment_method, long amount, String currency) {}

	public record GatewayResponse(String transactionId, String status, String errorMessage) {
		public boolean isSuccess() {
			return "succeeded".equalsIgnoreCase(status) || "paid".equalsIgnoreCase(status) || "SUCCESS".equalsIgnoreCase(status);
		}
	}
}
