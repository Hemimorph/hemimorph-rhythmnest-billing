package io.github.hemimogph

import io.ktor.server.application.Application
import io.ktor.util.AttributeKey

private val DatabaseQueueKey = AttributeKey<DatabaseQueue>("DatabaseQueue")

val Application.databaseQueue: DatabaseQueue
    get() = attributes[DatabaseQueueKey]

fun Application.rootModule(apiToken: String) {
    configureApi(apiToken)
    configureRouting()
}

fun Application.rootModule(databaseQueue: DatabaseQueue, apiToken: String) {
    attributes.put(DatabaseQueueKey, databaseQueue)
    rootModule(apiToken)
}
