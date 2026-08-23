package io.github.hemimogph

import java.time.Instant
import java.time.ZoneId
import java.util.Locale

private const val MINUTES_PER_DAY = 24 * 60
private const val HALF_HOUR_MILLIS = 30L * 60L * 1000L

data class RatePeriod(
    val startMinute: Int,
    val endMinute: Int,
    val amountPerHalfHour: Long,
    val maxAmount: Long,
)

data class PeriodCharge(
    val startedAtMs: Long,
    val endedAtMs: Long,
    val amount: Long,
)

data class BillCalculation(
    val periodCharges: List<PeriodCharge>,
    val totalAmount: Long,
)

class RateValidationException(message: String) : IllegalArgumentException(message)

class RateConfigurationNotFoundException : IllegalStateException("Rate configuration not found")

class BillingOverflowException : ArithmeticException("Bill exceeds the supported integer range")

private data class RateOccurrence(
    val period: RatePeriod,
    val endedAtMs: Long,
)

fun parseRateTime(value: String, maximumMinute: Int, fieldName: String): Int {
    if (value.length != 4 || value.any { !it.isDigit() }) {
        throw RateValidationException("$fieldName must be a four-digit time")
    }
    val hour = value.substring(0, 2).toInt()
    val minute = value.substring(2, 4).toInt()
    if (minute !in 0..59) throw RateValidationException("$fieldName has an invalid minute")
    val total = hour * 60 + minute
    if (total !in 0..maximumMinute) {
        throw RateValidationException("$fieldName is outside the supported range")
    }
    return total
}

fun formatRateTime(minute: Int): String = String.format(Locale.ROOT, "%02d%02d", minute / 60, minute % 60)

fun validateRatePeriods(periods: List<RatePeriod>): List<RatePeriod> {
    if (periods.isEmpty()) throw RateValidationException("At least one rate period is required")

    data class Coverage(val start: Int, val end: Int)

    val coverage = buildList {
        periods.forEach { period ->
            if (period.startMinute !in 0..MINUTES_PER_DAY) {
                throw RateValidationException("Rate start is outside 0000-2400")
            }
            if (period.endMinute !in 0..(MINUTES_PER_DAY * 2)) {
                throw RateValidationException("Rate end is outside 0000-4800")
            }
            val duration = period.endMinute - period.startMinute
            if (duration !in 1..MINUTES_PER_DAY) {
                throw RateValidationException("Each rate period must be longer than zero and at most 24 hours")
            }
            if (period.amountPerHalfHour < 0) {
                throw RateValidationException("amountPerHalfHour must not be negative")
            }
            if (period.maxAmount < -1) {
                throw RateValidationException("maxAmount must be -1 or non-negative")
            }

            val normalizedStart = period.startMinute % MINUTES_PER_DAY
            if (duration == MINUTES_PER_DAY) {
                add(Coverage(0, MINUTES_PER_DAY))
            } else {
                val normalizedEnd = normalizedStart + duration
                if (normalizedEnd <= MINUTES_PER_DAY) {
                    add(Coverage(normalizedStart, normalizedEnd))
                } else {
                    add(Coverage(normalizedStart, MINUTES_PER_DAY))
                    add(Coverage(0, normalizedEnd - MINUTES_PER_DAY))
                }
            }
        }
    }.sortedBy(Coverage::start)

    var coveredUntil = 0
    coverage.forEach { segment ->
        if (segment.start > coveredUntil) throw RateValidationException("Rate periods contain a gap")
        if (segment.start < coveredUntil) throw RateValidationException("Rate periods overlap")
        coveredUntil = segment.end
    }
    if (coveredUntil != MINUTES_PER_DAY) {
        throw RateValidationException("Rate periods must cover a complete 24-hour day")
    }
    return periods.sortedBy { it.startMinute % MINUTES_PER_DAY }
}

fun calculateBill(
    enteredAtMs: Long,
    calculatedAtMs: Long,
    periods: List<RatePeriod>,
    zoneId: ZoneId,
): Long = calculateBillBreakdown(enteredAtMs, calculatedAtMs, periods, zoneId).totalAmount

fun calculateBillBreakdown(
    enteredAtMs: Long,
    calculatedAtMs: Long,
    periods: List<RatePeriod>,
    zoneId: ZoneId,
): BillCalculation {
    require(enteredAtMs >= 0) { "enteredAtMs must not be negative" }
    require(calculatedAtMs >= enteredAtMs) { "calculatedAtMs must not precede enteredAtMs" }
    if (calculatedAtMs == enteredAtMs) return BillCalculation(emptyList(), 0)
    val validatedPeriods = validateRatePeriods(periods)

    val stayDuration = calculatedAtMs - enteredAtMs
    val completeBillingBlocks = stayDuration / HALF_HOUR_MILLIS
    var segmentStart = enteredAtMs
    var total = 0L
    val charges = mutableListOf<PeriodCharge>()
    while (segmentStart < calculatedAtMs) {
        val occurrence = findRateOccurrence(segmentStart, validatedPeriods, zoneId)
        // A rate change inside a half-hour unit takes effect at the next unit
        // boundary measured from entry, so one unit is never charged twice.
        val boundaryOffset = occurrence.endedAtMs - enteredAtMs
        val blocksUntilBoundary = ceilingHalfHours(boundaryOffset)
        val segmentEnd = if (blocksUntilBoundary <= completeBillingBlocks) {
            enteredAtMs + blocksUntilBoundary * HALF_HOUR_MILLIS
        } else {
            calculatedAtMs
        }
        check(segmentEnd > segmentStart) { "Rate period did not advance billing calculation" }

        val blocks = ceilingHalfHours(segmentEnd - segmentStart)
        val rawAmount = try {
            Math.multiplyExact(blocks, occurrence.period.amountPerHalfHour)
        } catch (_: ArithmeticException) {
            throw BillingOverflowException()
        }
        val periodAmount = if (occurrence.period.maxAmount == -1L) {
            rawAmount
        } else {
            minOf(rawAmount, occurrence.period.maxAmount)
        }
        total = try {
            Math.addExact(total, periodAmount)
        } catch (_: ArithmeticException) {
            throw BillingOverflowException()
        }
        charges += PeriodCharge(segmentStart, segmentEnd, periodAmount)
        segmentStart = segmentEnd
    }
    return BillCalculation(charges, total)
}

private fun findRateOccurrence(
    atMs: Long,
    periods: List<RatePeriod>,
    zoneId: ZoneId,
): RateOccurrence {
    val localDate = Instant.ofEpochMilli(atMs).atZone(zoneId).toLocalDate()
    for (date in listOf(localDate.minusDays(1), localDate)) {
        periods.forEach { period ->
            val durationMinutes = period.endMinute - period.startMinute
            val normalizedStart = period.startMinute % MINUTES_PER_DAY
            val localStart = date.atStartOfDay().plusMinutes(normalizedStart.toLong())
            val localEnd = localStart.plusMinutes(durationMinutes.toLong())
            val periodStartMs = localStart.atZone(zoneId).toInstant().toEpochMilli()
            val periodEndMs = localEnd.atZone(zoneId).toInstant().toEpochMilli()
            if (atMs >= periodStartMs && atMs < periodEndMs) {
                return RateOccurrence(period, periodEndMs)
            }
        }
    }
    error("Validated rate periods do not cover the billing instant")
}

private fun ceilingHalfHours(durationMs: Long): Long {
    require(durationMs > 0) { "durationMs must be positive" }
    val completeBlocks = durationMs / HALF_HOUR_MILLIS
    return completeBlocks + if (durationMs % HALF_HOUR_MILLIS == 0L) 0 else 1
}
