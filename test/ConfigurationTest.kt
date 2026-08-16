package io.github.hemimogph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConfigurationTest {
    private val environment = mapOf(
        "HERMIMORPH_BILL_HOST" to "127.0.0.1",
        "HERMIMORPH_BILL_PORT" to "9090",
        "HERMIMORPH_BILL_API_TOKEN" to "test-token",
        "HERMIMORPH_BILL_DB_JDBC_URL" to "jdbc:postgresql://localhost/rhythmnest",
        "HERMIMORPH_BILL_DB_USER" to "billing",
        "HERMIMORPH_BILL_DB_PASSWORD" to "secret",
        "HERMIMORPH_BILL_INITIAL_ADMIN_ID" to "initial-admin",
    )

    @Test
    fun `loads server settings from prefixed environment variables`() {
        val settings = ServerSettings.fromEnvironment(environment::get)

        assertEquals("127.0.0.1", settings.host)
        assertEquals(9090, settings.port)
        assertEquals("test-token", settings.apiToken)
    }

    @Test
    fun `loads database settings from prefixed environment variables`() {
        val settings = DatabaseSettings.fromEnvironment(environment::get)

        assertEquals("jdbc:postgresql://localhost/rhythmnest", settings.jdbcUrl)
        assertEquals("billing", settings.user)
        assertEquals("secret", settings.password)
        assertEquals("initial-admin", settings.initialAdminId)
    }

    @Test
    fun `rejects an invalid server port`() {
        val invalidEnvironment = environment + ("HERMIMORPH_BILL_PORT" to "65536")

        assertFailsWith<IllegalStateException> {
            ServerSettings.fromEnvironment(invalidEnvironment::get)
        }
    }
}
