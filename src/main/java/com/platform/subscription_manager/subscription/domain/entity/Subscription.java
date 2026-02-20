package com.platform.subscription_manager.subscription.domain.entity;

import com.platform.subscription_manager.subscription.domain.BillingCyclePolicy;
import com.platform.subscription_manager.subscription.domain.enums.Plan;
import com.platform.subscription_manager.subscription.domain.enums.SubscriptionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "subscriptions", indexes = {
		@Index(name = "idx_sub_renewal_sweep", columnList = "status, expiring_date"),
		@Index(name = "idx_sub_api_read", columnList = "user_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Subscription {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Plan plan;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private SubscriptionStatus status;

	@Column(name = "payment_token", nullable = false)
	private String paymentToken;

	@Column(name = "start_date", nullable = false, updatable = false)
	private LocalDateTime startDate;

	@Column(name = "expiring_date", nullable = false)
	private LocalDateTime expiringDate;

	@Column(name = "auto_renew", nullable = false)
	private boolean autoRenew;

	@Column(name = "billing_failed_attempts", nullable = false)
	private int billingFailedAttempts;

	@Version
	private Long version;

	public static Subscription create(UUID userId, Plan plan, String paymentToken) {
		Subscription sub = new Subscription();
		sub.userId = userId;
		sub.plan = plan;
		sub.paymentToken = paymentToken;
		sub.status = SubscriptionStatus.ACTIVE;
		sub.startDate = LocalDateTime.now();
		sub.expiringDate = BillingCyclePolicy.calculateNextExpiration(sub.startDate);
		sub.autoRenew = true;
		sub.billingFailedAttempts = 0;
		return sub;
	}

	public void cancelRenewal() {
		this.autoRenew = false;
	}

	public void suspendRenewal() {
		this.autoRenew = false;
		this.status = SubscriptionStatus.SUSPENDED;
	}

	public void recordPaymentFailure() {
		this.billingFailedAttempts++;
		if (this.billingFailedAttempts >= 3) {
			this.status = SubscriptionStatus.SUSPENDED;
			this.autoRenew = false;
		}
	}

	public void renew() {
		this.expiringDate = BillingCyclePolicy.calculateNextExpiration(this.expiringDate);
		this.billingFailedAttempts = 0;
		this.status = SubscriptionStatus.ACTIVE;
	}
}