package com.platform.subscription_manager.billing.domain.entity;


import com.platform.subscription_manager.billing.domain.enums.BillingHistoryStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

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

	@Column(name = "processed_at", nullable = false)
	private LocalDateTime processedAt;

	public static BillingHistory createPending(UUID subscriptionId, String idempotencyKey) {
		BillingHistory history = new BillingHistory();
		history.id = UUID.randomUUID();
		history.subscriptionId = subscriptionId;
		history.idempotencyKey = idempotencyKey;
		history.status = BillingHistoryStatus.PENDING;
		history.processedAt = LocalDateTime.now();
		return history;
	}

	public void markAsSuccess(String gatewayTransactionId) {
		this.gatewayTransactionId = gatewayTransactionId;
		this.status = BillingHistoryStatus.SUCCESS;
		this.processedAt = LocalDateTime.now();
	}

	public void markAsFailed() {
		this.status = BillingHistoryStatus.FAILED;
		this.processedAt = LocalDateTime.now();
	}
}
