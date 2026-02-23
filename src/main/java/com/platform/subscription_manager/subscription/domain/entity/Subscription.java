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
import java.util.UUID;

@Entity
@Table(name = "subscriptions", indexes = {
	@Index(name = "idx_sub_renewal_sweep", columnList = "next_retry_at, expiring_date"),
	@Index(name = "idx_sub_api_read", columnList = "user_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Subscription {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "user_id", nullable = false, unique = true)
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

	/**
	 * Marks the subscription as user-cancelled.
	 * autoRenew is set to false; status intentionally stays ACTIVE so the user
	 * retains access until expiringDate. The scheduler moves CANCELED → INACTIVE.
	 */
	public void cancelRenewal() {
		this.autoRenew = false;
	}

	/**
	 * Sets status to CANCELED. Called by the service layer after cancelRenewal()
	 * so the domain method stays honest about its single concern.
	 */
	public void markAsCanceled() {
		this.status = SubscriptionStatus.CANCELED;
	}

	/**
	 * Reactivates a CANCELED or SUSPENDED subscription.
	 * Resets the billing cycle from now, clears failure state, and optionally
	 * updates the payment token (user may have changed their card).
	 */
	public void reactivate(Plan newPlan, String newPaymentToken) {
		this.plan = newPlan;
		this.paymentToken = newPaymentToken;
		this.status = SubscriptionStatus.ACTIVE;
		this.startDate = LocalDateTime.now();
		this.expiringDate = BillingCyclePolicy.calculateNextExpiration(this.startDate);
		this.autoRenew = true;
		this.billingAttempts = 0;
		this.lastBillingAttempt = null;
		this.nextRetryAt = this.expiringDate;
	}

	/** Rolls back a reactivation when the initial charge is declined. */
	public void restoreStatus(SubscriptionStatus previousStatus) {
		this.status = previousStatus;
		this.autoRenew = false;
		this.nextRetryAt = null;
	}

	/**
	 * Stamps an in-flight guard so the sweep does not re-dispatch this subscription
	 * while a Kafka/gateway round-trip is already in progress.
	 * Uses a short fixed window independent of billingAttempts — exponential backoff
	 * is computed by SubscriptionResultListener using the billing.retry.base-delay-minutes property.
	 *
	 * @param inFlightGuardMinutes how long to block re-dispatch (e.g. 5 minutes)
	 */
	public void markBillingAttempt(int inFlightGuardMinutes) {
		this.lastBillingAttempt = LocalDateTime.now();
		this.nextRetryAt = LocalDateTime.now().plusMinutes(inFlightGuardMinutes);
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