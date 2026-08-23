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
                OpenApiDoc(
                    info = OpenApiInfo(
                        title = "RhythmNest Billing API",
                        version = "1.0.0",
                        description = "All monetary values are integers in the smallest currency unit: multiply the major-unit amount by 100 before sending it. For example, 1.00 is represented as 100. Responses use the same convention.",
                    ),
                ) +
                    routingRoot.descendants()
                ) + findSecuritySchemesOrRefs()
            val content = OpenApiJson.encodeToString(document)

            check(document.paths.containsKey("/guest/{userId}/bill"))
            check(document.paths.containsKey("/admin/rates"))
            check(document.paths.containsKey("/admin/debts"))
            check(content.contains("X-Operator-Id"))
            check(content.contains("X-Request-Timestamp"))
            check(content.contains("major-unit amount by 100"))
            check(content.contains("Charge for each started half hour"))
            check(!content.contains("processedAtMs"))
            check(!content.contains("Idempotency-Key"))

            Files.createDirectories(output.parent)
            Files.writeString(output, content)
        }
        startApplication()
    }
}
