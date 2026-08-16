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

private val OpenApiJson = Json { prettyPrint = true }

fun main(args: Array<String>) {
    val output = Path.of(requireNotNull(args.singleOrNull()) { "OpenAPI output path is required" }).toAbsolutePath()
    testApplication {
        application {
            configureApi("openapi-generation-token")
            configureRouting()
            val document = (
                OpenApiDoc(info = OpenApiInfo("RhythmNest Billing API", "1.0.0")) +
                    routingRoot.descendants()
                ) + findSecuritySchemesOrRefs()
            val content = OpenApiJson.encodeToString(document)

            check(document.paths.containsKey("/guest/{userId}/bill"))
            check(document.paths.containsKey("/admin/rates"))
            check(document.paths.containsKey("/admin/debts"))
            check(content.contains("X-Operator-Id"))
            check(content.contains("X-Request-Timestamp"))
            check(!content.contains("processedAtMs"))
            check(!content.contains("Idempotency-Key"))

            Files.createDirectories(output.parent)
            Files.writeString(output, content)
        }
        startApplication()
    }
}
