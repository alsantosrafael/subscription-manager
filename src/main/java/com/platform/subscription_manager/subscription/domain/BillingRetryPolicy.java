package com.platform.subscription_manager.subscription.domain;

import com.platform.subscription_manager.subscription.domain.entity.Subscription;

import java.time.LocalDateTime;

/**
 * Domain policy for computing exponential backoff delays in billing retries.
 *
 * <p>The formula is: {@code delay = 2^attempts * baseDelayMinutes}.
 * This is the single authoritative location for this calculation — referenced by
 * {@link com.platform.subscription_manager.subscription.domain.entity.Subscription#applyBillingFailure(Subscription.BillingFailureResult, LocalDateTime)}
 * and the {@code SmokeTestSeederController} to ensure seeded state mirrors production behavior.
 */
public class BillingRetryPolicy {

    private BillingRetryPolicy() {}

    /**
     * Calculates the next retry timestamp using exponential backoff.
     *
     * @param attemptNumber  the attempt number <em>after</em> the failure (1-indexed)
     * @param baseDelayMinutes  the base window in minutes (configured via {@code billing.retry.base-delay-minutes})
     * @param from  the reference point from which to add the delay
     * @return the timestamp at which the next retry should be attempted
     */
    public static LocalDateTime calculateNextRetry(int attemptNumber, int baseDelayMinutes, LocalDateTime from) {
        long factor;
        if (attemptNumber < 0) {
            factor = 0L;
        } else if (attemptNumber >= 63) {
            factor = Long.MAX_VALUE;
        } else {
            factor = 1L << attemptNumber;
        }
        long delayMinutes = factor * baseDelayMinutes;
        return from.plusMinutes(delayMinutes);
    }
}
