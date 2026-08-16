package io.github.hemimogph

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Table

@Serializable
enum class OperationType {
    LOGIN,
    LOGOUT,
    ADMIN_ADD,
    ADMIN_REMOVE,
    ADMIN_ADJUST,
    BALANCE_QUERY,
    CHANGES_QUERY,
    BILL_QUERY,
    RATE_UPDATE,
    DEBTS_QUERY,
}

internal object Administrators : Table("administrators") {
    val userId = varchar("user_id", 128)

    override val primaryKey = PrimaryKey(userId)
}

internal object ActiveGuests : Table("active_guests") {
    val userId = varchar("user_id", 128)
    val enteredAtMs = long("entered_at_ms")

    override val primaryKey = PrimaryKey(userId)
}

internal object Balances : Table("balances") {
    val userId = varchar("user_id", 128)
    val amountMinor = long("amount_minor").default(0)

    override val primaryKey = PrimaryKey(userId)
}

internal object Rates : Table("rates") {
    val startMinute = integer("start_minute")
    val endMinute = integer("end_minute")
    val amountPerHalfHour = long("amount_per_half_hour")
    val maxAmount = long("max_amount")

    override val primaryKey = PrimaryKey(startMinute)
}

internal object Operations : Table("operations") {
    val requestedAtMs = long("requested_at_ms")
    val processedAtMs = long("processed_at_ms")
    val operatorId = varchar("operator_id", 128)
    val targetId = varchar("target_id", 128)
    val type = enumerationByName<OperationType>("type", 32)
    val allowed = bool("allowed")
    val note = text("note").default("")
    val billMinor = long("bill_minor").nullable()
    val deltaMinor = long("delta_minor").nullable()
    val balanceAfterMinor = long("balance_after_minor").nullable()

    override val primaryKey = PrimaryKey(requestedAtMs, operatorId)
}
