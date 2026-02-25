package com.platform.subscription_manager.subscription.domain;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

/**
 * Domain Policy responsible for calculating the next billing cycle date.
 */
public class BillingCyclePolicy {

	/**
	 * Generates the next expiring date based on a previous date, adding exactly
	 * one calendar month while correctly handling:
	 * <ul>
	 *   <li>Leap years (e.g. Jan 31 on a leap year → Feb 29)</li>
	 *   <li>February's 28 / 29 days (e.g. Jan 31 → Feb 28 on a non-leap year)</li>
	 *   <li>Months with 30 vs 31 days (e.g. Mar 31 → Apr 30)</li>
	 * </ul>
	 *
	 * <p>The result is truncated to microseconds to match PostgreSQL {@code TIMESTAMP}
	 * precision, ensuring the value roundtrips through the DB without loss and
	 * remains safe to use as a CAS guard in atomic repository queries.</p>
	 *
	 * @param previousDate the reference date from which the next expiry is calculated
	 * @return a new {@link LocalDateTime} one calendar month after {@code previousDate},
	 *         truncated to microsecond precision
	 */
	public static LocalDateTime calculateNextExpiration(LocalDateTime previousDate) {
		int year  = previousDate.getYear();
		int month = previousDate.getMonthValue();
		int day   = previousDate.getDayOfMonth();

		boolean wasLastDayOfMonth = day == YearMonth.of(year, month).lengthOfMonth();
		LocalDateTime base = previousDate.plusMonths(1);

		if (wasLastDayOfMonth) {
			int targetYear  = base.getYear();
			int targetMonth = base.getMonthValue();
			int lastDay     = YearMonth.of(targetYear, targetMonth).lengthOfMonth();
			base = base.withDayOfMonth(lastDay);
		}

		return base.truncatedTo(ChronoUnit.MICROS);
	}
}
