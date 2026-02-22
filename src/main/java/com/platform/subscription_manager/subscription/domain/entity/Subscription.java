package com.platform.subscription_manager.subscription.domain.entity;

import com.platform.subscription_manager.subscription.domain.BillingCyclePolicy;
import com.platform.subscription_manager.shared.domain.Plan;
import com.platform.subscription_manager.shared.domain.SubscriptionStatus;
import com.platform.subscription_manager.shared.config.PaymentTokenConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
import java.util.Random;
import java.util.UUID;

@Entity
@Table(name = "subscriptions", indexes = {
	@Index(name = "idx_sub_renewal_sweep", columnList = "status, expiring_date, last_billing_attempt"),
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

	@Convert(converter = PaymentTokenConverter.class)
	@Column(name = "payment_token", nullable = false)
	private String paymentToken;

	@Column(name = "start_date", nullable = false, updatable = false)
	private LocalDateTime startDate;

	@Column(name = "expiring_date", nullable = false)
	private LocalDateTime expiringDate;

	@Column(name = "auto_renew", nullable = false)
	private boolean autoRenew;

	@Column(name = "billing_attempts", nullable = false)
	private int billingAttempts;

	@Column(name = "next_retry_at")
	private LocalDateTime nextRetryAt;

	@Column(name = "last_billing_attempt")
	private LocalDateTime lastBillingAttempt;

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
		sub.billingAttempts = 0;
		sub.nextRetryAt = sub.expiringDate;
		return sub;
	}

	public void cancelRenewal() { // scheduler will cancel on the day it should be renewed
		this.autoRenew = false;
	}

	public void registerPaymentFailure(int maxAttempts, int baseDelayMinutes) {
		if (this.billingAttempts >= maxAttempts) {
			return; // already at or beyond threshold — do not increment further
		}

		this.billingAttempts++;
		this.lastBillingAttempt = LocalDateTime.now();

		if (this.billingAttempts >= maxAttempts) {
			this.status = SubscriptionStatus.SUSPENDED;
			this.autoRenew = false;
			this.nextRetryAt = null;
		} else {
			long delay = (long) Math.pow(2, this.billingAttempts) * baseDelayMinutes;
			this.nextRetryAt = LocalDateTime.now()
				.plusMinutes(delay)
				.plusSeconds(new Random().nextInt(60)); // Jitter
		}
	}

	public void renew() {
		this.expiringDate = BillingCyclePolicy.calculateNextExpiration(this.expiringDate);
		this.billingAttempts = 0;
		this.status = SubscriptionStatus.ACTIVE;
		this.autoRenew = true;
	}
	public void markBillingAttempt(int baseDelayMinutes) {
		// Only stamps timing — billingAttempts counter is owned by registerPaymentFailure
		this.lastBillingAttempt = LocalDateTime.now();
		long minutesToAdd = (long) Math.pow(2, this.billingAttempts) * baseDelayMinutes;
		int jitter = new Random().nextInt(60);

		this.nextRetryAt = LocalDateTime.now().plusMinutes(minutesToAdd).plusSeconds(jitter);
	}

	public void registerBillingSuccess(LocalDateTime newExpiringDate) {
		this.status = SubscriptionStatus.ACTIVE;
		this.expiringDate = newExpiringDate;
		this.billingAttempts = 0;
		this.lastBillingAttempt = null;
		this.nextRetryAt = null;
	}

	/**
	 * Mirrors exactly what renewSubscriptionAtomic writes to the DB.
	 * Call this after a successful atomic update to keep the in-memory object
	 * consistent without an extra SELECT.
	 */
	public void applyRenewal(LocalDateTime nextDate) {
		this.expiringDate = nextDate;
		this.status = SubscriptionStatus.ACTIVE;
		this.billingAttempts = 0;
		this.lastBillingAttempt = null;
		this.nextRetryAt = nextDate;
	}
}