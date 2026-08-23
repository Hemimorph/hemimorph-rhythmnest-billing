package io.github.hemimogph

import io.ktor.http.HttpStatusCode
import io.ktor.openapi.JsonSchema
import io.ktor.openapi.Operation
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.openapi.hide
import io.ktor.utils.io.ExperimentalKtorApi
import kotlinx.serialization.Serializable

private const val OPERATOR_ID = "X-Operator-Id"
private const val REQUEST_TIMESTAMP = "X-Request-Timestamp"
private const val MAX_TIMESTAMP_DIFFERENCE_MS = 60_000L
private const val DEFAULT_CHANGE_LIMIT = 5
private const val MAX_IDENTIFIER_LENGTH = 128
private const val MINOR_UNIT_DESCRIPTION =
    "Integer in the smallest currency unit (major-unit amount multiplied by 100); for example, 1.00 is 100."

@Serializable
private data class OperationRequest(val note: String = "")

@Serializable
private data class BalanceAdjustmentRequest(
    @JsonSchema.Description("Signed balance adjustment. $MINOR_UNIT_DESCRIPTION")
    val delta: Long,
    val reason: String,
)

@Serializable
private data class RatePeriodRequest(
    val start: String,
    val end: String,
    @JsonSchema.Description("Charge for each started half hour. $MINOR_UNIT_DESCRIPTION")
    val amountPerHalfHour: Long,
    @JsonSchema.Description("Maximum charge for one occurrence of this rate period, or -1 for no cap. Non-negative values use the following convention: $MINOR_UNIT_DESCRIPTION")
    val maxAmount: Long,
)

@Serializable
private data class RatesUpdateRequest(
    val periods: List<RatePeriodRequest>,
    val note: String = "",
)

@Serializable
private data class ActiveGuestResponse(
    val userId: String,
    @JsonSchema.Format("int64")
    @JsonSchema.Description("Unix timestamp in milliseconds since 1970-01-01T00:00:00Z (UTC).")
    val enteredAtMs: Long,
)

@Serializable
private data class GuestCountResponse(
    val count: Long,
    val guests: List<ActiveGuestResponse>,
)

@Serializable
private data class BalanceResponse(
    val userId: String,
    @JsonSchema.Description("Current balance. $MINOR_UNIT_DESCRIPTION")
    val balance: Long,
)

@Serializable
private enum class BalanceChangeType {
    LOGOUT,
    ADMIN_ADJUST,
}

@Serializable
private data class BalanceChangeResponse(
    @JsonSchema.Format("int64")
    @JsonSchema.Description("Unix timestamp in milliseconds since 1970-01-01T00:00:00Z (UTC).")
    val requestedAtMs: Long,
    val operatorId: String,
    val type: BalanceChangeType,
    @JsonSchema.Description("Signed balance change. $MINOR_UNIT_DESCRIPTION")
    val delta: Long,
    @JsonSchema.Description("Balance after the change. $MINOR_UNIT_DESCRIPTION")
    val balanceAfter: Long,
    val reason: String,
)

@Serializable
private data class BillResponse(
    val userId: String,
    @JsonSchema.Format("int64")
    @JsonSchema.Description("Unix timestamp in milliseconds since 1970-01-01T00:00:00Z (UTC).")
    val enteredAtMs: Long,
    @JsonSchema.Format("int64")
    @JsonSchema.Description("Unix timestamp in milliseconds since 1970-01-01T00:00:00Z (UTC).")
    val calculatedAtMs: Long,
    val periodCharges: List<PeriodChargeResponse>,
    @JsonSchema.Description("Total calculated bill. $MINOR_UNIT_DESCRIPTION")
    val amount: Long,
)

@Serializable
private data class PeriodChargeResponse(
    @JsonSchema.Format("int64")
    @JsonSchema.Description("Unix timestamp in milliseconds since 1970-01-01T00:00:00Z (UTC).")
    val startedAtMs: Long,
    @JsonSchema.Format("int64")
    @JsonSchema.Description("Unix timestamp in milliseconds since 1970-01-01T00:00:00Z (UTC).")
    val endedAtMs: Long,
    @JsonSchema.Description("Charge for this rate-period occurrence. $MINOR_UNIT_DESCRIPTION")
    val amount: Long,
)

@Serializable
private data class CheckoutResponse(
    val userId: String,
    @JsonSchema.Format("int64")
    @JsonSchema.Description("Unix timestamp in milliseconds since 1970-01-01T00:00:00Z (UTC).")
    val enteredAtMs: Long,
    @JsonSchema.Format("int64")
    @JsonSchema.Description("Unix timestamp in milliseconds since 1970-01-01T00:00:00Z (UTC).")
    val exitedAtMs: Long,
    val periodCharges: List<PeriodChargeResponse>,
    @JsonSchema.Description("Total checkout charge. $MINOR_UNIT_DESCRIPTION")
    val totalAmount: Long,
    @JsonSchema.Description("Balance remaining after checkout. $MINOR_UNIT_DESCRIPTION")
    val remainingBalance: Long,
)

@Serializable
private data class RatePeriodResponse(
    val start: String,
    val end: String,
    @JsonSchema.Description("Charge for each started half hour. $MINOR_UNIT_DESCRIPTION")
    val amountPerHalfHour: Long,
    @JsonSchema.Description("Maximum charge for one occurrence of this rate period, or -1 for no cap. Non-negative values use the following convention: $MINOR_UNIT_DESCRIPTION")
    val maxAmount: Long,
)

@Serializable
private data class RatesResponse(
    val timeZone: String,
    val periods: List<RatePeriodResponse>,
)

@Serializable
private data class DebtResponse(
    val userId: String,
    @JsonSchema.Description("Negative balance owed by the user. $MINOR_UNIT_DESCRIPTION")
    val balance: Long,
)

@Serializable
private data class DebtsResponse(
    val count: Int,
    val balances: List<DebtResponse>,
)

@Serializable
private data class BalanceChangesResponse(
    val userId: String,
    val changes: List<BalanceChangeResponse>,
)

@Serializable
private data class BalanceAdjustmentResponse(
    @JsonSchema.Format("int64")
    @JsonSchema.Description("Unix timestamp in milliseconds since 1970-01-01T00:00:00Z (UTC).")
    val requestedAtMs: Long,
    val operatorId: String,
    val userId: String,
    @JsonSchema.Description("Signed balance adjustment. $MINOR_UNIT_DESCRIPTION")
    val delta: Long,
    @JsonSchema.Description("Balance after the adjustment. $MINOR_UNIT_DESCRIPTION")
    val balanceAfter: Long,
    val reason: String,
)

@OptIn(ExperimentalKtorApi::class)
fun Application.configureRouting() {
    routing {
        get("/") {
            call.respondText("Hello, World!")
        }.hide()

        get("/admin/rates") {
            val rates = databaseQueue.rates()
            if (rates.isEmpty()) throw RateConfigurationNotFoundException()
            call.respond(rates.toResponse(databaseQueue.zoneId.id))
        }.describe {
            documentedOperation("Get rates", "admin")
            jsonResponses<RatesResponse>(
                HttpStatusCode.OK,
                "Current rate configuration",
                HttpStatusCode.NotFound,
            )
        }

        authenticate(API_AUTHENTICATION) {
            route("/guest") {
                get("/count") {
                    val guests = databaseQueue.activeGuests()
                    call.respond(
                        GuestCountResponse(
                            count = guests.size.toLong(),
                            guests = guests.map { ActiveGuestResponse(it.userId, it.enteredAtMs) },
                        ),
                    )
                }.describe {
                    documentedOperation("Count active guests", "guest")
                    jsonResponses<GuestCountResponse>(
                        HttpStatusCode.OK,
                        "Current active guest count",
                        HttpStatusCode.Unauthorized,
                    )
                }
                route("/{userId}") {
                    put("/login") {
                        val requestedAtMs = call.requireRequestTimestamp()
                        val userId = call.requireUserId()
                        val operatorId = call.requireOperatorId()
                        val request = call.receive<OperationRequest>().validated()
                        databaseQueue.login(userId, operatorId, request.note, requestedAtMs).requireAllowed()
                    call.respond(HttpStatusCode.NoContent)
                    }.describe {
                        documentedOperation("Log in guest", "guest", userId = true, operatorId = true)
                        jsonRequest<OperationRequest>()
                        emptyResponses(
                            HttpStatusCode.NoContent,
                            "Guest is active",
                            HttpStatusCode.BadRequest,
                            HttpStatusCode.Unauthorized,
                            HttpStatusCode.PaymentRequired,
                            HttpStatusCode.Forbidden,
                            HttpStatusCode.Conflict,
                        )
                    }
                    put("/logout") {
                        val requestedAtMs = call.requireRequestTimestamp()
                        val userId = call.requireUserId()
                        val operatorId = call.requireOperatorId()
                        val request = call.receive<OperationRequest>().validated()
                        val checkout = databaseQueue.logout(
                            userId,
                            operatorId,
                            request.note,
                            requestedAtMs,
                        ).requireCheckout()
                        call.respond(
                            CheckoutResponse(
                                userId = userId,
                                enteredAtMs = checkout.enteredAtMs,
                                exitedAtMs = checkout.exitedAtMs,
                                periodCharges = checkout.periodCharges.map { charge ->
                                    PeriodChargeResponse(
                                        startedAtMs = charge.startedAtMs,
                                        endedAtMs = charge.endedAtMs,
                                        amount = charge.amount,
                                    )
                                },
                                totalAmount = checkout.totalAmount,
                                remainingBalance = checkout.balanceAfter,
                            ),
                        )
                    }.describe {
                        documentedOperation("Log out and settle guest", "guest", userId = true, operatorId = true)
                        jsonRequest<OperationRequest>()
                        jsonResponses<CheckoutResponse>(
                            HttpStatusCode.OK,
                            "Checkout receipt with per-period charges",
                            HttpStatusCode.BadRequest,
                            HttpStatusCode.Unauthorized,
                            HttpStatusCode.Forbidden,
                            HttpStatusCode.NotFound,
                            HttpStatusCode.Conflict,
                        )
                    }
                    get("/bill") {
                        val requestedAtMs = call.requireRequestTimestamp()
                        val userId = call.requireUserId()
                        val operatorId = call.requireOperatorId()
                        val bill = databaseQueue.bill(userId, operatorId, requestedAtMs).requireAllowed()
                        if (!bill.active) {
                            call.respond(HttpStatusCode.NoContent)
                            return@get
                        }
                        call.respond(
                            BillResponse(
                                userId = userId,
                                enteredAtMs = checkNotNull(bill.enteredAtMs),
                                calculatedAtMs = bill.calculatedAtMs,
                                periodCharges = bill.periodCharges.map { charge ->
                                    PeriodChargeResponse(
                                        startedAtMs = charge.startedAtMs,
                                        endedAtMs = charge.endedAtMs,
                                        amount = charge.amount,
                                    )
                                },
                                amount = bill.amount,
                            ),
                        )
                    }.describe {
                        documentedOperation("Get current bill", "guest", userId = true, operatorId = true)
                        billResponses(
                            HttpStatusCode.OK,
                            "Current calculated bill",
                            HttpStatusCode.BadRequest,
                            HttpStatusCode.Unauthorized,
                            HttpStatusCode.Forbidden,
                            HttpStatusCode.NotFound,
                            HttpStatusCode.Conflict,
                        )
                    }
                    get("/balance") {
                        val requestedAtMs = call.requireRequestTimestamp()
                        val userId = call.requireUserId()
                        val operatorId = call.requireOperatorId()
                        val result = databaseQueue.balance(userId, operatorId, requestedAtMs).requireAllowed()
                        call.respond(BalanceResponse(userId, result))
                    }.describe {
                        documentedOperation("Get guest balance", "guest", userId = true, operatorId = true)
                        jsonResponses<BalanceResponse>(
                            HttpStatusCode.OK,
                            "Current balance",
                            HttpStatusCode.BadRequest,
                            HttpStatusCode.Unauthorized,
                            HttpStatusCode.Forbidden,
                            HttpStatusCode.Conflict,
                        )
                    }
                    get("/changes") {
                        val requestedAtMs = call.requireRequestTimestamp()
                        val userId = call.requireUserId()
                        val limit = parseChangesLimit(call.request.queryParameters["limit"])
                        val operatorId = call.requireOperatorId()
                        val changes = databaseQueue.balanceChanges(
                            userId,
                            operatorId,
                            limit,
                            requestedAtMs,
                        ).requireAllowed().map { change ->
                            BalanceChangeResponse(
                                requestedAtMs = change.requestedAtMs,
                                operatorId = change.operatorId,
                                type = BalanceChangeType.valueOf(change.type.name),
                                delta = change.delta,
                                balanceAfter = change.balanceAfter,
                                reason = change.reason,
                            )
                        }
                        call.respond(BalanceChangesResponse(userId, changes))
                    }.describe {
                        documentedOperation(
                            "Get recent balance changes",
                            "guest",
                            userId = true,
                            operatorId = true,
                            limit = true,
                        )
                        jsonResponses<BalanceChangesResponse>(
                            HttpStatusCode.OK,
                            "Recent balance changes",
                            HttpStatusCode.BadRequest,
                            HttpStatusCode.Unauthorized,
                            HttpStatusCode.Forbidden,
                            HttpStatusCode.Conflict,
                        )
                    }
                }
            }

            put("/admin/rates") {
                val requestedAtMs = call.requireRequestTimestamp()
                val operatorId = call.requireOperatorId()
                val request = call.receive<RatesUpdateRequest>().validated()
                databaseQueue.replaceRates(
                    periods = request.periods.map(RatePeriodRequest::toRatePeriod),
                    operatorId = operatorId,
                    note = request.note,
                    requestedAtMs = requestedAtMs,
                ).requireAllowed()
                call.respond(HttpStatusCode.NoContent)
            }.describe {
                documentedOperation("Replace rates", "admin", operatorId = true)
                jsonRequest<RatesUpdateRequest>()
                emptyResponses(
                    HttpStatusCode.NoContent,
                    "Rate configuration replaced",
                    HttpStatusCode.BadRequest,
                    HttpStatusCode.Unauthorized,
                    HttpStatusCode.Forbidden,
                    HttpStatusCode.Conflict,
                )
            }

            get("/admin/debts") {
                val requestedAtMs = call.requireRequestTimestamp()
                val operatorId = call.requireOperatorId()
                val debts = databaseQueue.debts(operatorId, requestedAtMs).requireAllowed()
                call.respond(
                    DebtsResponse(
                        count = debts.size,
                        balances = debts.map { DebtResponse(it.userId, it.balance) },
                    ),
                )
            }.describe {
                documentedOperation("Get negative balances", "admin", operatorId = true)
                jsonResponses<DebtsResponse>(
                    HttpStatusCode.OK,
                    "All negative balances",
                    HttpStatusCode.BadRequest,
                    HttpStatusCode.Unauthorized,
                    HttpStatusCode.Forbidden,
                    HttpStatusCode.Conflict,
                )
            }

            route("/admin/{userId}") {
                put {
                    val requestedAtMs = call.requireRequestTimestamp()
                    val userId = call.requireUserId()
                    val operatorId = call.requireOperatorId()
                    val request = call.receive<OperationRequest>().validated()
                    databaseQueue.addAdministrator(
                        userId,
                        operatorId,
                        request.note,
                        requestedAtMs,
                    ).requireAllowed()
                    call.respond(HttpStatusCode.NoContent)
                }.describe {
                    documentedOperation("Add administrator", "admin", userId = true, operatorId = true)
                    jsonRequest<OperationRequest>()
                    emptyResponses(
                        HttpStatusCode.NoContent,
                        "Administrator added",
                        HttpStatusCode.BadRequest,
                        HttpStatusCode.Unauthorized,
                        HttpStatusCode.Forbidden,
                        HttpStatusCode.Conflict,
                    )
                }
                delete {
                    val requestedAtMs = call.requireRequestTimestamp()
                    val userId = call.requireUserId()
                    val operatorId = call.requireOperatorId()
                    val request = call.receive<OperationRequest>().validated()
                    databaseQueue.deleteAdministrator(
                        userId,
                        operatorId,
                        request.note,
                        requestedAtMs,
                    ).requireAllowed()
                    call.respond(HttpStatusCode.NoContent)
                }.describe {
                    documentedOperation("Delete administrator", "admin", userId = true, operatorId = true)
                    jsonRequest<OperationRequest>()
                    emptyResponses(
                        HttpStatusCode.NoContent,
                        "Administrator deleted",
                        HttpStatusCode.BadRequest,
                        HttpStatusCode.Unauthorized,
                        HttpStatusCode.Forbidden,
                        HttpStatusCode.Conflict,
                    )
                }
                post("/balance") {
                    val requestedAtMs = call.requireRequestTimestamp()
                    val userId = call.requireUserId()
                    val operatorId = call.requireOperatorId()
                    val request = call.receive<BalanceAdjustmentRequest>().validated()
                    val adjustment = databaseQueue.adjustBalance(
                        targetId = userId,
                        operatorId = operatorId,
                        delta = request.delta,
                        reason = request.reason,
                        requestedAtMs = requestedAtMs,
                    ).requireAllowed()
                    call.respond(
                        HttpStatusCode.Created,
                        BalanceAdjustmentResponse(
                            requestedAtMs = adjustment.requestedAtMs,
                            operatorId = adjustment.operatorId,
                            userId = adjustment.targetId,
                            delta = adjustment.delta,
                            balanceAfter = adjustment.balanceAfter,
                            reason = adjustment.reason,
                        ),
                    )
                }.describe {
                    documentedOperation(
                        "Adjust guest balance",
                        "admin",
                        userId = true,
                        operatorId = true,
                    )
                    jsonRequest<BalanceAdjustmentRequest>()
                    jsonResponses<BalanceAdjustmentResponse>(
                        HttpStatusCode.Created,
                        "Created balance adjustment",
                        HttpStatusCode.BadRequest,
                        HttpStatusCode.Unauthorized,
                        HttpStatusCode.Forbidden,
                        HttpStatusCode.Conflict,
                    )
                }
            }
        }
    }
}

private fun Operation.Builder.documentedOperation(
    operationSummary: String,
    operationTag: String,
    userId: Boolean = false,
    operatorId: Boolean = false,
    limit: Boolean = false,
) {
    summary = operationSummary
    tag(operationTag)
    parameters {
        if (userId) {
            path("userId") {
                description = "Target user ID"
                schema = jsonSchema<String>()
            }
        }
        if (operatorId) {
            header(OPERATOR_ID) {
                description = "ID of the user performing the operation"
                required = true
                schema = jsonSchema<String>()
            }
            header(REQUEST_TIMESTAMP) {
                description = "Client request instant as Unix epoch milliseconds (UTC); must be within 60 seconds of server time. Billing maps this instant into the configured local time zone."
                required = true
                schema = jsonSchema<Long>()
            }
        }
        if (limit) {
            query("limit") {
                description = "Maximum number of records; defaults to 5 and -1 returns all"
                required = false
                schema = jsonSchema<Int>()
            }
        }
    }
}

private inline fun <reified T : Any> Operation.Builder.jsonRequest() {
    requestBody {
        required = true
        schema = jsonSchema<T>()
    }
}

private inline fun <reified T : Any> Operation.Builder.jsonResponses(
    status: HttpStatusCode,
    responseDescription: String,
    vararg errors: HttpStatusCode,
) {
    responses {
        status {
            description = responseDescription
            schema = jsonSchema<T>()
        }
        errors.forEach { error ->
            error {
                description = error.description
                schema = jsonSchema<ApiError>()
            }
        }
    }
}

private fun Operation.Builder.billResponses(
    status: HttpStatusCode,
    responseDescription: String,
    vararg errors: HttpStatusCode,
) {
    responses {
        status {
            description = responseDescription
            schema = jsonSchema<BillResponse>()
        }
        HttpStatusCode.NoContent {
            description = "Guest is not active"
        }
        errors.forEach { error ->
            error {
                description = error.description
                schema = jsonSchema<ApiError>()
            }
        }
    }
}

private fun Operation.Builder.emptyResponses(
    status: HttpStatusCode,
    responseDescription: String,
    vararg errors: HttpStatusCode,
) {
    responses {
        status { description = responseDescription }
        errors.forEach { error ->
            error {
                description = error.description
                schema = jsonSchema<ApiError>()
            }
        }
    }
}

internal fun parseChangesLimit(value: String?): Int {
    if (value == null) return DEFAULT_CHANGE_LIMIT
    val limit = value.toIntOrNull()
        ?: throw ApiValidationException("limit must be -1 or a positive integer")
    if (limit != -1 && limit <= 0) {
        throw ApiValidationException("limit must be -1 or a positive integer")
    }
    return limit
}

private fun ApplicationCall.requireUserId(): String =
    parameters["userId"].validatedIdentifier("userId")

private fun ApplicationCall.requireOperatorId(): String =
    request.header(OPERATOR_ID).validatedIdentifier(OPERATOR_ID)

private fun ApplicationCall.requireRequestTimestamp(): Long {
    val requestedAtMs = request.header(REQUEST_TIMESTAMP)?.toLongOrNull()
        ?: throw ApiValidationException("$REQUEST_TIMESTAMP must be a Unix timestamp in milliseconds")
    val receivedAtMs = System.currentTimeMillis()
    if (requestedAtMs < receivedAtMs - MAX_TIMESTAMP_DIFFERENCE_MS ||
        requestedAtMs > receivedAtMs + MAX_TIMESTAMP_DIFFERENCE_MS
    ) {
        throw ApiValidationException("$REQUEST_TIMESTAMP must be within 60 seconds of server time")
    }
    return requestedAtMs
}

private fun OperationRequest.validated(): OperationRequest = copy(
    note = note.trim(),
)

private fun BalanceAdjustmentRequest.validated(): BalanceAdjustmentRequest {
    if (delta == 0L) throw ApiValidationException("delta must not be zero")
    if (reason.isBlank()) throw ApiValidationException("reason must not be blank")
    return copy(reason = reason.trim())
}

private fun RatesUpdateRequest.validated(): RatesUpdateRequest = copy(
    periods = periods.also { requests ->
        validateRatePeriods(requests.map(RatePeriodRequest::toRatePeriod))
    },
    note = note.trim(),
)

private fun RatePeriodRequest.toRatePeriod(): RatePeriod = RatePeriod(
    startMinute = parseRateTime(start, 24 * 60, "start"),
    endMinute = parseRateTime(end, 48 * 60, "end"),
    amountPerHalfHour = amountPerHalfHour,
    maxAmount = maxAmount,
)

private fun List<RatePeriod>.toResponse(timeZone: String): RatesResponse = RatesResponse(
    timeZone = timeZone,
    periods = map { period ->
        RatePeriodResponse(
            start = formatRateTime(period.startMinute),
            end = formatRateTime(period.endMinute),
            amountPerHalfHour = period.amountPerHalfHour,
            maxAmount = period.maxAmount,
        )
    },
)

private fun String?.validatedIdentifier(name: String): String {
    val value = this?.trim()?.takeIf(String::isNotEmpty)
        ?: throw ApiValidationException("$name must not be blank")
    if (value.length > MAX_IDENTIFIER_LENGTH) {
        throw ApiValidationException("$name must not exceed $MAX_IDENTIFIER_LENGTH characters")
    }
    return value
}

private fun MutationResult.requireAllowed() {
    when (this) {
        MutationResult.DENIED_PERMISSION -> throw PermissionDeniedException()
        MutationResult.DENIED_NEGATIVE_BALANCE -> throw NegativeBalanceException()
        MutationResult.APPLIED, MutationResult.NO_CHANGE -> Unit
    }
}

private fun <T> AccessResult<T>.requireAllowed(): T {
    if (!allowed) throw PermissionDeniedException()
    return checkNotNull(value)
}

private fun BalanceAdjustment.requireAllowed(): BalanceAdjustment {
    if (!allowed) throw PermissionDeniedException()
    return this
}

private fun LogoutResult.requireCheckout(): Checkout = when (status) {
    MutationResult.APPLIED -> checkNotNull(checkout)
    MutationResult.NO_CHANGE -> throw GuestNotActiveException()
    MutationResult.DENIED_PERMISSION -> throw PermissionDeniedException()
    MutationResult.DENIED_NEGATIVE_BALANCE -> error("Logout cannot be denied by balance")
}
