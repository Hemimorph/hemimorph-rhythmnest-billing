package io.github.hemimogph

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.sql.SQLException

data class BalanceChange(
    val occurredAtMs: Long,
    val operatorId: String,
    val type: OperationType,
    val delta: Long,
    val balanceAfter: Long,
    val reason: String,
)

data class Bill(
    val active: Boolean,
    val enteredAtMs: Long?,
    val calculatedAtMs: Long,
    val amount: Long,
)

data class BalanceAdjustment(
    val occurredAtMs: Long,
    val operatorId: String,
    val targetId: String,
    val delta: Long,
    val balanceAfter: Long,
    val reason: String,
    val allowed: Boolean,
)

data class AccessResult<T>(
    val allowed: Boolean,
    val value: T?,
)

enum class MutationResult {
    APPLIED,
    NO_CHANGE,
    DENIED_PERMISSION,
    DENIED_NEGATIVE_BALANCE,
}

class OperationConflictException : IllegalStateException(
    "The operator already performed another operation in the same millisecond",
)

class IdempotencyConflictException : IllegalStateException(
    "Idempotency-Key was already used for a different request",
)

class BalanceOverflowException : ArithmeticException("Balance exceeds the supported integer range")

class PermissionDeniedException : IllegalStateException("Operator is not allowed to perform this operation")

class NegativeBalanceException : IllegalStateException("A guest with a negative balance cannot enter")

suspend fun DatabaseQueue.login(
    targetId: String,
    operatorId: String,
    note: String,
    occurredAtMs: Long,
): MutationResult = write {
    if (!canOperateGuest(operatorId, targetId)) {
        insertOperation(
            occurredAtMs,
            operatorId,
            targetId,
            OperationType.LOGIN,
            false,
            deniedNote("NOT_SELF_OR_ADMIN", note),
        )
        return@write MutationResult.DENIED_PERMISSION
    }
    val existing = ActiveGuests.selectAll()
        .where { ActiveGuests.userId eq targetId }
        .singleOrNull()
    if (existing != null) {
        MutationResult.NO_CHANGE
    } else {
        val balance = Balances.selectAll()
            .where { Balances.userId eq targetId }
            .singleOrNull()
            ?.get(Balances.amountMinor)
            ?: 0L
        if (balance < 0) {
            insertOperation(
                occurredAtMs,
                operatorId,
                targetId,
                OperationType.LOGIN,
                false,
                deniedNote("NEGATIVE_BALANCE", note),
            )
            return@write MutationResult.DENIED_NEGATIVE_BALANCE
        }
        ensureBalance(targetId)
        ActiveGuests.insert {
            it[userId] = targetId
            it[enteredAtMs] = occurredAtMs
        }
        insertOperation(occurredAtMs, operatorId, targetId, OperationType.LOGIN, true, note)
        MutationResult.APPLIED
    }
}

suspend fun DatabaseQueue.logout(
    targetId: String,
    operatorId: String,
    note: String,
    occurredAtMs: Long,
): MutationResult = write {
    if (!canOperateGuest(operatorId, targetId)) {
        insertOperation(
            occurredAtMs,
            operatorId,
            targetId,
            OperationType.LOGOUT,
            false,
            deniedNote("NOT_SELF_OR_ADMIN", note),
        )
        return@write MutationResult.DENIED_PERMISSION
    }
    val existing = ActiveGuests.selectAll()
        .where { ActiveGuests.userId eq targetId }
        .singleOrNull()
    if (existing == null) {
        MutationResult.NO_CHANGE
    } else {
        val periods = loadRates()
        if (periods.isEmpty()) throw RateConfigurationNotFoundException()
        val bill = calculateBill(
            enteredAtMs = existing[ActiveGuests.enteredAtMs],
            calculatedAtMs = occurredAtMs,
            periods = periods,
            zoneId = zoneId,
        )
        val balanceRow = Balances.selectAll()
            .where { Balances.userId eq targetId }
            .singleOrNull()
        val currentBalance = balanceRow?.get(Balances.amountMinor) ?: 0L
        val balanceAfter = try {
            Math.subtractExact(currentBalance, bill)
        } catch (_: ArithmeticException) {
            throw BalanceOverflowException()
        }
        if (balanceRow == null) {
            Balances.insert {
                it[userId] = targetId
                it[amountMinor] = balanceAfter
            }
        } else {
            Balances.update({ Balances.userId eq targetId }) {
                it[amountMinor] = balanceAfter
            }
        }
        ActiveGuests.deleteWhere { ActiveGuests.userId eq targetId }
        insertLogoutOperation(
            occurredAtMs = occurredAtMs,
            operatorId = operatorId,
            targetId = targetId,
            bill = bill,
            balanceAfter = balanceAfter,
            note = note,
        )
        MutationResult.APPLIED
    }
}

suspend fun DatabaseQueue.guestCount(): Long = execute {
    ActiveGuests.selectAll().count()
}

suspend fun DatabaseQueue.balance(
    targetId: String,
    operatorId: String,
    occurredAtMs: Long,
): AccessResult<Long> = write {
    if (!canOperateGuest(operatorId, targetId)) {
        insertOperation(occurredAtMs, operatorId, targetId, OperationType.BALANCE_QUERY, false, "NOT_SELF_OR_ADMIN")
        AccessResult(false, null)
    } else {
        val balance = Balances.selectAll()
            .where { Balances.userId eq targetId }
            .singleOrNull()
            ?.get(Balances.amountMinor)
            ?: 0L
        AccessResult(true, balance)
    }
}

suspend fun DatabaseQueue.balanceChanges(
    targetId: String,
    operatorId: String,
    limit: Int,
    occurredAtMs: Long,
): AccessResult<List<BalanceChange>> = write {
    if (!canOperateGuest(operatorId, targetId)) {
        insertOperation(occurredAtMs, operatorId, targetId, OperationType.CHANGES_QUERY, false, "NOT_SELF_OR_ADMIN")
        return@write AccessResult(false, null)
    }
    val query = Operations.selectAll()
        .where {
            (Operations.targetId eq targetId) and
                (Operations.allowed eq true) and
                (
                    (Operations.type eq OperationType.BALANCE_ADJUST) or
                        (Operations.type eq OperationType.LOGOUT)
                    ) and
                (Operations.deltaMinor neq 0L)
        }
        .orderBy(
            Operations.occurredAtMs to SortOrder.DESC,
            Operations.operatorId to SortOrder.DESC,
        )
    if (limit != -1) query.limit(limit)
    val changes = query.map { row ->
        BalanceChange(
            occurredAtMs = row[Operations.occurredAtMs],
            operatorId = row[Operations.operatorId],
            type = row[Operations.type],
            delta = checkNotNull(row[Operations.deltaMinor]),
            balanceAfter = checkNotNull(row[Operations.balanceAfterMinor]),
            reason = row[Operations.note],
        )
    }
    AccessResult(true, changes)
}

suspend fun DatabaseQueue.bill(
    targetId: String,
    operatorId: String,
    calculatedAtMs: Long,
): AccessResult<Bill> = write {
    if (!canOperateGuest(operatorId, targetId)) {
        insertOperation(
            calculatedAtMs,
            operatorId,
            targetId,
            OperationType.BILL_QUERY,
            false,
            "NOT_SELF_OR_ADMIN",
        )
        return@write AccessResult(false, null)
    }
    val active = ActiveGuests.selectAll()
        .where { ActiveGuests.userId eq targetId }
        .singleOrNull()
    if (active == null) {
        AccessResult(true, Bill(false, null, calculatedAtMs, 0))
    } else {
        val periods = loadRates()
        if (periods.isEmpty()) throw RateConfigurationNotFoundException()
        val enteredAtMs = active[ActiveGuests.enteredAtMs]
        AccessResult(
            true,
            Bill(
                active = true,
                enteredAtMs = enteredAtMs,
                calculatedAtMs = calculatedAtMs,
                amount = calculateBill(enteredAtMs, calculatedAtMs, periods, zoneId),
            ),
        )
    }
}

suspend fun DatabaseQueue.adjustBalance(
    targetId: String,
    operatorId: String,
    delta: Long,
    reason: String,
    idempotencyKey: String,
    occurredAtMs: Long,
): BalanceAdjustment = write {
    val previous = Operations.selectAll()
        .where { Operations.idempotencyKey eq idempotencyKey }
        .singleOrNull()
    if (previous != null) {
        val expectedReason = if (previous[Operations.allowed]) reason else deniedNote("NOT_ADMIN", reason)
        if (
            previous[Operations.targetId] != targetId ||
            previous[Operations.operatorId] != operatorId ||
            previous[Operations.deltaMinor] != delta ||
            previous[Operations.note] != expectedReason
        ) {
            throw IdempotencyConflictException()
        }
        previous.toBalanceAdjustment()
    } else {
        val balanceRow = Balances.selectAll()
            .where { Balances.userId eq targetId }
            .singleOrNull()
        val currentBalance = balanceRow?.get(Balances.amountMinor) ?: 0L
        if (!isAdministrator(operatorId)) {
            val deniedReason = deniedNote("NOT_ADMIN", reason)
            insertBalanceOperation(
                occurredAtMs = occurredAtMs,
                operatorId = operatorId,
                targetId = targetId,
                delta = delta,
                balanceAfter = currentBalance,
                reason = deniedReason,
                idempotencyKey = idempotencyKey,
                allowed = false,
            )
            return@write BalanceAdjustment(
                occurredAtMs = occurredAtMs,
                operatorId = operatorId,
                targetId = targetId,
                delta = delta,
                balanceAfter = currentBalance,
                reason = deniedReason,
                allowed = false,
            )
        }
        val balanceAfter = try {
            Math.addExact(currentBalance, delta)
        } catch (_: ArithmeticException) {
            throw BalanceOverflowException()
        }

        if (balanceRow == null) {
            Balances.insert {
                it[userId] = targetId
                it[amountMinor] = balanceAfter
            }
        } else {
            Balances.update({ Balances.userId eq targetId }) {
                it[amountMinor] = balanceAfter
            }
        }
        insertBalanceOperation(
            occurredAtMs = occurredAtMs,
            operatorId = operatorId,
            targetId = targetId,
            delta = delta,
            balanceAfter = balanceAfter,
            reason = reason,
            idempotencyKey = idempotencyKey,
            allowed = true,
        )
        BalanceAdjustment(
            occurredAtMs = occurredAtMs,
            operatorId = operatorId,
            targetId = targetId,
            delta = delta,
            balanceAfter = balanceAfter,
            reason = reason,
            allowed = true,
        )
    }
}

private fun JdbcTransaction.ensureBalance(targetId: String) {
    val existing = Balances.selectAll()
        .where { Balances.userId eq targetId }
        .singleOrNull()
    if (existing == null) {
        Balances.insert {
            it[userId] = targetId
            it[amountMinor] = 0L
        }
    }
}

internal fun JdbcTransaction.insertOperation(
    occurredAtMs: Long,
    operatorId: String,
    targetId: String,
    type: OperationType,
    allowed: Boolean,
    note: String,
) {
    Operations.insert {
        it[Operations.occurredAtMs] = occurredAtMs
        it[Operations.operatorId] = operatorId
        it[Operations.targetId] = targetId
        it[Operations.type] = type
        it[Operations.allowed] = allowed
        it[Operations.note] = note
    }
}

private fun JdbcTransaction.insertBalanceOperation(
    occurredAtMs: Long,
    operatorId: String,
    targetId: String,
    delta: Long,
    balanceAfter: Long,
    reason: String,
    idempotencyKey: String,
    allowed: Boolean,
) {
    Operations.insert {
        it[Operations.occurredAtMs] = occurredAtMs
        it[Operations.operatorId] = operatorId
        it[Operations.targetId] = targetId
        it[type] = OperationType.BALANCE_ADJUST
        it[Operations.allowed] = allowed
        it[note] = reason
        it[deltaMinor] = delta
        it[Operations.balanceAfterMinor] = balanceAfter
        it[Operations.idempotencyKey] = idempotencyKey
    }
}

private fun JdbcTransaction.insertLogoutOperation(
    occurredAtMs: Long,
    operatorId: String,
    targetId: String,
    bill: Long,
    balanceAfter: Long,
    note: String,
) {
    Operations.insert {
        it[Operations.occurredAtMs] = occurredAtMs
        it[Operations.operatorId] = operatorId
        it[Operations.targetId] = targetId
        it[type] = OperationType.LOGOUT
        it[allowed] = true
        it[Operations.note] = note
        it[billMinor] = bill
        it[deltaMinor] = -bill
        it[Operations.balanceAfterMinor] = balanceAfter
    }
}

private fun JdbcTransaction.canOperateGuest(operatorId: String, targetId: String): Boolean =
    operatorId == targetId || isAdministrator(operatorId)

private fun org.jetbrains.exposed.v1.core.ResultRow.toBalanceAdjustment(): BalanceAdjustment =
    BalanceAdjustment(
        occurredAtMs = this[Operations.occurredAtMs],
        operatorId = this[Operations.operatorId],
        targetId = this[Operations.targetId],
        delta = checkNotNull(this[Operations.deltaMinor]),
        balanceAfter = checkNotNull(this[Operations.balanceAfterMinor]),
        reason = this[Operations.note],
        allowed = this[Operations.allowed],
    )

internal suspend fun <T> DatabaseQueue.write(block: JdbcTransaction.() -> T): T =
    try {
        execute(block)
    } catch (failure: Throwable) {
        if (failure.hasSqlState("23505")) throw OperationConflictException()
        throw failure
    }

private fun Throwable.hasSqlState(state: String): Boolean =
    generateSequence(this) { it.cause }
        .filterIsInstance<SQLException>()
        .any { it.sqlState == state }
