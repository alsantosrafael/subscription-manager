package com.platform.subscription_manager.subscription.domain;

import com.platform.subscription_manager.subscription.domain.entity.Subscription;
import com.platform.subscription_manager.shared.domain.Plan;
import com.platform.subscription_manager.shared.domain.SubscriptionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Subscription entity")
class SubscriptionTest {

	private static final UUID USER_ID = UUID.randomUUID();
	private static final String TOKEN  = "tok_test_abc123";

	@Nested
	@DisplayName("Subscription.create()")
	class Create {

		@Test
		@DisplayName("New subscription is ACTIVE with autoRenew=true and zero failures")
		void defaultStateAfterCreate() {
			Subscription sub = Subscription.create(USER_ID, Plan.BASICO, TOKEN);

			assertEquals(SubscriptionStatus.ACTIVE, sub.getStatus());
			assertTrue(sub.isAutoRenew());
			assertEquals(0, sub.getBillingAttempts());
		}

		@Test
		@DisplayName("userId, plan and paymentToken are stored correctly")
		void fieldsArePopulatedCorrectly() {
			Subscription sub = Subscription.create(USER_ID, Plan.PREMIUM, TOKEN);

			assertEquals(USER_ID,      sub.getUserId());
			assertEquals(Plan.PREMIUM, sub.getPlan());
			assertEquals(TOKEN,        sub.getPaymentToken());
		}

		@Test
		@DisplayName("startDate is set and expiringDate is strictly after startDate")
		void datesAreSetAndExpiringIsAfterStart() {
			Subscription sub = Subscription.create(USER_ID, Plan.FAMILIA, TOKEN);

			assertNotNull(sub.getStartDate());
			assertNotNull(sub.getExpiringDate());
			assertTrue(sub.getExpiringDate().isAfter(sub.getStartDate()),
				"expiringDate must be after startDate");
		}

		@Test
		@DisplayName("expiringDate is approximately one month after startDate")
		void expiringDateIsAboutOneMonthAhead() {
			Subscription sub = Subscription.create(USER_ID, Plan.BASICO, TOKEN);

			var expectedBase = sub.getStartDate().plusMonths(1);
			assertFalse(sub.getExpiringDate().isBefore(expectedBase),
				"expiringDate must not be before startDate + 1 month");
			assertFalse(sub.getExpiringDate().isAfter(expectedBase.plusHours(6)),
				"expiringDate must not exceed startDate + 1 month + 6 h jitter");
		}

	}

	@Nested
	@DisplayName("cancelRenewal()")
	class CancelRenewal {

		@Test
		@DisplayName("Sets autoRenew to false; status stays ACTIVE until scheduler expires it")
		void autoRenewBecomesFalseStatusStaysActive() {
			Subscription sub = Subscription.create(USER_ID, Plan.BASICO, TOKEN);

			sub.cancelRenewal();

			assertFalse(sub.isAutoRenew());
			assertEquals(SubscriptionStatus.ACTIVE, sub.getStatus());
		}

		@Test
		@DisplayName("Calling cancelRenewal removes autoRenew and allows user to enjoy subscription until the end of " +
			"cycle")
		void doubleCancelIsIdempotent() {
			Subscription sub = Subscription.create(USER_ID, Plan.BASICO, TOKEN);

			sub.cancelRenewal();
			sub.cancelRenewal();

			assertFalse(sub.isAutoRenew());
			assertEquals(SubscriptionStatus.ACTIVE, sub.getStatus());
		}
	}


	@Nested
	@DisplayName("applyRenewal()")
	class ApplyRenewal {

		@Test
		@DisplayName("Updates expiringDate, resets billingAttempts and sets nextRetryAt to nextDate")
		void appliesRenewalCorrectly() {
			Subscription sub = Subscription.create(USER_ID, Plan.BASICO, TOKEN);
			ReflectionTestUtils.setField(sub, "billingAttempts", 2);
			LocalDateTime nextDate = LocalDateTime.now().plusMonths(1);

			sub.applyRenewal(nextDate);

			assertEquals(SubscriptionStatus.ACTIVE, sub.getStatus());
			assertEquals(nextDate, sub.getExpiringDate());
			assertEquals(0, sub.getBillingAttempts());
			assertNull(sub.getLastBillingAttempt());
			assertEquals(nextDate, sub.getNextRetryAt());
		}
	}
}
