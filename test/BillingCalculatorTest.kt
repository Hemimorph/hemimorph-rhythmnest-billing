package io.github.hemimogph

import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BillingCalculatorTest {
    private val day = RatePeriod(120, 1080, 100, -1)
    private val night = RatePeriod(1080, 1560, 200, -1)

    @Test
    fun `parses normal and extended times`() {
        assertEquals(120, parseRateTime("0200", 1440, "start"))
        assertEquals(1440, parseRateTime("2400", 1440, "start"))
        assertEquals(1560, parseRateTime("2600", 2880, "end"))
        assertEquals(2880, parseRateTime("4800", 2880, "end"))
        assertEquals("0200", formatRateTime(120))
        assertEquals("2600", formatRateTime(1560))

        assertFailsWith<RateValidationException> { parseRateTime("1860", 1440, "start") }
        assertFailsWith<RateValidationException> { parseRateTime("2500", 1440, "start") }
    }

    @Test
    fun `validates circular complete coverage`() {
        assertEquals(listOf(day, night), validateRatePeriods(listOf(night, day)))
        validateRatePeriods(listOf(RatePeriod(0, 1440, 100, -1)))
        validateRatePeriods(listOf(RatePeriod(1440, 2880, 100, 7900)))

        assertFailsWith<RateValidationException> {
            validateRatePeriods(listOf(RatePeriod(120, 1080, 100, -1)))
        }
        assertFailsWith<RateValidationException> {
            validateRatePeriods(
                listOf(
                    RatePeriod(0, 800, 100, -1),
                    RatePeriod(700, 1440, 100, -1),
                ),
            )
        }
        assertFailsWith<RateValidationException> {
            validateRatePeriods(listOf(RatePeriod(1080, 120, 100, -1)))
        }
    }

    @Test
    fun `does not split a cross-midnight period at midnight`() {
        val entered = utcMillis(2026, 1, 1, 23, 50)
        val calculated = utcMillis(2026, 1, 2, 0, 10)

        assertEquals(200, calculateBill(entered, calculated, listOf(day, night), ZoneOffset.UTC))
    }

    @Test
    fun `rounds each crossed rate period independently`() {
        val entered = utcMillis(2026, 1, 1, 17, 50)
        val calculated = utcMillis(2026, 1, 1, 18, 10)

        assertEquals(300, calculateBill(entered, calculated, listOf(day, night), ZoneOffset.UTC))

        assertEquals(
            BillCalculation(
                periodCharges = listOf(
                    PeriodCharge(entered, utcMillis(2026, 1, 1, 18, 0), 100),
                    PeriodCharge(utcMillis(2026, 1, 1, 18, 0), calculated, 200),
                ),
                totalAmount = 300,
            ),
            calculateBillBreakdown(entered, calculated, listOf(day, night), ZoneOffset.UTC),
        )
    }

    @Test
    fun `applies amount cap per period occurrence`() {
        val cappedNight = night.copy(amountPerHalfHour = 1500, maxAmount = 7900)
        val entered = utcMillis(2026, 1, 1, 18, 0)
        val calculated = utcMillis(2026, 1, 2, 2, 0)

        assertEquals(7900, calculateBill(entered, calculated, listOf(day, cappedNight), ZoneOffset.UTC))
    }

    @Test
    fun `rounds an exact half hour once and a partial excess twice`() {
        val fullDay = listOf(RatePeriod(0, 1440, 100, -1))
        val entered = utcMillis(2026, 1, 1, 10, 0)

        assertEquals(100, calculateBill(entered, entered + 30 * 60 * 1000, fullDay, ZoneOffset.UTC))
        assertEquals(200, calculateBill(entered, entered + 30 * 60 * 1000 + 1, fullDay, ZoneOffset.UTC))
    }

    private fun utcMillis(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
    ): Long = LocalDateTime.of(year, month, day, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()
}
