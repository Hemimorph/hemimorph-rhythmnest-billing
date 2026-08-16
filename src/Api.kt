package io.github.hemimogph

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.openapi.registerBearerAuthSecurityScheme
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal const val API_AUTHENTICATION = "api-token"

@Serializable
data class ApiError(val error: String)

class ApiValidationException(message: String) : IllegalArgumentException(message)

fun Application.configureApi(apiToken: String) {
    registerBearerAuthSecurityScheme(API_AUTHENTICATION, "Billing API bearer token")
    install(CallLogging) {
        level = Level.INFO
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
