package com.platform.subscription_manager.shared.domain;

public enum SubscriptionStatus {
	ACTIVE,
	CANCELED,   // User chose to cancel; still has access until expiringDate
	SUSPENDED,  // Billing failed 3 times; access revoked immediately; awaiting reactivation
	INACTIVE    // Billing period ended (scheduler moves CANCELED → INACTIVE at expiringDate)
}
