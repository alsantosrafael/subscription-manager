package com.platform.subscription_manager.subscription.domain;

import com.platform.subscription_manager.subscription.domain.entity.Subscription;
import com.platform.subscription_manager.subscription.domain.enums.Plan;
import com.platform.subscription_manager.subscription.domain.enums.SubscriptionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
			assertEquals(0, sub.getBillingFailedAttempts());
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
		@DisplayName("Sets autoRenew to false; status stays ACTIVE")
		void autoRenewBecomesFalseStatusUnchanged() {
			Subscription sub = Subscription.create(USER_ID, Plan.BASICO, TOKEN);

			sub.cancelRenewal();

			assertFalse(sub.isAutoRenew(), "autoRenew must be false after cancel");
			assertEquals(SubscriptionStatus.ACTIVE, sub.getStatus(),
				"status must remain ACTIVE — cancel only stops renewal");
		}

		@Test
		@DisplayName("Calling cancelRenewal twice is idempotent")
		void doubleCancelIsIdempotent() {
			Subscription sub = Subscription.create(USER_ID, Plan.BASICO, TOKEN);

			sub.cancelRenewal();
			sub.cancelRenewal();

			assertFalse(sub.isAutoRenew());
			assertEquals(SubscriptionStatus.ACTIVE, sub.getStatus());
		}
	}

	@Nested
	@DisplayName("suspendRenewal()")
	class SuspendRenewal {

		@Test
		@DisplayName("Sets status to SUSPENDED and autoRenew to false")
		void suspendSetsStatusAndDisablesAutoRenew() {
			Subscription sub = Subscription.create(USER_ID, Plan.PREMIUM, TOKEN);

			sub.suspendRenewal();

			assertEquals(SubscriptionStatus.SUSPENDED, sub.getStatus());
			assertFalse(sub.isAutoRenew());
		}

		@Test
		@DisplayName("Calling suspendRenewal twice is idempotent")
		void doubleSuspendIsIdempotent() {
			Subscription sub = Subscription.create(USER_ID, Plan.PREMIUM, TOKEN);

			sub.suspendRenewal();
			sub.suspendRenewal();

			assertEquals(SubscriptionStatus.SUSPENDED, sub.getStatus());
			assertFalse(sub.isAutoRenew());
		}
	}

	@Nested
	@DisplayName("recordPaymentFailure()")
	class RecordPaymentFailure {

		@Test
		@DisplayName("First failure increments counter; status stays ACTIVE, autoRenew stays true")
		void firstFailureIncrementsCounterOnly() {
			Subscription sub = Subscription.create(USER_ID, Plan.BASICO, TOKEN);

			sub.recordPaymentFailure();

			assertEquals(1, sub.getBillingFailedAttempts());
			assertEquals(SubscriptionStatus.ACTIVE, sub.getStatus());
			assertTrue(sub.isAutoRenew());
		}

		@Test
		@DisplayName("Second failure increments counter; still ACTIVE")
		void secondFailureStillActive() {
			Subscription sub = Subscription.create(USER_ID, Plan.BASICO, TOKEN);

			sub.recordPaymentFailure();
			sub.recordPaymentFailure();

			assertEquals(2, sub.getBillingFailedAttempts());
			assertEquals(SubscriptionStatus.ACTIVE, sub.getStatus());
		}

		@Test
		@DisplayName("Third failure suspends the subscription and stops autoRenew")
		void thirdFailureSuspendsSubscription() {
			Subscription sub = Subscription.create(USER_ID, Plan.BASICO, TOKEN);

			sub.recordPaymentFailure();
			sub.recordPaymentFailure();
			sub.recordPaymentFailure();

			assertEquals(3, sub.getBillingFailedAttempts());
			assertEquals(SubscriptionStatus.SUSPENDED, sub.getStatus());
			assertFalse(sub.isAutoRenew());
		}

		@Test
		@DisplayName("Fourth failure keeps SUSPENDED and keeps incrementing the counter")
		void furtherFailuresKeepSuspended() {
			Subscription sub = Subscription.create(USER_ID, Plan.BASICO, TOKEN);

			sub.recordPaymentFailure();
			sub.recordPaymentFailure();
			sub.recordPaymentFailure();
			sub.recordPaymentFailure();

			assertEquals(4, sub.getBillingFailedAttempts());
			assertEquals(SubscriptionStatus.SUSPENDED, sub.getStatus());
			assertFalse(sub.isAutoRenew());
		}
	}

	@Nested
	@DisplayName("renew()")
	class Renew {

		@Test
		@DisplayName("expiringDate advances by one month from the previous expiringDate")
		void expiringDateAdvancesOneMonth() {
			Subscription sub = Subscription.create(USER_ID, Plan.BASICO, TOKEN);
			var previousExpiring = sub.getExpiringDate();

			sub.renew();

			var expectedBase = previousExpiring.plusMonths(1);
			assertFalse(sub.getExpiringDate().isBefore(expectedBase),
				"renewed expiringDate must not be before previous + 1 month");
			assertFalse(sub.getExpiringDate().isAfter(expectedBase.plusHours(6)),
				"renewed expiringDate must not exceed previous + 1 month + 6 h jitter");
		}

		@Test
		@DisplayName("billingFailedAttempts resets to 0")
		void failedAttemptsResetOnRenewal() {
			Subscription sub = Subscription.create(USER_ID, Plan.BASICO, TOKEN);
			sub.recordPaymentFailure();
			sub.recordPaymentFailure();

			sub.renew();

			assertEquals(0, sub.getBillingFailedAttempts());
		}

		@Test
		@DisplayName("Status is ACTIVE after renewal")
		void statusIsActiveAfterRenewal() {
			Subscription sub = Subscription.create(USER_ID, Plan.BASICO, TOKEN);
			sub.suspendRenewal();

			sub.renew();

			assertEquals(SubscriptionStatus.ACTIVE, sub.getStatus());
		}

		@Test
		@DisplayName("autoRenew is restored to true after renewal")
		void autoRenewRestoredAfterRenewal() {
			Subscription sub = Subscription.create(USER_ID, Plan.BASICO, TOKEN);
			sub.cancelRenewal();

			sub.renew();

			assertTrue(sub.isAutoRenew());
		}

		@Test
		@DisplayName("Two consecutive renewals advance the date by two months")
		void multipleRenewalsAdvanceDateCorrectly() {
			Subscription sub = Subscription.create(USER_ID, Plan.PREMIUM, TOKEN);
			var startExpiring = sub.getExpiringDate();

			sub.renew();
			sub.renew();

			var expectedBase = startExpiring.plusMonths(2);
			assertFalse(sub.getExpiringDate().isBefore(expectedBase),
				"after 2 renewals expiringDate must be at least 2 months ahead");
			assertFalse(sub.getExpiringDate().isAfter(expectedBase.plusHours(12)),
				"after 2 renewals expiringDate must not be more than 2 months + 12 h ahead");
		}
	}
}
