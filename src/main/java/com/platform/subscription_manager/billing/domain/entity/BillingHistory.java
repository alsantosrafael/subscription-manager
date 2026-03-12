package com.platform.subscription_manager.billing.domain.entity;


import com.platform.subscription_manager.shared.domain.BillingHistoryStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Audit record for every billing attempt (initial charge + Kafka-driven renewals).
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>A PENDING row is inserted via {@code insertIfNotExist} <em>before</em> the gateway
 *       call. {@code processed_at} is null at this stage.</li>
 *   <li>Once the gateway responds, {@code updateResult} stamps {@code processed_at} and
 *       sets the final status (SUCCESS or FAILED).</li>
 * </ol>
 *
 * <p>The {@code idempotency_key} unique constraint prevents double-charging on retries.
 */
@Entity
@Table(name = "billing_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BillingHistory {

	@Id
	private UUID id;

	@Column(name = "subscription_id", nullable = false)
	private UUID subscriptionId;

	@Column(name = "idempotency_key", nullable = false, unique = true)
	private String idempotencyKey;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private BillingHistoryStatus status;

	@Column(name = "gateway_transaction_id")
	private String gatewayTransactionId;

	@Column(name = "processed_at")
	private LocalDateTime processedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	@CreationTimestamp
	private LocalDateTime createdAt;
}
