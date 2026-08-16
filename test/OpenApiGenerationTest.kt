package io.github.hemimogph

import io.ktor.openapi.OpenApiDoc
import io.ktor.openapi.OpenApiInfo
import io.ktor.server.routing.routingRoot
import io.ktor.server.routing.openapi.findSecuritySchemesOrRefs
import io.ktor.server.routing.openapi.plus
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

private val OpenApiJson = Json { prettyPrint = true }

class OpenApiGenerationTest {
    @Test
    fun generateOpenApi() = testApplication {
        application {
            rootModule("openapi-generation-token")
            val document = (
                OpenApiDoc(info = OpenApiInfo("RhythmNest Billing API", "1.0.0")) +
                    routingRoot.descendants()
                ) + findSecuritySchemesOrRefs()
            val content = OpenApiJson.encodeToString(document)
            val output = Path.of("build", "openapi", "openapi.json")
            Files.createDirectories(output.parent)
            Files.writeString(output, content)

            assertTrue(document.paths.containsKey("/guest/{userId}/bill"))
            assertTrue(document.paths.containsKey("/admin/rates"))
            assertTrue(document.paths.containsKey("/admin/debts"))
            assertTrue(content.contains("X-Operator-Id"))
            assertTrue(content.contains("Idempotency-Key"))
        }
        startApplication()
    }
}
