package com.platform.subscription_manager.subscription.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@DisplayName("BillingCyclePolicy.calculateNextExpiration")
class BillingCyclePolicyTest {


	@Nested
	@DisplayName("Mid-month dates — simple +1 month")
	class MidMonthDates {

		@Test
		@DisplayName("March 15 → April 15")
		void march15ToApril15() {
			LocalDateTime input  = LocalDateTime.of(2025, 3, 15, 10, 0);
			LocalDateTime result = BillingCyclePolicy.calculateNextExpiration(input);
			assertEquals(4,  result.getMonthValue(),  "month");
			assertEquals(15, result.getDayOfMonth(),  "day");
			assertEquals(2025, result.getYear(),      "year");
		}

		@Test
		@DisplayName("July 10 → August 10")
		void july10ToAugust10() {
			LocalDateTime input  = LocalDateTime.of(2025, 7, 10, 8, 30);
			LocalDateTime result = BillingCyclePolicy.calculateNextExpiration(input);
			assertEquals(8,  result.getMonthValue());
			assertEquals(10, result.getDayOfMonth());
		}

		@Test
		@DisplayName("October 1 → November 1")
		void october1ToNovember1() {
			LocalDateTime input  = LocalDateTime.of(2025, 10, 1, 0, 0);
			LocalDateTime result = BillingCyclePolicy.calculateNextExpiration(input);
			assertEquals(11, result.getMonthValue());
			assertEquals(1,  result.getDayOfMonth());
		}
	}

	@Nested
	@DisplayName("Year boundary")
	class YearBoundary {

		@Test
		@DisplayName("December 31 → January 31 of next year (last-day snap)")
		void december31ToJanuary31() {
			LocalDateTime input  = LocalDateTime.of(2025, 12, 31, 12, 0);
			LocalDateTime result = BillingCyclePolicy.calculateNextExpiration(input);
			assertEquals(2026, result.getYear(),         "year");
			assertEquals(1,    result.getMonthValue(),   "month");
			assertEquals(31,   result.getDayOfMonth(),   "day — Jan has 31 days");
		}

		@Test
		@DisplayName("December 15 → January 15 of next year")
		void december15ToJanuary15() {
			LocalDateTime input  = LocalDateTime.of(2025, 12, 15, 6, 0);
			LocalDateTime result = BillingCyclePolicy.calculateNextExpiration(input);
			assertEquals(2026, result.getYear());
			assertEquals(1,    result.getMonthValue());
			assertEquals(15,   result.getDayOfMonth());
		}
	}

	@Nested
	@DisplayName("January 31 edge cases")
	class January31 {

		@Test
		@DisplayName("Jan 31 on a non-leap year → Feb 28 (last day snap)")
		void jan31NonLeapYear() {
			// 2025 is not a leap year
			LocalDateTime input  = LocalDateTime.of(2025, 1, 31, 10, 0);
			LocalDateTime result = BillingCyclePolicy.calculateNextExpiration(input);
			assertEquals(2,  result.getMonthValue(), "month");
			assertEquals(28, result.getDayOfMonth(), "last day of Feb 2025");
		}

		@Test
		@DisplayName("Jan 31 on a leap year → Feb 29 (last day snap)")
		void jan31LeapYear() {
			// 2024 is a leap year
			LocalDateTime input  = LocalDateTime.of(2024, 1, 31, 10, 0);
			LocalDateTime result = BillingCyclePolicy.calculateNextExpiration(input);
			assertEquals(2,  result.getMonthValue(), "month");
			assertEquals(29, result.getDayOfMonth(), "last day of Feb 2024 (leap)");
		}
	}

	@Nested
	@DisplayName("February edge cases")
	class FebruaryEdgeCases {

		@Test
		@DisplayName("Feb 28 on a non-leap year (last day) → March 31 (last day snap)")
		void feb28NonLeapYearLastDay() {
			// Feb 28 is the last day in non-leap years
			LocalDateTime input  = LocalDateTime.of(2025, 2, 28, 9, 0);
			LocalDateTime result = BillingCyclePolicy.calculateNextExpiration(input);
			assertEquals(3,  result.getMonthValue(), "month");
			assertEquals(31, result.getDayOfMonth(), "last day of March");
		}

		@Test
		@DisplayName("Feb 28 on a leap year (NOT last day) → March 28 (no snap)")
		void feb28LeapYearNotLastDay() {
			// 2024 leap year: Feb 28 is NOT the last day (Feb 29 exists)
			LocalDateTime input  = LocalDateTime.of(2024, 2, 28, 9, 0);
			LocalDateTime result = BillingCyclePolicy.calculateNextExpiration(input);
			assertEquals(3,  result.getMonthValue(), "month");
			assertEquals(28, result.getDayOfMonth(), "no snap — Feb 28 was not last day in 2024");
		}

		@Test
		@DisplayName("Feb 29 on a leap year (last day) → March 31 (last day snap)")
		void feb29LeapYearLastDay() {
			// 2024 leap year: Feb 29 is the last day
			LocalDateTime input  = LocalDateTime.of(2024, 2, 29, 14, 0);
			LocalDateTime result = BillingCyclePolicy.calculateNextExpiration(input);
			assertEquals(3,  result.getMonthValue(), "month");
			assertEquals(31, result.getDayOfMonth(), "last day of March");
		}
	}

	@Nested
	@DisplayName("31-day month → 30-day next month (last-day snap)")
	class ThirtyOneDayToThirtyDay {

		@Test
		@DisplayName("March 31 → April 30")
		void march31ToApril30() {
			LocalDateTime input  = LocalDateTime.of(2025, 3, 31, 10, 0);
			LocalDateTime result = BillingCyclePolicy.calculateNextExpiration(input);
			assertEquals(4,  result.getMonthValue());
			assertEquals(30, result.getDayOfMonth());
		}

		@Test
		@DisplayName("May 31 → June 30")
		void may31ToJune30() {
			LocalDateTime input  = LocalDateTime.of(2025, 5, 31, 10, 0);
			LocalDateTime result = BillingCyclePolicy.calculateNextExpiration(input);
			assertEquals(6,  result.getMonthValue());
			assertEquals(30, result.getDayOfMonth());
		}

		@Test
		@DisplayName("August 31 → September 30")
		void august31ToSeptember30() {
			LocalDateTime input  = LocalDateTime.of(2025, 8, 31, 10, 0);
			LocalDateTime result = BillingCyclePolicy.calculateNextExpiration(input);
			assertEquals(9,  result.getMonthValue());
			assertEquals(30, result.getDayOfMonth());
		}

		@Test
		@DisplayName("October 31 → November 30")
		void october31ToNovember30() {
			LocalDateTime input  = LocalDateTime.of(2025, 10, 31, 10, 0);
			LocalDateTime result = BillingCyclePolicy.calculateNextExpiration(input);
			assertEquals(11, result.getMonthValue());
			assertEquals(30, result.getDayOfMonth());
		}
	}

	@Nested
	@DisplayName("30-day month (last day) → 31-day next month (last-day snap)")
	class ThirtyDayToThirtyOneDay {

		@Test
		@DisplayName("April 30 → May 31")
		void april30ToMay31() {
			LocalDateTime input  = LocalDateTime.of(2025, 4, 30, 10, 0);
			LocalDateTime result = BillingCyclePolicy.calculateNextExpiration(input);
			assertEquals(5,  result.getMonthValue());
			assertEquals(31, result.getDayOfMonth());
		}

		@Test
		@DisplayName("June 30 → July 31")
		void june30ToJuly31() {
			LocalDateTime input  = LocalDateTime.of(2025, 6, 30, 10, 0);
			LocalDateTime result = BillingCyclePolicy.calculateNextExpiration(input);
			assertEquals(7,  result.getMonthValue());
			assertEquals(31, result.getDayOfMonth());
		}

		@Test
		@DisplayName("September 30 → October 31")
		void september30ToOctober31() {
			LocalDateTime input  = LocalDateTime.of(2025, 9, 30, 10, 0);
			LocalDateTime result = BillingCyclePolicy.calculateNextExpiration(input);
			assertEquals(10, result.getMonthValue());
			assertEquals(31, result.getDayOfMonth());
		}

		@Test
		@DisplayName("November 30 → December 31")
		void november30ToDecember31() {
			LocalDateTime input  = LocalDateTime.of(2025, 11, 30, 10, 0);
			LocalDateTime result = BillingCyclePolicy.calculateNextExpiration(input);
			assertEquals(12, result.getMonthValue());
			assertEquals(31, result.getDayOfMonth());
		}
	}


	@Nested
	@DisplayName("Time-of-day preservation")
	class TimeOfDayPreservation {

		@Test
		@DisplayName("Minutes, seconds and nanoseconds are preserved after jitter")
		void minutesSecondsNanosPreserved() {
			LocalDateTime input  = LocalDateTime.of(2025, 5, 10, 14, 45, 30, 123456789);
			LocalDateTime result = BillingCyclePolicy.calculateNextExpiration(input);

			assertEquals(45,        result.getMinute(), "minutes must be unchanged");
			assertEquals(30,        result.getSecond(), "seconds must be unchanged");
			assertEquals(123456000, result.getNano(),   "nanos must be truncated to micros (last 3 digits zeroed)");
		}

		@Test
		@DisplayName("Result year is always +1 month from input (no drift)")
		void noCalendarDrift() {
			LocalDateTime input  = LocalDateTime.of(2025, 1, 1, 0, 0);
			LocalDateTime result = BillingCyclePolicy.calculateNextExpiration(input);
			assertEquals(2025, result.getYear());
			assertEquals(2,    result.getMonthValue());
			assertEquals(1,    result.getDayOfMonth());
		}
	}

	@Nested
	@DisplayName("Leap year cycle correctness")
	class LeapYearCycle {

		@Test
		@DisplayName("Jan 31 2096 (non-leap) → Feb 28")
		void jan31Year2096NonLeap() {
			LocalDateTime input  = LocalDateTime.of(2100, 1, 31, 0, 0);
			LocalDateTime result = BillingCyclePolicy.calculateNextExpiration(input);
			assertEquals(2,  result.getMonthValue());
			assertEquals(28, result.getDayOfMonth(), "2100 is not a leap year → Feb has 28 days");
			assertFalse(YearMonth.of(2100, 2).isLeapYear(), "sanity: 2100 is not a leap year");
		}

		@Test
		@DisplayName("Jan 31 2000 (leap year divisible by 400) → Feb 29")
		void jan31Year2000Leap() {
			// 2000 IS a leap year (divisible by 400)
			LocalDateTime input  = LocalDateTime.of(2000, 1, 31, 0, 0);
			LocalDateTime result = BillingCyclePolicy.calculateNextExpiration(input);
			assertEquals(2,  result.getMonthValue());
			assertEquals(29, result.getDayOfMonth(), "2000 is a leap year → Feb has 29 days");
		}
	}

}