package io.github.hemimogph

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.ZoneOffset
import java.util.UUID

fun createTestDatabaseQueue(
    initialAdminId: String = "admin",
    seedRates: Boolean = true,
): DatabaseQueue {
    val database = Database.connect(
        url = "jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1",
        driver = "org.h2.Driver",
    )
    transaction(database) {
        SchemaUtils.create(Administrators, ActiveGuests, Balances, Rates, Operations)
        Administrators.insert { it[userId] = initialAdminId }
        if (seedRates) {
            Rates.insert {
                it[startMinute] = 0
                it[endMinute] = 1440
                it[amountPerHalfHour] = 0
                it[maxAmount] = -1
            }
        }
    }
    return DatabaseQueue(database, ZoneOffset.UTC)
}
