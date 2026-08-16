package io.github.hemimogph

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoggingTest {
    @Test
    fun `HTTP logs include request and response values while redacting credentials`() {
        val messages = captureLogs("io.ktor.test") {
            testApplication {
                application { testRootModule("secret-token") }

                client.post("/admin/guest/balance") {
                    bearerAuth("secret-token")
                    header("X-Operator-Id", "admin")
                    header("X-Request-Timestamp", System.currentTimeMillis())
                    contentType(ContentType.Application.Json)
                    setBody("""{"delta":0,"reason":"test"}""")
                }
            }
        }

        val message = messages.single { it.startsWith("HTTP request method=POST") }
        assertTrue(message.contains("Authorization=<redacted>"))
        assertTrue(message.contains("requestBody={\"delta\":0,\"reason\":\"test\"}"))
        assertTrue(message.contains("responseStatus=400"))
        assertTrue(message.contains("responseBody=ApiError(error=delta must not be zero)"))
        assertFalse(message.contains("secret-token"))
    }

    @Test
    fun `database logs include operation input and output values`() {
        val queue = createTestDatabaseQueue()
        val messages = try {
            captureLogs(DatabaseQueue::class.java.name) {
                runBlocking {
                    queue.adjustBalance("guest", "admin", 100, "credit", 1_000)
                }
            }
        } finally {
            runBlocking { queue.shutdown() }
        }

        val message = messages.single { it.contains("Database transaction committed") }
        assertTrue(message.contains("operation=adjustBalance"))
        assertTrue(message.contains("targetId=guest"))
        assertTrue(message.contains("delta=100"))
        assertTrue(message.contains("balanceAfter=100"))
    }

    private fun captureLogs(loggerName: String, block: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(loggerName) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        return try {
            block()
            appender.list.map(ILoggingEvent::getFormattedMessage)
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }
}
