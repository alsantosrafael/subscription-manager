package com.platform.subscription_manager.subscription.infrastructure.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/test")
@Profile("local")
public class SmokeTestSeederController {

	private static final Logger log = LoggerFactory.getLogger(SmokeTestSeederController.class);
	private final JdbcTemplate jdbcTemplate;

	public SmokeTestSeederController(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@PostMapping("/seed")
	public String seedDatabase(@RequestParam(defaultValue = "2000") int count) {
		log.info("🧹 Limpando tabelas...");
		jdbcTemplate.execute("TRUNCATE TABLE billing_history CASCADE;");
		jdbcTemplate.execute("TRUNCATE TABLE event_publication CASCADE;");
		jdbcTemplate.execute("TRUNCATE TABLE subscriptions CASCADE;");
		jdbcTemplate.execute("TRUNCATE TABLE users CASCADE;");

		LocalDateTime today = LocalDateTime.now().truncatedTo(ChronoUnit.DAYS);
		LocalDateTime pastMonth = today.minusMonths(1);

		List<Object[]> usersBatch = new ArrayList<>();
		List<Object[]> subsBatch = new ArrayList<>();

		log.info("🌱 Gerando dados para {} assinaturas...", count);

		for (int i = 0; i < count; i++) {
			UUID userId = UUID.randomUUID();
			String doc = userId.toString().substring(0, 11).replace("-", "");

			// Dados do Usuário
			usersBatch.add(new Object[]{userId, "User " + i, "user" + i + "@test.com", doc, LocalDateTime.now()});

			// Lógica de distribuição de cenários (proporcional)
			String plan = (i % 3 == 0) ? "PREMIUM" : (i % 3 == 1) ? "FAMILIA" : "BASICO";

			if (i < (count * 0.7)) {
				// 70% Happy Path (Sucesso)
				subsBatch.add(createSubRow(userId, plan, "ACTIVE", "tok_test_success", pastMonth, today, true, 0, null));
			} else if (i < (count * 0.8)) {
				// 10% Retry (Falhou 1x) - Tentativa de cobrança falhou há 3 horas
				subsBatch.add(createSubRow(userId, plan, "ACTIVE", "tok_test_success", pastMonth, today, true, 1, LocalDateTime.now().minusHours(3)));
			} else if (i < (count * 0.9)) {
				// 10% Beira do Abismo (Vai Suspender) - Já falhou 2x
				subsBatch.add(createSubRow(userId, plan, "ACTIVE", "tok_test_fail", pastMonth, today, true, 2, LocalDateTime.now().minusHours(3)));
			} else {
				// 10% Cancelados (Vai para CANCELED no fim do ciclo)
				subsBatch.add(createSubRow(userId, plan, "ACTIVE", "tok_test_success", pastMonth, today, false, 0, null));
			}
		}

		log.info("💾 Inserindo usuários no banco...");
		jdbcTemplate.batchUpdate("INSERT INTO users (id, name, email, document, created_at) VALUES (?, ?, ?, ?, ?)", usersBatch);

		log.info("💾 Inserindo assinaturas no banco...");
		// Cleaned up the INSERT to exactly match your schema and the Object[] array
		jdbcTemplate.batchUpdate("""
            INSERT INTO subscriptions 
            (id, user_id, plan, status, payment_token, start_date, expiring_date, auto_renew, billing_attempts, last_billing_attempt, next_retry_at, version) 
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
        """, subsBatch);

		log.info("✅ Smoke Test concluído com {} registros.", count);
		return "Seeded " + count + " subscriptions successfully!";
	}

	private Object[] createSubRow(UUID userId, String plan, String status, String token,
								  LocalDateTime start, LocalDateTime expire,
								  boolean autoRenew, int attempts, LocalDateTime lastAttempt) {

		// Intelligent next_retry_at generation
		LocalDateTime nextRetry = null;
		if (autoRenew) {
			if (attempts > 0 && lastAttempt != null) {
				// If it's a retry scenario, schedule the next retry slightly in the future or right now
				nextRetry = LocalDateTime.now().plusMinutes(15);
			} else {
				// Normal renewal date
				nextRetry = expire;
			}
		}

		// Returns exactly 11 items to match the 11 placeholders
		return new Object[]{
			UUID.randomUUID(),
			userId,
			plan,
			status,
			token,
			start,
			expire,
			autoRenew,
			attempts,
			lastAttempt,
			nextRetry
		};
	}
}