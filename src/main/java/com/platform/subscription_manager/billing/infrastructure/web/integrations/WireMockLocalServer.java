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
        wireMockServer = new WireMockServer(WireMockConfiguration.options().port(8081));
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
                        .withStatus(200) // Retorna 200 OK
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "transactionId": "tx_success_0001",
                                  "status": "succeeded"
                                }
                                """)));

        // =====================================================================
        // 2. MOCK DE FALHA ABSOLUTA (Cartão Bloqueado)
        // =====================================================================
        WireMock.stubFor(WireMock.post(WireMock.urlEqualTo("/v1/charges"))
                .withRequestBody(WireMock.matchingJsonPath("$.payment_method", WireMock.equalTo("tok_test_always_fail")))
                .willReturn(WireMock.aResponse()
                        .withFixedDelay(800)
                        .withStatus(200) // Falso Positivo (200 OK mas status failed)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "transactionId": "tx_fail_0002",
                                  "status": "failed",
                                  "errorMessage": "Cartão bloqueado por suspeita de fraude."
                                }
                                """)));

        // =====================================================================
        // 3. MOCK COM MÁQUINA DE ESTADO (O Smoke Test da Retentativa!)
        // =====================================================================
        String SCENARIO_NAME = "Retry Scenario";
        String STATE_SECOND_ATTEMPT = "SECOND_ATTEMPT";

        // Regra A: Quando o cenário começa (Tentativa 1), ele FALHA e avança o estado.
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
                .willSetStateTo(STATE_SECOND_ATTEMPT)); // Transição de Estado!

        // Regra B: Quando já estiver no estado 'SECOND_ATTEMPT', ele APROVA!
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