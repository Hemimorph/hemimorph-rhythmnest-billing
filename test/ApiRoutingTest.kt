package io.github.hemimogph

import io.ktor.client.statement.bodyAsText
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ApiRoutingTest {
    private val routes = listOf(
        HttpMethod.Put to "/guest/user/login",
        HttpMethod.Put to "/guest/user/logout",
        HttpMethod.Get to "/guest/count",
        HttpMethod.Get to "/guest/user/bill",
        HttpMethod.Get to "/guest/user/balance",
        HttpMethod.Get to "/guest/user/changes",
        HttpMethod.Put to "/admin/user",
        HttpMethod.Delete to "/admin/user",
        HttpMethod.Post to "/admin/user/balance",
        HttpMethod.Put to "/admin/rates",
        HttpMethod.Get to "/admin/debts",
    )

    @Test
    fun `all API routes require authentication`() = testApplication {
        application { rootModule("test-token") }

        routes.forEach { (method, path) ->
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.request(path) { this.method = method }.status,
                "$method $path",
            )
        }
    }

    @Test
    fun `rejects an incorrect API token`() = testApplication {
        application { rootModule("test-token") }

        val response = client.request("/guest/count") {
            method = HttpMethod.Get
            bearerAuth("wrong-token")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `rejects invalid change limit`() = testApplication {
        application { rootModule("test-token") }

        val response = client.request("/guest/user/changes?limit=0") {
            method = HttpMethod.Get
            bearerAuth("test-token")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `requires idempotency key for balance adjustment`() = testApplication {
        application { rootModule("test-token") }

        val response = client.request("/admin/user/balance") {
            method = HttpMethod.Post
            bearerAuth("test-token")
            header("X-Operator-Id", "admin")
            contentType(ContentType.Application.Json)
            setBody("""{"delta":100,"reason":"test"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `rejects zero balance adjustment`() = testApplication {
        application { rootModule("test-token") }

        val response = client.request("/admin/user/balance") {
            method = HttpMethod.Post
            bearerAuth("test-token")
            header("X-Operator-Id", "admin")
            header("Idempotency-Key", "event-1")
            contentType(ContentType.Application.Json)
            setBody("""{"delta":0,"reason":"test"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `rejects blank balance adjustment reason`() = testApplication {
        application { rootModule("test-token") }

        val response = client.request("/admin/user/balance") {
            method = HttpMethod.Post
            bearerAuth("test-token")
            header("X-Operator-Id", "admin")
            header("Idempotency-Key", "event-1")
            contentType(ContentType.Application.Json)
            setBody("""{"delta":100,"reason":" "}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `requires operator header`() = testApplication {
        application { rootModule("test-token") }

        val response = client.request("/guest/user/balance") {
            method = HttpMethod.Get
            bearerAuth("test-token")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `rejects unauthorized guest delegation`() = testApplication {
        val queue = createTestDatabaseQueue()
        application { rootModule(queue, "test-token") }

        try {
            val response = client.request("/guest/guest/login") {
                method = HttpMethod.Put
                bearerAuth("test-token")
                header("X-Operator-Id", "outsider")
                contentType(ContentType.Application.Json)
                setBody("""{"note":"attempt"}""")
            }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertEquals(0, queue.guestCount())
        } finally {
            queue.shutdown()
        }
    }

    @Test
    fun `parses supported change limits`() {
        assertEquals(5, parseChangesLimit(null))
        assertEquals(1, parseChangesLimit("1"))
        assertEquals(-1, parseChangesLimit("-1"))
        assertFailsWith<ApiValidationException> { parseChangesLimit("invalid") }
        assertFailsWith<ApiValidationException> { parseChangesLimit("-2") }
    }

    @Test
    fun `routes execute database operations`() = testApplication {
        val queue = createTestDatabaseQueue()
        application { rootModule(queue, "test-token") }

        try {
            val login = client.request("/guest/guest/login") {
                method = HttpMethod.Put
                bearerAuth("test-token")
                header("X-Operator-Id", "guest")
                contentType(ContentType.Application.Json)
                setBody("""{"note":"enter"}""")
            }
            assertEquals(HttpStatusCode.NoContent, login.status)

            val count = client.request("/guest/count") {
                method = HttpMethod.Get
                bearerAuth("test-token")
            }
            assertEquals(HttpStatusCode.OK, count.status)
            assertEquals("""{"count":1}""", count.bodyAsText())

            val adjustment = client.request("/admin/guest/balance") {
                method = HttpMethod.Post
                bearerAuth("test-token")
                header("X-Operator-Id", "admin")
                header("Idempotency-Key", "event-1")
                contentType(ContentType.Application.Json)
                setBody("""{"delta":-500,"reason":"charge"}""")
            }
            assertEquals(HttpStatusCode.Created, adjustment.status)

            val balance = client.request("/guest/guest/balance") {
                method = HttpMethod.Get
                bearerAuth("test-token")
                header("X-Operator-Id", "guest")
            }
            assertEquals(HttpStatusCode.OK, balance.status)
            assertEquals("""{"userId":"guest","balance":-500}""", balance.bodyAsText())

            val changes = client.request("/guest/guest/changes") {
                method = HttpMethod.Get
                bearerAuth("test-token")
                header("X-Operator-Id", "guest")
            }
            assertEquals(HttpStatusCode.OK, changes.status)
            assertTrue(changes.bodyAsText().contains("\"reason\":\"charge\""))

            val bill = client.request("/guest/guest/bill") {
                method = HttpMethod.Get
                bearerAuth("test-token")
                header("X-Operator-Id", "guest")
            }
            assertEquals(HttpStatusCode.OK, bill.status)
            assertTrue(bill.bodyAsText().contains("\"active\":true"))

            val logout = client.request("/guest/guest/logout") {
                method = HttpMethod.Put
                bearerAuth("test-token")
                header("X-Operator-Id", "guest")
                contentType(ContentType.Application.Json)
                setBody("""{"note":"leave"}""")
            }
            assertEquals(HttpStatusCode.NoContent, logout.status)
        } finally {
            queue.shutdown()
        }
    }

    @Test
    fun `rates are public but updates require an administrator`() = testApplication {
        val queue = createTestDatabaseQueue()
        application { rootModule(queue, "test-token") }

        try {
            val publicRates = client.request("/admin/rates") { method = HttpMethod.Get }
            assertEquals(HttpStatusCode.OK, publicRates.status)

            val denied = client.request("/admin/rates") {
                method = HttpMethod.Put
                bearerAuth("test-token")
                header("X-Operator-Id", "outsider")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"periods":[{"start":"0000","end":"2400","amountPerHalfHour":100,"maxAmount":7900}]}""",
                )
            }
            assertEquals(HttpStatusCode.Forbidden, denied.status)

            val updated = client.request("/admin/rates") {
                method = HttpMethod.Put
                bearerAuth("test-token")
                header("X-Operator-Id", "admin")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"periods":[{"start":"0000","end":"2400","amountPerHalfHour":100,"maxAmount":7900}]}""",
                )
            }
            assertEquals(HttpStatusCode.NoContent, updated.status)
        } finally {
            queue.shutdown()
        }
    }

    @Test
    fun `negative balance returns payment required on login`() = testApplication {
        val queue = createTestDatabaseQueue()
        queue.adjustBalance("guest", "admin", -100, "debt", "debt-api", 1_000)
        application { rootModule(queue, "test-token") }

        try {
            val response = client.request("/guest/guest/login") {
                method = HttpMethod.Put
                bearerAuth("test-token")
                header("X-Operator-Id", "guest")
                contentType(ContentType.Application.Json)
                setBody("""{"note":"enter"}""")
            }
            assertEquals(HttpStatusCode.PaymentRequired, response.status)

            val debts = client.request("/admin/debts") {
                method = HttpMethod.Get
                bearerAuth("test-token")
                header("X-Operator-Id", "admin")
            }
            assertEquals(HttpStatusCode.OK, debts.status)
            assertTrue(debts.bodyAsText().contains("\"balance\":-100"))
        } finally {
            queue.shutdown()
        }
    }

    @Test
    fun `missing rates return not found without settling logout`() = testApplication {
        val queue = createTestDatabaseQueue(seedRates = false)
        queue.login("guest", "guest", "enter", 1_000)
        application { rootModule(queue, "test-token") }

        try {
            val rates = client.request("/admin/rates") { method = HttpMethod.Get }
            assertEquals(HttpStatusCode.NotFound, rates.status)

            val bill = client.request("/guest/guest/bill") {
                method = HttpMethod.Get
                bearerAuth("test-token")
                header("X-Operator-Id", "guest")
            }
            assertEquals(HttpStatusCode.NotFound, bill.status)

            val logout = client.request("/guest/guest/logout") {
                method = HttpMethod.Put
                bearerAuth("test-token")
                header("X-Operator-Id", "guest")
                contentType(ContentType.Application.Json)
                setBody("""{"note":"leave"}""")
            }
            assertEquals(HttpStatusCode.NotFound, logout.status)
            assertEquals(1, queue.guestCount())
        } finally {
            queue.shutdown()
        }
    }
}
