package com.platform.subscription_manager.subscription.application.services;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Application service encapsulating admin/observability queries.
 * Keeps the controller thin and ensures the raw SQL stays out of the web layer.
 */
@Service
public class AdminService {

	private final JdbcTemplate jdbcTemplate;

	public AdminService(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/**
	 * Returns all subscriptions ordered by status severity and billing attempts.
	 */
	public List<Map<String, Object>> listAllSubscriptions() {
		return jdbcTemplate.queryForList("""
			SELECT
			    s.id,
			    s.user_id,
			    s.plan,
			    s.status,
			    s.auto_renew,
			    COALESCE(s.billing_attempts, 0)        AS billing_attempts,
			    s.expiring_date,
			    s.next_retry_at,
			    s.last_billing_attempt,
			    s.start_date
			FROM subscriptions s
			ORDER BY
			    CASE s.status
			        WHEN 'SUSPENDED' THEN 1
			        WHEN 'CANCELED'  THEN 2
			        WHEN 'ACTIVE'    THEN 3
			        WHEN 'INACTIVE'  THEN 4
			        ELSE 5
			    END,
			    COALESCE(s.billing_attempts, 0) DESC
			""");
	}

	/**
	 * Compares the current DB state against the expected outcomes of a seed + sweep cycle.
	 * Only considers subscriptions created more than 1 hour ago (i.e., seeded rows).
	 */
	public Map<String, Object> verifySeedResult() {
		Long total = jdbcTemplate.queryForObject("""
			SELECT COUNT(*) FROM subscriptions
			WHERE start_date < NOW() - INTERVAL '1 hour'
			""", Long.class);

		if (total == null || total == 0) {
			Map<String, Object> empty = new LinkedHashMap<>();
			empty.put("passed", false);
			empty.put("reason", "Nenhuma assinatura de seed encontrada. Execute POST /api/test/seed primeiro.");
			empty.put("hint", "Subs criadas via HTTP journey (start_date recente) são excluídas desta contagem.");
			return empty;
		}

		Long activeTotal = jdbcTemplate.queryForObject("""
			SELECT COUNT(*) FROM subscriptions
			WHERE status = 'ACTIVE' AND start_date < NOW() - INTERVAL '1 hour'
			""", Long.class);

		Long renewedClean = jdbcTemplate.queryForObject("""
			SELECT COUNT(*) FROM subscriptions
			WHERE status = 'ACTIVE' AND COALESCE(billing_attempts, 0) = 0
			  AND start_date < NOW() - INTERVAL '1 hour'
			""", Long.class);

		Long pendingRetry = jdbcTemplate.queryForObject("""
			SELECT COUNT(*) FROM subscriptions
			WHERE status = 'ACTIVE' AND COALESCE(billing_attempts, 0) > 0
			  AND start_date < NOW() - INTERVAL '1 hour'
			""", Long.class);

		Long suspended = jdbcTemplate.queryForObject("""
			SELECT COUNT(*) FROM subscriptions
			WHERE status = 'SUSPENDED' AND auto_renew = false
			  AND start_date < NOW() - INTERVAL '1 hour'
			""", Long.class);

		Long inactive = jdbcTemplate.queryForObject("""
			SELECT COUNT(*) FROM subscriptions
			WHERE status = 'INACTIVE'
			  AND start_date < NOW() - INTERVAL '1 hour'
			""", Long.class);

		Long canceled = jdbcTemplate.queryForObject("""
			SELECT COUNT(*) FROM subscriptions
			WHERE status = 'CANCELED'
			  AND start_date < NOW() - INTERVAL '1 hour'
			""", Long.class);

		Long outboxPending = jdbcTemplate.queryForObject("""
			SELECT COUNT(*) FROM event_publication WHERE completion_date IS NULL
			""", Long.class);

		double expectedActive    = total * 0.80;
		double expectedSuspended = total * 0.10;
		double expectedInactive  = total * 0.10;
		double tolerance = Math.max(total * 0.15, 1.0);

		boolean activeOk    = activeTotal  != null && Math.abs(activeTotal  - expectedActive)    <= tolerance;
		boolean suspendedOk = suspended    != null && Math.abs(suspended    - expectedSuspended) <= tolerance;
		boolean inactiveOk  = inactive     != null && Math.abs(inactive     - expectedInactive)  <= tolerance;
		boolean outboxOk    = outboxPending != null && outboxPending == 0;
		boolean allPassed   = activeOk && suspendedOk && inactiveOk && outboxOk;

		Map<String, Object> checks = new LinkedHashMap<>();

		Map<String, Object> activeCheck = new LinkedHashMap<>();
		activeCheck.put("actual", activeTotal);
		activeCheck.put("expected_approx", (long) expectedActive);
		activeCheck.put("passed", activeOk);
		activeCheck.put("detail_clean_renewal", renewedClean);
		activeCheck.put("detail_pending_retry", pendingRetry);
		activeCheck.put("note", "ACTIVE total (~80%): renovadas limpas + pendentes de retry. Ambas são comportamento correto.");
		checks.put("active", activeCheck);

		Map<String, Object> suspCheck = new LinkedHashMap<>();
		suspCheck.put("actual", suspended);
		suspCheck.put("expected_approx", (long) expectedSuspended);
		suspCheck.put("passed", suspendedOk);
		suspCheck.put("note", "SUSPENDED + autoRenew=false (~10%): token always_fail esgotou as 3 tentativas.");
		checks.put("suspended", suspCheck);

		Map<String, Object> inactCheck = new LinkedHashMap<>();
		inactCheck.put("actual", inactive);
		inactCheck.put("expected_approx", (long) expectedInactive);
		inactCheck.put("passed", inactiveOk);
		inactCheck.put("note", "INACTIVE (~10%): eram CANCELED com expiringDate ≤ hoje — scheduler marcou como inativas.");
		checks.put("becameInactive", inactCheck);

		if (canceled != null && canceled > 0) {
			Map<String, Object> canceledInfo = new LinkedHashMap<>();
			canceledInfo.put("count", canceled);
			canceledInfo.put("note", "CANCELED mas ainda não expiradas — o scheduler as marcará INACTIVE no próximo ciclo.");
			checks.put("canceledPendingExpiry_info", canceledInfo);
		}

		Map<String, Object> outboxCheck = new LinkedHashMap<>();
		outboxCheck.put("pending", outboxPending);
		outboxCheck.put("passed", outboxOk);
		outboxCheck.put("note", outboxOk
			? "Outbox limpo — todos os eventos foram processados pelo Kafka."
			: "Outbox ainda tem eventos pendentes — aguarde ~5s e re-execute.");
		checks.put("outboxClean", outboxCheck);

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("passed", allPassed);
		result.put("seededSubscriptions", total);
		result.put("note_scope", "Contagem inclui apenas assinaturas de seed (start_date > 1h atrás). Subs criadas via endpoint HTTP são excluídas.");
		result.put("checks", checks);
		result.put("checkedAt", LocalDateTime.now().toString());
		if (!allPassed) {
			result.put("hint", "Se o sweep ainda não rodou, execute POST /v1/admin/billing/trigger-sweep e aguarde ~5s antes de verificar novamente.");
		}
		return result;
	}

	/**
	 * Returns all billing history rows for a given subscription, newest first.
	 * Uses JdbcTemplate to avoid crossing the billing module boundary from the
	 * subscription module — the data lives in a shared table.
	 */
	public List<Map<String, Object>> getBillingHistory(java.util.UUID subscriptionId) {
		return jdbcTemplate.queryForList("""
			SELECT
			    bh.id,
			    bh.idempotency_key,
			    bh.status,
			    bh.gateway_transaction_id,
			    bh.processed_at,
			    bh.created_at
			FROM billing_history bh
			WHERE bh.subscription_id = ?
			ORDER BY bh.created_at DESC
			""", subscriptionId);
	}

	/**
	 * Returns a consolidated system-wide snapshot of subscriptions by status.
	 */
	public Map<String, Object> systemStatus() {
		List<Map<String, Object>> statusCounts = jdbcTemplate.queryForList("""
			SELECT status, COUNT(*) AS total
			FROM subscriptions
			GROUP BY status
			ORDER BY total DESC
			""");

		Long successfulRenewals = jdbcTemplate.queryForObject("""
			SELECT COUNT(*) FROM subscriptions
			WHERE status = 'ACTIVE'
			  AND expiring_date > CURRENT_DATE
			  AND billing_attempts = 0
			""", Long.class);

		Long pendingRetries = jdbcTemplate.queryForObject("""
			SELECT COUNT(*) FROM subscriptions
			WHERE status = 'ACTIVE'
			  AND billing_attempts > 0
			  AND next_retry_at > NOW()
			""", Long.class);

		Long stuckInFlight = jdbcTemplate.queryForObject("""
			SELECT COUNT(*) FROM subscriptions
			WHERE status = 'ACTIVE'
			  AND billing_attempts > 0
			  AND next_retry_at < NOW()
			""", Long.class);

		Long outboxPending = jdbcTemplate.queryForObject("""
			SELECT COUNT(*) FROM event_publication
			WHERE completion_date IS NULL
			""", Long.class);

		Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM subscriptions", Long.class);

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("totalSubscriptions", total);
		result.put("countsByStatus", statusCounts);
		result.put("successfulRenewals", successfulRenewals);
		result.put("pendingRetries", pendingRetries);
		result.put("stuckInFlight", stuckInFlight);
		result.put("outboxPending", outboxPending);
		result.put("checkedAt", LocalDateTime.now().toString());
		return result;
	}
}
