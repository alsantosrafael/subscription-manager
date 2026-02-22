package com.platform.subscription_manager.billing.infrastructure.web.integrations;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("local")
public class WireMockLocalServer {

    private static final Logger log = LoggerFactory.getLogger(WireMockLocalServer.class);
    private WireMockServer wireMockServer;

    @PostConstruct
    public void start() {
        // containerThreads must exceed the max concurrent billing workers (15 per chunk × possible parallel chunks).
        // Too low → WireMock cancels connections → RST_STREAM → spurious CB failures.
        wireMockServer = new WireMockServer(WireMockConfiguration.options().port(8081).containerThreads(200));
        wireMockServer.start();
        WireMock.configureFor("localhost", 8081);

        log.info("🚀 WireMock Server iniciado na porta 8081 para simular o gateway de pagamentos.");
        log.info("📋 Tokens configurados:");
        log.info("   tok_test_success           → aprovação (~70% das subs no seed)");
        log.info("   tok_test_always_fail        → recusa permanente → SUSPENDED após 3 tentativas");
        log.info("   tok_test_fail_first_attempt → falha 1ª tentativa, sucesso 2ª (demonstra retry)");

        setupStubs();
    }

    private void setupStubs() {
        // NOTA SOBRE O DELAY: 200ms simula latência real de gateway sem saturar o WireMock.
        // O valor anterior de 800ms com 15 threads concorrentes causava RST_STREAM/Broken pipe
        // porque o Jetty interno do WireMock descartava conexões sob pressão — o circuit breaker
        // abria por erro de INFRA, não por falha de negócio. Comportamento enganoso para demos.

        // =====================================================================
        // 1. SUCESSO — tok_test_success
        // ~70% das subs do seed. Gateway aprova. Sub renovada → ACTIVE.
        // =====================================================================
        WireMock.stubFor(WireMock.post(WireMock.urlEqualTo("/v1/charges"))
                .withRequestBody(WireMock.matchingJsonPath("$.payment_method", WireMock.equalTo("tok_test_success")))
                .willReturn(WireMock.aResponse()
                        .withFixedDelay(200)
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "transactionId": "tx_success_0001",
                                  "status": "succeeded"
                                }
                                """)));

        // =====================================================================
        // 2. FALHA PERMANENTE — tok_test_always_fail
        // ~10% das subs do seed chegam com billingAttempts=2.
        // Esta é a 3ª tentativa → SubscriptionResultListener suspende a sub.
        // Demonstra: suspensão automática, cache atualizado, acesso revogado.
        // =====================================================================
        WireMock.stubFor(WireMock.post(WireMock.urlEqualTo("/v1/charges"))
                .withRequestBody(WireMock.matchingJsonPath("$.payment_method", WireMock.equalTo("tok_test_always_fail")))
                .willReturn(WireMock.aResponse()
                        .withFixedDelay(200)
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "transactionId": "tx_fail_0002",
                                  "status": "failed",
                                  "errorMessage": "Cartão bloqueado por suspeita de fraude."
                                }
                                """)));

        // =====================================================================
        // 3. FALHA SEEDER — tok_test_fail
        // Alias de falha lógica usado para seeds com billingAttempts=2.
        // =====================================================================
        WireMock.stubFor(WireMock.post(WireMock.urlEqualTo("/v1/charges"))
                .withRequestBody(WireMock.matchingJsonPath("$.payment_method", WireMock.equalTo("tok_test_fail")))
                .willReturn(WireMock.aResponse()
                        .withFixedDelay(200)
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "transactionId": "tx_fail_seed",
                                  "status": "failed",
                                  "errorMessage": "Saldo insuficiente."
                                }
                                """)));

        // =====================================================================
        // 4. MÁQUINA DE ESTADO — tok_test_fail_first_attempt
        // Falha na 1ª chamada, sucesso na 2ª.
        // Demonstra: backoff de retry, idempotência, recuperação automática.
        // ATENÇÃO: cenários WireMock são globais — não use este token em bulk.
        // =====================================================================
        String SCENARIO_NAME = "Retry Scenario";
        String STATE_SECOND_ATTEMPT = "SECOND_ATTEMPT";

        WireMock.stubFor(WireMock.post(WireMock.urlEqualTo("/v1/charges"))
                .inScenario(SCENARIO_NAME)
                .whenScenarioStateIs(Scenario.STARTED)
                .withRequestBody(WireMock.matchingJsonPath("$.payment_method", WireMock.equalTo("tok_test_fail_first_attempt")))
                .willReturn(WireMock.aResponse()
                        .withFixedDelay(200)
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "transactionId": "tx_scenario_fail_1",
                                  "status": "failed",
                                  "errorMessage": "Saldo Insuficiente"
                                }
                                """))
                .willSetStateTo(STATE_SECOND_ATTEMPT));

        WireMock.stubFor(WireMock.post(WireMock.urlEqualTo("/v1/charges"))
                .inScenario(SCENARIO_NAME)
                .whenScenarioStateIs(STATE_SECOND_ATTEMPT)
                .withRequestBody(WireMock.matchingJsonPath("$.payment_method", WireMock.equalTo("tok_test_fail_first_attempt")))
                .willReturn(WireMock.aResponse()
                        .withFixedDelay(200)
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "transactionId": "tx_scenario_success_2",
                                  "status": "succeeded"
                                }
                                """)));
    }

    @PreDestroy
    public void stop() {
        if (wireMockServer != null) {
            wireMockServer.stop();
            log.info("🛑 WireMock Server desligado.");
        }
    }
}