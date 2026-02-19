package com.platform.subscription_manager.shared.utils;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.concurrent.ThreadLocalRandom;

public class DateHelper {

	/**
	 * Generates the next expiring date based on a previous date, adding exactly
	 * one calendar month while correctly handling:
	 * <ul>
	 *   <li>Leap years (e.g. Jan 31 on a leap year → Feb 29)</li>
	 *   <li>February's 28 / 29 days (e.g. Jan 31 → Feb 28 on a non-leap year)</li>
	 *   <li>Months with 30 vs 31 days (e.g. Mar 31 → Apr 30)</li>
	 * </ul>
	 *
	 * <p>A random jitter of ±0–6 hours is added to spread renewal processing
	 * load and avoid thundering-herd effects.</p>
	 *
	 * @param previousDate the reference date from which the next expiry is calculated
	 * @return a new {@link LocalDateTime} one calendar month after {@code previousDate},
	 *         with a small random hour jitter applied
	 */
	public static LocalDateTime nextExpiringDate(LocalDateTime previousDate) {
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

		long jitterHours = ThreadLocalRandom.current().nextLong(0, 7); // [0, 6]
		return base.plusHours(jitterHours);
	}
}
