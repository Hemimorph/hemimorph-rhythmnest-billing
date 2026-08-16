package io.github.hemimogph

import io.ktor.server.application.Application
import io.ktor.util.AttributeKey

private val DatabaseQueueKey = AttributeKey<DatabaseQueue>("DatabaseQueue")

val Application.databaseQueue: DatabaseQueue
    get() = attributes[DatabaseQueueKey]

fun Application.rootModule(databaseQueue: DatabaseQueue, apiToken: String) {
    attributes.put(DatabaseQueueKey, databaseQueue)
    configureApi(apiToken)
    configureRouting()
}
