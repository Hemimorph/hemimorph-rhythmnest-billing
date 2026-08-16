package io.github.hemimogph

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.doublereceive.DoubleReceive
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.routing.openapi.registerBearerAuthSecurityScheme
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import io.ktor.util.AttributeKey
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal const val API_AUTHENTICATION = "api-token"
private const val MAX_LOG_VALUE_LENGTH = 16_384
private val RequestBodyKey = AttributeKey<String>("RequestBodyForLogging")
private val ResponseBodyKey = AttributeKey<String>("ResponseBodyForLogging")
private val MethodsWithBodies = setOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Patch, HttpMethod.Delete)
private val SensitiveHeaders = setOf("authorization", "cookie", "set-cookie")

private val DetailedCallLogging = createApplicationPlugin("DetailedCallLogging") {
    onCall { call ->
        val body = if (call.request.httpMethod in MethodsWithBodies) {
            runCatching { call.receiveText() }.getOrElse { "<unavailable:${it::class.simpleName}>" }
        } else {
            "<empty>"
        }
        call.attributes.put(RequestBodyKey, body.toSafeLogValue())
    }
    onCallRespond { call, body ->
        val responseBody = if (call.response.status() == HttpStatusCode.NoContent) "<empty>" else body.toString()
        call.attributes.put(ResponseBodyKey, responseBody.toSafeLogValue())
    }
}

@Serializable
data class ApiError(val error: String)

class ApiValidationException(message: String) : IllegalArgumentException(message)

fun Application.configureApi(apiToken: String) {
    registerBearerAuthSecurityScheme(API_AUTHENTICATION, "Billing API bearer token")
    install(DoubleReceive)
    install(DetailedCallLogging)
    install(CallLogging) {
        level = Level.INFO
        format { call ->
            val requestHeaders = call.request.headers.entries().joinToString(prefix = "{", postfix = "}") { (name, values) ->
                val value = if (name.lowercase() in SensitiveHeaders) "<redacted>" else values.joinToString(",")
                "$name=$value"
            }
            val responseHeaders = call.response.headers.allValues().entries().joinToString(
                prefix = "{",
                postfix = "}",
            ) { (name, values) ->
                val value = if (name.lowercase() in SensitiveHeaders) "<redacted>" else values.joinToString(",")
                "$name=$value"
            }
            val status = call.response.status()
            val responseBody = if (status == HttpStatusCode.NoContent) {
                "<empty>"
            } else {
                call.attributes.getOrNull(ResponseBodyKey) ?: "<empty>"
            }
            "HTTP request method=${call.request.httpMethod.value} uri=${call.request.uri.toSafeLogValue()} " +
                "requestHeaders=${requestHeaders.toSafeLogValue()} " +
                "requestBody=${call.attributes.getOrNull(RequestBodyKey) ?: "<empty>"} " +
                "responseStatus=${status?.value ?: 0} responseHeaders=${responseHeaders.toSafeLogValue()} " +
                "responseBody=$responseBody"
        }
    }
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = false })
    }
    install(Authentication) {
        bearer(API_AUTHENTICATION) {
            authenticate { credential ->
                val supplied = credential.token.toByteArray(StandardCharsets.UTF_8)
                val expected = apiToken.toByteArray(StandardCharsets.UTF_8)
                if (MessageDigest.isEqual(supplied, expected)) UserIdPrincipal("billing-api") else null
            }
        }
    }
    install(StatusPages) {
        exception<ApiValidationException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ApiError(cause.message ?: "Invalid request"))
        }
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ApiError("Invalid request body"))
        }
        exception<OperationConflictException> { call, cause ->
            call.respond(HttpStatusCode.Conflict, ApiError(checkNotNull(cause.message)))
        }
        exception<GuestNotActiveException> { call, cause ->
            call.respond(HttpStatusCode.Conflict, ApiError(checkNotNull(cause.message)))
        }
        exception<BalanceOverflowException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ApiError(checkNotNull(cause.message)))
        }
        exception<BillingOverflowException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ApiError(checkNotNull(cause.message)))
        }
        exception<RateValidationException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ApiError(checkNotNull(cause.message)))
        }
        exception<PermissionDeniedException> { call, cause ->
            call.respond(HttpStatusCode.Forbidden, ApiError(checkNotNull(cause.message)))
        }
        exception<NegativeBalanceException> { call, cause ->
            call.respond(HttpStatusCode.PaymentRequired, ApiError(checkNotNull(cause.message)))
        }
        exception<RateConfigurationNotFoundException> { call, cause ->
            call.respond(HttpStatusCode.NotFound, ApiError(checkNotNull(cause.message)))
        }
    }
}

private fun String.toSafeLogValue(): String {
    val sanitized = replace('\r', ' ').replace('\n', ' ')
    return if (sanitized.length <= MAX_LOG_VALUE_LENGTH) sanitized else sanitized.take(MAX_LOG_VALUE_LENGTH) + "<truncated>"
}
