package io.github.hemimogph

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

data class Debt(
    val userId: String,
    val balance: Long,
)

suspend fun DatabaseQueue.rates(): List<RatePeriod> = execute("rates") {
    loadRates()
}

suspend fun DatabaseQueue.replaceRates(
    periods: List<RatePeriod>,
    operatorId: String,
    note: String,
    requestedAtMs: Long,
): MutationResult {
    val validated = validateRatePeriods(periods)
    return write(
        "replaceRates",
        mapOf(
            "periods" to validated,
            "operatorId" to operatorId,
            "note" to note,
            "requestedAtMs" to requestedAtMs,
        ),
    ) {
        if (!isAdministrator(operatorId)) {
            insertOperation(
                requestedAtMs,
                operatorId,
                "rates",
                OperationType.RATE_UPDATE,
                false,
                deniedNote("NOT_ADMIN", note),
            )
            return@write MutationResult.DENIED_PERMISSION
        }
        if (loadRates() == validated) return@write MutationResult.NO_CHANGE

        Rates.deleteAll()
        validated.forEach { period ->
            Rates.insert {
                it[startMinute] = period.startMinute
                it[endMinute] = period.endMinute
                it[amountPerHalfHour] = period.amountPerHalfHour
                it[maxAmount] = period.maxAmount
            }
        }
        insertOperation(
            requestedAtMs,
            operatorId,
            "rates",
            OperationType.RATE_UPDATE,
            true,
            note,
        )
        MutationResult.APPLIED
    }
}

suspend fun DatabaseQueue.debts(
    operatorId: String,
    requestedAtMs: Long,
): AccessResult<List<Debt>> = write(
    "debts",
    mapOf("operatorId" to operatorId, "requestedAtMs" to requestedAtMs),
) {
    if (!isAdministrator(operatorId)) {
        insertOperation(
            requestedAtMs,
            operatorId,
            "debts",
            OperationType.DEBTS_QUERY,
            false,
            "NOT_ADMIN",
        )
        return@write AccessResult(false, null)
    }
    val debts = Balances.selectAll()
        .where { Balances.amountMinor less 0L }
        .orderBy(
            Balances.amountMinor to SortOrder.ASC,
            Balances.userId to SortOrder.ASC,
        )
        .map { Debt(it[Balances.userId], it[Balances.amountMinor]) }
    AccessResult(true, debts)
}

internal fun JdbcTransaction.loadRates(): List<RatePeriod> {
    val periods = Rates.selectAll()
        .orderBy(Rates.startMinute to SortOrder.ASC)
        .map {
            RatePeriod(
                startMinute = it[Rates.startMinute],
                endMinute = it[Rates.endMinute],
                amountPerHalfHour = it[Rates.amountPerHalfHour],
                maxAmount = it[Rates.maxAmount],
            )
        }
    return if (periods.isEmpty()) emptyList() else validateRatePeriods(periods)
}

internal fun deniedNote(reason: String, note: String): String =
    if (note.isBlank()) reason else "$reason: $note"
