package com.platform.subscription_manager.billing.infrastructure.web.integrations;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
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
        wireMockServer = new WireMockServer(WireMockConfiguration.options().port(8081).containerThreads(200));
        wireMockServer.start();
        WireMock.configureFor("localhost", 8081);

        log.info("🚀 WireMock iniciado na porta 8081 (gateway de pagamentos simulado).");
        log.info("📋 Tokens:");
        log.info("   tok_test_success     → gateway aprova → sub ACTIVE");
        log.info("   tok_test_always_fail → gateway recusa → sub SUSPENDED após 3 sweeps");

        setupStubs();
    }

    private void setupStubs() {
        // ── tok_test_success ──────────────────────────────────────────────────
        // Gateway aprova. Usado em 90% do seed (renovação normal e retry 1x)
        // e em todas as jornadas HTTP do stress-seed.
        // ─────────────────────────────────────────────────────────────────────
        WireMock.stubFor(WireMock.post(WireMock.urlEqualTo("/v1/charges"))
                .withRequestBody(WireMock.matchingJsonPath("$.payment_method", WireMock.equalTo("tok_test_success")))
                .willReturn(WireMock.aResponse()
                        .withFixedDelay(200)
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"transactionId":"tx_success_001","status":"succeeded"}
                                """)));

        // ── tok_test_always_fail ──────────────────────────────────────────────
        // Gateway recusa (lógica de negócio, HTTP 200 status=failed).
        // Usado em 10% do seed com billingAttempts=2: a 3ª tentativa do sweeper
        // aciona a suspensão automática → SUSPENDED.
        // Demonstra: suspensão, cache invalidado, acesso revogado.
        // ─────────────────────────────────────────────────────────────────────
        WireMock.stubFor(WireMock.post(WireMock.urlEqualTo("/v1/charges"))
                .withRequestBody(WireMock.matchingJsonPath("$.payment_method", WireMock.equalTo("tok_test_always_fail")))
                .willReturn(WireMock.aResponse()
                        .withFixedDelay(200)
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"transactionId":"tx_fail_001","status":"failed","errorMessage":"Cartão bloqueado."}
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