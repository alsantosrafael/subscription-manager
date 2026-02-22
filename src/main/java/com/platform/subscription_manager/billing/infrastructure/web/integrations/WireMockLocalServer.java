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

        log.info("🚀 WireMock Server iniciado na porta 8081 para simular a Pagar.me / Stripe.");

        setupStubs();
    }

    private void setupStubs() {
        // =====================================================================
        // 1. MOCK DE SUCESSO (Caminho Feliz)
        // =====================================================================
        WireMock.stubFor(WireMock.post(WireMock.urlEqualTo("/v1/charges"))
                .withRequestBody(WireMock.matchingJsonPath("$.payment_method", WireMock.equalTo("tok_test_success")))
                .willReturn(WireMock.aResponse()
                        .withFixedDelay(800)
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "transactionId": "tx_success_0001",
                                  "status": "succeeded"
                                }
                                """)));

        // =====================================================================
        // 2. MOCK DE FALHA ABSOLUTA (Cartão Bloqueado para sempre)
        // Used by tok_test_always_fail — sub will be SUSPENDED after 3 attempts.
        // =====================================================================
        WireMock.stubFor(WireMock.post(WireMock.urlEqualTo("/v1/charges"))
                .withRequestBody(WireMock.matchingJsonPath("$.payment_method", WireMock.equalTo("tok_test_always_fail")))
                .willReturn(WireMock.aResponse()
                        .withFixedDelay(800)
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
        // 3. MOCK DE FALHA PARA O SEEDER (tok_test_fail)
        // Used by the 10% "Beira do Abismo" seeder scenario (billingAttempts=2).
        // Always fails logically so these subs will be SUSPENDED on the next sweep.
        // =====================================================================
        WireMock.stubFor(WireMock.post(WireMock.urlEqualTo("/v1/charges"))
                .withRequestBody(WireMock.matchingJsonPath("$.payment_method", WireMock.equalTo("tok_test_fail")))
                .willReturn(WireMock.aResponse()
                        .withFixedDelay(800)
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
        // 4. MOCK COM MÁQUINA DE ESTADO (O Smoke Test da Retentativa!)
        // NOTE: WireMock scenarios are global — concurrent requests for the same
        // token share the same state machine. This stub is designed for a single
        // subscription with tok_test_fail_first_attempt, not bulk scenarios.
        // =====================================================================
        String SCENARIO_NAME = "Retry Scenario";
        String STATE_SECOND_ATTEMPT = "SECOND_ATTEMPT";

        WireMock.stubFor(WireMock.post(WireMock.urlEqualTo("/v1/charges"))
                .inScenario(SCENARIO_NAME)
                .whenScenarioStateIs(Scenario.STARTED)
                .withRequestBody(WireMock.matchingJsonPath("$.payment_method", WireMock.equalTo("tok_test_fail_first_attempt")))
                .willReturn(WireMock.aResponse()
                        .withFixedDelay(800)
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
                        .withFixedDelay(800)
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