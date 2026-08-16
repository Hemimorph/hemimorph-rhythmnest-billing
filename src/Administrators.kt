package io.github.hemimogph

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

suspend fun DatabaseQueue.addAdministrator(
    targetId: String,
    operatorId: String,
    note: String,
    requestedAtMs: Long,
): MutationResult = write(
    "addAdministrator",
    mapOf("targetId" to targetId, "operatorId" to operatorId, "note" to note, "requestedAtMs" to requestedAtMs),
) {
    if (!isAdministrator(operatorId)) {
        insertOperation(
            requestedAtMs,
            operatorId,
            targetId,
            OperationType.ADMIN_ADD,
            false,
            deniedNote("NOT_ADMIN", note),
        )
        return@write MutationResult.DENIED_PERMISSION
    }
    val existing = Administrators.selectAll()
        .where { Administrators.userId eq targetId }
        .singleOrNull()
    if (existing != null) {
        MutationResult.NO_CHANGE
    } else {
        Administrators.insert { it[userId] = targetId }
        insertOperation(requestedAtMs, operatorId, targetId, OperationType.ADMIN_ADD, true, note)
        MutationResult.APPLIED
    }
}

suspend fun DatabaseQueue.deleteAdministrator(
    targetId: String,
    operatorId: String,
    note: String,
    requestedAtMs: Long,
): MutationResult = write(
    "deleteAdministrator",
    mapOf("targetId" to targetId, "operatorId" to operatorId, "note" to note, "requestedAtMs" to requestedAtMs),
) {
    if (!isAdministrator(operatorId)) {
        insertOperation(
            requestedAtMs,
            operatorId,
            targetId,
            OperationType.ADMIN_REMOVE,
            false,
            deniedNote("NOT_ADMIN", note),
        )
        return@write MutationResult.DENIED_PERMISSION
    }
    val existing = Administrators.selectAll()
        .where { Administrators.userId eq targetId }
        .singleOrNull()
    if (existing == null) {
        MutationResult.NO_CHANGE
    } else {
        Administrators.deleteWhere { Administrators.userId eq targetId }
        insertOperation(requestedAtMs, operatorId, targetId, OperationType.ADMIN_REMOVE, true, note)
        MutationResult.APPLIED
    }
}

internal fun JdbcTransaction.isAdministrator(userId: String): Boolean =
    Administrators.selectAll()
        .where { Administrators.userId eq userId }
        .singleOrNull() != null
