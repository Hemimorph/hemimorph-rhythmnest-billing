package io.github.hemimogph

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.time.LocalDateTime
import java.time.ZoneOffset

class BillingTest {
    @Test
    fun `login and logout only record effective state changes`() = withDatabase { queue ->
        assertEquals(MutationResult.APPLIED, queue.login("guest", "guest", "enter", 1_000))
        assertEquals(MutationResult.NO_CHANGE, queue.login("guest", "guest", "duplicate", 1_001))
        assertEquals(1, queue.guestCount())

        assertEquals(MutationResult.APPLIED, queue.logout("guest", "guest", "leave", 1_002))
        assertEquals(MutationResult.NO_CHANGE, queue.logout("guest", "guest", "duplicate", 1_003))
        assertEquals(0, queue.guestCount())

        val operations = queue.execute {
            Operations.selectAll()
                .where { Operations.targetId eq "guest" }
                .count()
        }
        assertEquals(2, operations)
    }

    @Test
    fun `same operator cannot perform two effective operations in one millisecond`() = withDatabase { queue ->
        queue.login("first", "admin", "", 1_000)

        assertFailsWith<OperationConflictException> {
            queue.login("second", "admin", "", 1_000)
        }
        assertEquals(1, queue.guestCount())
    }

    @Test
    fun `operation conflict rolls back balance update`() = withDatabase { queue ->
        queue.adjustBalance("guest", "admin", 100, "first", 1_000)

        assertFailsWith<OperationConflictException> {
            queue.adjustBalance("guest", "admin", 200, "second", 1_000)
        }
        assertEquals(100, queue.balance("guest", "guest", 2_000).value)
        assertEquals(1, queue.balanceChanges("guest", "guest", -1, 2_001).value?.size)
    }

    @Test
    fun `administrator changes are idempotent and recorded`() = withDatabase { queue ->
        assertEquals(MutationResult.APPLIED, queue.addAdministrator("second-admin", "admin", "add", 1_000))
        assertEquals(MutationResult.NO_CHANGE, queue.addAdministrator("second-admin", "admin", "duplicate", 1_001))
        assertEquals(MutationResult.APPLIED, queue.deleteAdministrator("second-admin", "admin", "remove", 1_002))
        assertEquals(MutationResult.NO_CHANGE, queue.deleteAdministrator("second-admin", "admin", "duplicate", 1_003))

        val operations = queue.execute {
            Operations.selectAll()
                .where { Operations.targetId eq "second-admin" }
                .count()
        }
        assertEquals(2, operations)
    }

    @Test
    fun `guest delegation requires administrator permission`() = withDatabase { queue ->
        assertEquals(
            MutationResult.DENIED_PERMISSION,
            queue.login("guest", "outsider", "denied", 1_000),
        )
        assertEquals(0, queue.guestCount())

        val denied = queue.execute {
            Operations.selectAll()
                .where { Operations.targetId eq "guest" }
                .single()
        }
        assertEquals(OperationType.LOGIN, denied[Operations.type])
        assertFalse(denied[Operations.allowed])

        assertEquals(
            MutationResult.APPLIED,
            queue.login("guest", "admin", "allowed", 1_001),
        )
    }

    @Test
    fun `admin operations are denied and logged for non administrators`() = withDatabase { queue ->
        assertEquals(
            MutationResult.DENIED_PERMISSION,
            queue.addAdministrator("new-admin", "outsider", "attempt", 1_000),
        )
        val administratorExists = queue.execute {
            Administrators.selectAll()
                .where { Administrators.userId eq "new-admin" }
                .singleOrNull() != null
        }
        assertFalse(administratorExists)

        val adjustment = queue.adjustBalance(
            targetId = "guest",
            operatorId = "outsider",
            delta = 100,
            reason = "attempt",
            requestedAtMs = 1_001,
        )
        assertFalse(adjustment.allowed)
        assertEquals(0, queue.balance("guest", "guest", 1_002).value)
        assertEquals(0, queue.balanceChanges("guest", "guest", -1, 1_003).value?.size)
    }

    @Test
    fun `balance queries require self or administrator permission`() = withDatabase { queue ->
        assertEquals(0, queue.balance("guest", "guest", 1_000).value)
        assertEquals(0, queue.balance("guest", "admin", 1_001).value)

        assertFalse(queue.balance("guest", "outsider", 1_002).allowed)
        assertFalse(queue.balanceChanges("guest", "outsider", 5, 1_003).allowed)

        val deniedTypes = queue.execute {
            Operations.selectAll()
                .where { Operations.operatorId eq "outsider" }
                .map { it[Operations.type] }
        }
        assertEquals(setOf(OperationType.BALANCE_QUERY, OperationType.CHANGES_QUERY), deniedTypes.toSet())
    }

    @Test
    fun `balance adjustment is signed and recorded`() = withDatabase { queue ->
        val first = queue.adjustBalance(
            targetId = "guest",
            operatorId = "admin",
            delta = -500,
            reason = "charge",
            requestedAtMs = 1_000,
        )

        assertEquals(-500, first.balanceAfter)
        assertEquals(1_000, first.requestedAtMs)
        val processedAtMs = queue.execute {
            Operations.selectAll()
                .where { Operations.requestedAtMs eq 1_000L }
                .single()[Operations.processedAtMs]
        }
        assertTrue(processedAtMs > first.requestedAtMs)
        assertEquals(-500, queue.balance("guest", "guest", 2_001).value)
        assertEquals(1, queue.balanceChanges("guest", "guest", -1, 2_002).value?.size)
    }

    @Test
    fun `balance history applies newest first limit`() = withDatabase { queue ->
        repeat(7) { index ->
            queue.adjustBalance(
                targetId = "guest",
                operatorId = "admin",
                delta = 100,
                reason = "change-$index",
                requestedAtMs = 1_000L + index,
            )
        }

        val defaultPage = checkNotNull(queue.balanceChanges("guest", "guest", 5, 2_000).value)
        val all = checkNotNull(queue.balanceChanges("guest", "guest", -1, 2_001).value)

        assertEquals(5, defaultPage.size)
        assertEquals("change-6", defaultPage.first().reason)
        assertEquals(7, all.size)
        assertEquals(700, queue.balance("guest", "guest", 2_002).value)
    }

    @Test
    fun `negative balance prevents self and administrator login`() = withDatabase { queue ->
        queue.adjustBalance("guest", "admin", -100, "debt", 1_000)

        assertEquals(
            MutationResult.DENIED_NEGATIVE_BALANCE,
            queue.login("guest", "guest", "self", 1_001),
        )
        assertEquals(
            MutationResult.DENIED_NEGATIVE_BALANCE,
            queue.login("guest", "admin", "delegated", 1_002),
        )
        assertEquals(0, queue.guestCount())

        val deniedLogins = queue.execute {
            Operations.selectAll()
                .where { Operations.type eq OperationType.LOGIN }
                .toList()
        }
        assertEquals(2, deniedLogins.size)
        assertTrue(deniedLogins.all { !it[Operations.allowed] })
        assertTrue(deniedLogins.all { it[Operations.note].startsWith("NEGATIVE_BALANCE") })
    }

    @Test
    fun `bill and logout use latest rate and record settlement`() = withDatabase { queue ->
        queue.replaceRates(
            periods = listOf(RatePeriod(0, 1440, 100, -1)),
            operatorId = "admin",
            note = "rate",
            requestedAtMs = 1_000,
        )
        val enteredAt = utcMillis(2026, 1, 1, 10, 0)
        val calculatedAt = enteredAt + 30 * 60 * 1000 + 1
        queue.login("guest", "guest", "enter", enteredAt)

        val bill = checkNotNull(queue.bill("guest", "guest", calculatedAt).value)
        assertTrue(bill.active)
        assertEquals(200, bill.amount)

        assertEquals(
            MutationResult.APPLIED,
            queue.logout("guest", "guest", "settlement", calculatedAt),
        )
        assertEquals(-200, queue.balance("guest", "guest", calculatedAt + 1).value)
        val changes = checkNotNull(queue.balanceChanges("guest", "guest", -1, calculatedAt + 2).value)
        assertEquals(1, changes.size)
        assertEquals(OperationType.LOGOUT, changes.single().type)
        assertEquals(-200, changes.single().delta)
    }

    @Test
    fun `missing rates keep active guest and balance unchanged`() = withDatabase(seedRates = false) { queue ->
        queue.login("guest", "guest", "enter", 1_000)

        assertFailsWith<RateConfigurationNotFoundException> {
            queue.bill("guest", "guest", 2_000)
        }
        assertFailsWith<RateConfigurationNotFoundException> {
            queue.logout("guest", "guest", "leave", 2_001)
        }
        assertEquals(1, queue.guestCount())
        assertEquals(0, queue.balance("guest", "guest", 2_002).value)
    }

    @Test
    fun `rate replacement and debts require administrator`() = withDatabase { queue ->
        val period = listOf(RatePeriod(0, 1440, 100, 7900))
        assertEquals(
            MutationResult.DENIED_PERMISSION,
            queue.replaceRates(period, "outsider", "attempt", 1_000),
        )
        assertEquals(
            MutationResult.APPLIED,
            queue.replaceRates(period, "admin", "update", 1_001),
        )
        assertEquals(period, queue.rates())

        queue.adjustBalance("guest", "admin", -100, "debt", 1_002)
        assertFalse(queue.debts("outsider", 1_003).allowed)
        val debts = checkNotNull(queue.debts("admin", 1_004).value)
        assertEquals(listOf(Debt("guest", -100)), debts)
    }

    private fun withDatabase(
        seedRates: Boolean = true,
        block: suspend (DatabaseQueue) -> Unit,
    ) = runBlocking {
        val queue = createTestDatabaseQueue(seedRates = seedRates)
        try {
            block(queue)
        } finally {
            queue.shutdown()
        }
    }

    private fun utcMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()
}
