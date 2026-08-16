package io.github.hemimogph

import kotlinx.coroutines.runBlocking
import io.ktor.server.engine.*

fun main(): Unit = runBlocking {
    val serverSettings = ServerSettings.fromEnvironment()
    val databaseRuntime = DatabaseRuntime.create()
    try {
        embeddedServer(
            factory = io.ktor.server.netty.Netty,
            port = serverSettings.port,
            host = serverSettings.host,
            module = { rootModule(databaseRuntime.queue, serverSettings.apiToken) },
        ).start(wait = true)
    } finally {
        databaseRuntime.shutdown()
    }
}
