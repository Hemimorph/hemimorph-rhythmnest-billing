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
    val requestedAtMs: Long,
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
    val periodCharges: List<PeriodCharge>,
    val amount: Long,
)

data class Checkout(
    val enteredAtMs: Long,
    val exitedAtMs: Long,
    val periodCharges: List<PeriodCharge>,
    val totalAmount: Long,
    val balanceAfter: Long,
)

data class LogoutResult(
    val status: MutationResult,
    val checkout: Checkout?,
)

data class ActiveGuest(
    val userId: String,
    val enteredAtMs: Long,
)

data class BalanceAdjustment(
    val requestedAtMs: Long,
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

class GuestNotActiveException : IllegalStateException("Guest is not active")

class BalanceOverflowException : ArithmeticException("Balance exceeds the supported integer range")

class PermissionDeniedException : IllegalStateException("Operator is not allowed to perform this operation")

class NegativeBalanceException : IllegalStateException("A guest with a negative balance cannot enter")

suspend fun DatabaseQueue.login(
    targetId: String,
    operatorId: String,
    note: String,
    requestedAtMs: Long,
): MutationResult = write {
    if (!canOperateGuest(operatorId, targetId)) {
        insertOperation(
            requestedAtMs,
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
                requestedAtMs,
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
            it[enteredAtMs] = requestedAtMs
        }
        insertOperation(requestedAtMs, operatorId, targetId, OperationType.LOGIN, true, note)
        MutationResult.APPLIED
    }
}

suspend fun DatabaseQueue.logout(
    targetId: String,
    operatorId: String,
    note: String,
    requestedAtMs: Long,
): LogoutResult = write {
    if (!canOperateGuest(operatorId, targetId)) {
        insertOperation(
            requestedAtMs,
            operatorId,
            targetId,
            OperationType.LOGOUT,
            false,
            deniedNote("NOT_SELF_OR_ADMIN", note),
        )
        return@write LogoutResult(MutationResult.DENIED_PERMISSION, null)
    }
    val existing = ActiveGuests.selectAll()
        .where { ActiveGuests.userId eq targetId }
        .singleOrNull()
    if (existing == null) {
        LogoutResult(MutationResult.NO_CHANGE, null)
    } else {
        val periods = loadRates()
        if (periods.isEmpty()) throw RateConfigurationNotFoundException()
        val enteredAtMs = existing[ActiveGuests.enteredAtMs]
        val bill = calculateBillBreakdown(
            enteredAtMs = enteredAtMs,
            calculatedAtMs = requestedAtMs,
            periods = periods,
            zoneId = zoneId,
        )
        val balanceRow = Balances.selectAll()
            .where { Balances.userId eq targetId }
            .singleOrNull()
        val currentBalance = balanceRow?.get(Balances.amountMinor) ?: 0L
        val balanceAfter = try {
            Math.subtractExact(currentBalance, bill.totalAmount)
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
            requestedAtMs = requestedAtMs,
            operatorId = operatorId,
            targetId = targetId,
            bill = bill.totalAmount,
            balanceAfter = balanceAfter,
            note = note,
        )
        LogoutResult(
            status = MutationResult.APPLIED,
            checkout = Checkout(
                enteredAtMs = enteredAtMs,
                exitedAtMs = requestedAtMs,
                periodCharges = bill.periodCharges,
                totalAmount = bill.totalAmount,
                balanceAfter = balanceAfter,
            ),
        )
    }
}

suspend fun DatabaseQueue.guestCount(): Long = execute {
    ActiveGuests.selectAll().count()
}

suspend fun DatabaseQueue.activeGuests(): List<ActiveGuest> = execute {
    ActiveGuests.selectAll()
        .orderBy(
            ActiveGuests.enteredAtMs to SortOrder.ASC,
            ActiveGuests.userId to SortOrder.ASC,
        )
        .map { row ->
            ActiveGuest(
                userId = row[ActiveGuests.userId],
                enteredAtMs = row[ActiveGuests.enteredAtMs],
            )
        }
}

suspend fun DatabaseQueue.balance(
    targetId: String,
    operatorId: String,
    requestedAtMs: Long,
): AccessResult<Long> = write {
    if (!canOperateGuest(operatorId, targetId)) {
        insertOperation(requestedAtMs, operatorId, targetId, OperationType.BALANCE_QUERY, false, "NOT_SELF_OR_ADMIN")
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
    requestedAtMs: Long,
): AccessResult<List<BalanceChange>> = write {
    if (!canOperateGuest(operatorId, targetId)) {
        insertOperation(requestedAtMs, operatorId, targetId, OperationType.CHANGES_QUERY, false, "NOT_SELF_OR_ADMIN")
        return@write AccessResult(false, null)
    }
    val query = Operations.selectAll()
        .where {
            (Operations.targetId eq targetId) and
                (Operations.allowed eq true) and
                (
                    (Operations.type eq OperationType.ADMIN_ADJUST) or
                        (Operations.type eq OperationType.LOGOUT)
                    ) and
                (Operations.deltaMinor neq 0L)
        }
        .orderBy(
            Operations.requestedAtMs to SortOrder.DESC,
            Operations.operatorId to SortOrder.DESC,
        )
    if (limit != -1) query.limit(limit)
    val changes = query.map { row ->
        BalanceChange(
            requestedAtMs = row[Operations.requestedAtMs],
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
        AccessResult(true, Bill(false, null, calculatedAtMs, emptyList(), 0))
    } else {
        val periods = loadRates()
        if (periods.isEmpty()) throw RateConfigurationNotFoundException()
        val enteredAtMs = active[ActiveGuests.enteredAtMs]
        val calculation = calculateBillBreakdown(enteredAtMs, calculatedAtMs, periods, zoneId)
        AccessResult(
            true,
            Bill(
                active = true,
                enteredAtMs = enteredAtMs,
                calculatedAtMs = calculatedAtMs,
                periodCharges = calculation.periodCharges,
                amount = calculation.totalAmount,
            ),
        )
    }
}

suspend fun DatabaseQueue.adjustBalance(
    targetId: String,
    operatorId: String,
    delta: Long,
    reason: String,
    requestedAtMs: Long,
): BalanceAdjustment = write {
    val balanceRow = Balances.selectAll()
        .where { Balances.userId eq targetId }
        .singleOrNull()
    val currentBalance = balanceRow?.get(Balances.amountMinor) ?: 0L
    if (!isAdministrator(operatorId)) {
        val deniedReason = deniedNote("NOT_ADMIN", reason)
        insertBalanceOperation(
            requestedAtMs = requestedAtMs,
            operatorId = operatorId,
            targetId = targetId,
            delta = delta,
            balanceAfter = currentBalance,
            reason = deniedReason,
            allowed = false,
        )
        return@write BalanceAdjustment(
            requestedAtMs = requestedAtMs,
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
        requestedAtMs = requestedAtMs,
        operatorId = operatorId,
        targetId = targetId,
        delta = delta,
        balanceAfter = balanceAfter,
        reason = reason,
        allowed = true,
    )
    BalanceAdjustment(
        requestedAtMs = requestedAtMs,
        operatorId = operatorId,
        targetId = targetId,
        delta = delta,
        balanceAfter = balanceAfter,
        reason = reason,
        allowed = true,
    )
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
    requestedAtMs: Long,
    operatorId: String,
    targetId: String,
    type: OperationType,
    allowed: Boolean,
    note: String,
) {
    Operations.insert {
        it[Operations.requestedAtMs] = requestedAtMs
        it[Operations.processedAtMs] = System.currentTimeMillis()
        it[Operations.operatorId] = operatorId
        it[Operations.targetId] = targetId
        it[Operations.type] = type
        it[Operations.allowed] = allowed
        it[Operations.note] = note
    }
}

private fun JdbcTransaction.insertBalanceOperation(
    requestedAtMs: Long,
    operatorId: String,
    targetId: String,
    delta: Long,
    balanceAfter: Long,
    reason: String,
    allowed: Boolean,
): Unit {
    Operations.insert {
        it[Operations.requestedAtMs] = requestedAtMs
        it[Operations.processedAtMs] = System.currentTimeMillis()
        it[Operations.operatorId] = operatorId
        it[Operations.targetId] = targetId
        it[type] = OperationType.ADMIN_ADJUST
        it[Operations.allowed] = allowed
        it[note] = reason
        it[deltaMinor] = delta
        it[Operations.balanceAfterMinor] = balanceAfter
    }
}

private fun JdbcTransaction.insertLogoutOperation(
    requestedAtMs: Long,
    operatorId: String,
    targetId: String,
    bill: Long,
    balanceAfter: Long,
    note: String,
) {
    Operations.insert {
        it[Operations.requestedAtMs] = requestedAtMs
        it[Operations.processedAtMs] = System.currentTimeMillis()
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
