package com.platform.subscription_manager.subscription.infrastructure.web;

import com.platform.subscription_manager.shared.config.PaymentTokenConverter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/test")
@Profile("local")
@Tag(name = "Test Utils", description = """
	Utilitários de teste — disponíveis **apenas no perfil `local`**, nunca em produção.
	Use `POST /api/test/seed` para popular o banco com dados representando todos os perfis de negócio
	antes de executar o sweep manual via `POST /v1/admin/billing/trigger-sweep`.
	""")
public class SmokeTestSeederController {

	private static final Logger log = LoggerFactory.getLogger(SmokeTestSeederController.class);

	private final JdbcTemplate jdbcTemplate;
	private final PaymentTokenConverter tokenConverter;
	private final int baseDelayMinutes;

	public SmokeTestSeederController(JdbcTemplate jdbcTemplate,
									 PaymentTokenConverter tokenConverter,
									 @Value("${billing.retry.base-delay-minutes:1}") int baseDelayMinutes) {
		this.jdbcTemplate = jdbcTemplate;
		this.tokenConverter = tokenConverter;
		this.baseDelayMinutes = baseDelayMinutes;
	}

	@Operation(
		summary = "Popular banco com dados de teste",
		description = """
			Limpa todas as tabelas (assinaturas, usuários, histórico, outbox, ShedLock) e insere
			`count` assinaturas distribuídas entre os 3 perfis de negócio:

			| % | Perfil | Token | billingAttempts | Resultado esperado após sweep |
			|---|---|---|---|---|
			| 70% | Renovação normal | `tok_test_success` | 0 | `ACTIVE` renovada |
			| 10% | Retry — falhou 1x | `tok_test_success` | 1 | `ACTIVE` renovada |
			| 10% | Beira do abismo | `tok_test_always_fail` | 2 | `SUSPENDED` (3ª falha) |
			| 10% | Cancelados vencidos | `tok_test_success` | 0 | `INACTIVE` |

			**Fluxo recomendado:**
			```
			POST /api/test/seed?count=20
			POST /v1/admin/billing/trigger-sweep
			GET  /v1/admin/subscriptions        ← observe as transições após ~3s
			```
			""",
		responses = {
			@ApiResponse(responseCode = "200", description = "Seed concluído")
		}
	)
	@PostMapping("/seed")
	public ResponseEntity<Map<String, Object>> seedDatabase(
		@Parameter(description = "Número de assinaturas a criar. Recomendado: 20 para demos, 2000 para testes de carga.")
		@RequestParam(defaultValue = "2000") int count) {
		log.info("🧹 Limpando tabelas...");
		jdbcTemplate.execute("TRUNCATE TABLE billing_history CASCADE;");
		jdbcTemplate.execute("TRUNCATE TABLE event_publication CASCADE;");
		jdbcTemplate.execute("TRUNCATE TABLE subscriptions CASCADE;");
		jdbcTemplate.execute("TRUNCATE TABLE users CASCADE;");
		jdbcTemplate.update("""
			INSERT INTO shedlock (name, lock_until, locked_at, locked_by)
			VALUES ('dailySweepTask', '1970-01-01 00:00:00', '1970-01-01 00:00:00', 'seed-reset')
			ON CONFLICT (name) DO UPDATE
			  SET lock_until = '1970-01-01 00:00:00',
			      locked_at  = '1970-01-01 00:00:00',
			      locked_by  = 'seed-reset'
			""");


		log.warn("⚠️  Aguarde ~5s após o seed antes de acionar o scheduler — " +
			"mensagens Kafka in-flight do ciclo anterior ainda podem estar a caminho do consumer.");

		LocalDateTime today = LocalDateTime.now().truncatedTo(ChronoUnit.DAYS);
		LocalDateTime pastMonth = today.minusMonths(1);

		List<Object[]> usersBatch = new ArrayList<>();
		List<Object[]> subsBatch = new ArrayList<>();

		log.info("🌱 Gerando dados para {} assinaturas...", count);

		int happyPath = (int) (count * 0.7);
		int retryOnce = (int) (count * 0.8) - happyPath;
		int nearSuspension = (int) (count * 0.9) - (int) (count * 0.8);
		int canceled = count - (int) (count * 0.9);

		for (int i = 0; i < count; i++) {
			UUID userId = UUID.randomUUID();
			String doc = userId.toString().substring(0, 11).replace("-", "");

			usersBatch.add(new Object[]{userId, "User " + i, "user" + i + "@test.com", doc, LocalDateTime.now()});

			String plan = (i % 3 == 0) ? "PREMIUM" : (i % 3 == 1) ? "FAMILIA" : "BASICO";

			if (i < (count * 0.7)) {
				// 70% Happy Path — renova com sucesso
				subsBatch.add(createSubRow(userId, plan, "ACTIVE", "tok_test_success", pastMonth, today, true, 0, null));
			} else if (i < (count * 0.8)) {
				// 10% Retry — falhou 1x, vai tentar novamente e ter sucesso
				subsBatch.add(createSubRow(userId, plan, "ACTIVE", "tok_test_success", pastMonth, today, true, 1, LocalDateTime.now().minusHours(3)));
			} else if (i < (count * 0.9)) {
				// 10% Beira do Abismo — falhou 2x, próxima tentativa vai suspender
				subsBatch.add(createSubRow(userId, plan, "ACTIVE", "tok_test_always_fail", pastMonth, today, true, 2, LocalDateTime.now().minusHours(3)));
			} else {
				// 10% Cancelados — status já CANCELED, autoRenew=false, expiry=hoje.
				// Representa um usuário que cancelou durante o ciclo e chegou ao vencimento.
				// O scheduler vai varrer esses e mover para INACTIVE via expireCanceledSubscriptions().
				subsBatch.add(createSubRow(userId, plan, "CANCELED", "tok_test_success", pastMonth, today, false, 0, null));
			}
		}

		log.info("💾 Inserindo usuários no banco...");
		jdbcTemplate.batchUpdate("INSERT INTO users (id, name, email, document, created_at) VALUES (?, ?, ?, ?, ?)", usersBatch);

		log.info("💾 Inserindo assinaturas no banco...");
		jdbcTemplate.batchUpdate("""
            INSERT INTO subscriptions 
            (id, user_id, plan, status, payment_token, start_date, expiring_date, auto_renew, billing_attempts, last_billing_attempt, next_retry_at, version) 
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
        """, subsBatch);

		log.info("✅ Smoke Test concluído com {} registros.", count);

		// Build a rich response so the caller knows exactly what was seeded and what to expect
		Map<String, Object> seeded = new LinkedHashMap<>();
		seeded.put("happyPath_renewsSuccessfully", happyPath);
		seeded.put("retry1x_renewsOn2ndAttempt", retryOnce);
		seeded.put("nearSuspension_3rdFailSuspends", nearSuspension);
		seeded.put("canceled_becomesInactive", canceled);

		Map<String, Object> expectedAfterSweep = new LinkedHashMap<>();
		expectedAfterSweep.put("ACTIVE_renewed_billingAttempts_0", happyPath + retryOnce);
		expectedAfterSweep.put("SUSPENDED_autoRenew_false", nearSuspension);
		expectedAfterSweep.put("INACTIVE_from_canceled", canceled);
		expectedAfterSweep.put("note", "Aguarde ~3-5s após o sweep para o Kafka processar. Verifique em GET /v1/admin/status");

		Map<String, Object> nextSteps = new LinkedHashMap<>();
		nextSteps.put("step1_triggerSweep", "POST /v1/admin/billing/trigger-sweep");
		nextSteps.put("step2_wait", "Aguarde ~3-5 segundos para processamento Kafka");
		nextSteps.put("step3_checkStatus", "GET /v1/admin/status  ← snapshot com contagens por status");
		nextSteps.put("step4_listAll", "GET /v1/admin/subscriptions  ← lista todas com campos operacionais");

		Map<String, Object> response = new LinkedHashMap<>();
		response.put("totalSeeded", count);
		response.put("seededBreakdown", seeded);
		response.put("expectedAfterSweep", expectedAfterSweep);
		response.put("nextSteps", nextSteps);

		return ResponseEntity.ok(response);
	}

	private Object[] createSubRow(UUID userId, String plan, String status, String token,
								  LocalDateTime start, LocalDateTime expire,
								  boolean autoRenew, int attempts, LocalDateTime lastAttempt) {

		LocalDateTime nextRetry = null;
		if (autoRenew) {
			if (attempts > 0 && lastAttempt != null) {
				// Mirror the exact backoff formula used by SubscriptionResultListener:
				// delay = 2^attempts * baseDelayMinutes — seeded state is consistent with production
				long delayMinutes = (long) Math.pow(2, attempts) * baseDelayMinutes;
				nextRetry = lastAttempt.plusMinutes(delayMinutes);
			} else {
				// No prior failures — nextRetryAt = expiringDate (due now, eligible immediately)
				nextRetry = expire;
			}
		}
		// autoRenew=false (CANCELED/SUSPENDED): nextRetryAt stays null — scheduler ignores these rows
		// in findEligibleForRenewal (which requires autoRenew=true) and only sweeps them via
		// expireCanceledSubscriptions.

		return new Object[]{
			UUID.randomUUID(),
			userId,
			plan,
			status,
			tokenConverter.convertToDatabaseColumn(token),
			start,
			expire,
			autoRenew,
			attempts,
			lastAttempt,
			nextRetry
		};
	}
}

