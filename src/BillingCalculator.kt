package io.github.hemimogph

import java.time.Instant
import java.time.LocalDate
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

class RateValidationException(message: String) : IllegalArgumentException(message)

class RateConfigurationNotFoundException : IllegalStateException("Rate configuration not found")

class BillingOverflowException : ArithmeticException("Bill exceeds the supported integer range")

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
): Long {
    require(enteredAtMs >= 0) { "enteredAtMs must not be negative" }
    require(calculatedAtMs >= enteredAtMs) { "calculatedAtMs must not precede enteredAtMs" }
    if (calculatedAtMs == enteredAtMs) return 0
    val validatedPeriods = validateRatePeriods(periods)

    val firstDate = Instant.ofEpochMilli(enteredAtMs).atZone(zoneId).toLocalDate().minusDays(1)
    val lastDate = Instant.ofEpochMilli(calculatedAtMs).atZone(zoneId).toLocalDate()
    var date = firstDate
    var total = 0L
    while (!date.isAfter(lastDate)) {
        validatedPeriods.forEach { period ->
            val durationMinutes = period.endMinute - period.startMinute
            val normalizedStart = period.startMinute % MINUTES_PER_DAY
            val localStart = date.atStartOfDay().plusMinutes(normalizedStart.toLong())
            val localEnd = localStart.plusMinutes(durationMinutes.toLong())
            val periodStartMs = localStart.atZone(zoneId).toInstant().toEpochMilli()
            val periodEndMs = localEnd.atZone(zoneId).toInstant().toEpochMilli()
            val overlapStart = maxOf(enteredAtMs, periodStartMs)
            val overlapEnd = minOf(calculatedAtMs, periodEndMs)
            if (overlapEnd > overlapStart) {
                val elapsed = overlapEnd - overlapStart
                val blocks = (elapsed + HALF_HOUR_MILLIS - 1) / HALF_HOUR_MILLIS
                val rawAmount = try {
                    Math.multiplyExact(blocks, period.amountPerHalfHour)
                } catch (_: ArithmeticException) {
                    throw BillingOverflowException()
                }
                val periodAmount = if (period.maxAmount == -1L) {
                    rawAmount
                } else {
                    minOf(rawAmount, period.maxAmount)
                }
                total = try {
                    Math.addExact(total, periodAmount)
                } catch (_: ArithmeticException) {
                    throw BillingOverflowException()
                }
            }
        }
        date = date.plusDays(1)
    }
    return total
}
