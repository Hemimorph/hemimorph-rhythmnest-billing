package io.github.hemimogph

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.ZoneId

data class DatabaseSettings(
    val jdbcUrl: String,
    val user: String,
    val password: String,
    val initialAdminId: String,
) {
    companion object {
        fun fromEnvironment(environment: (String) -> String? = System::getenv): DatabaseSettings =
            DatabaseSettings(
                jdbcUrl = environment.required("DB_JDBC_URL"),
                user = environment.required("DB_USER"),
                password = environment.required("DB_PASSWORD"),
                initialAdminId = environment.required("INITIAL_ADMIN_ID"),
            )
    }
}

class DatabaseQueue internal constructor(
    database: Database,
    internal val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    private class Task<T>(
        val block: JdbcTransaction.() -> T,
        val result: CompletableDeferred<T>,
    )

    private val tasks = Channel<Task<*>>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val worker = scope.launch {
        for (task in tasks) {
            executeTask(database, task)
        }
    }

    suspend fun <T> execute(block: JdbcTransaction.() -> T): T {
        val result = CompletableDeferred<T>()
        tasks.send(Task(block, result))
        return result.await()
    }

    internal suspend fun shutdown() {
        tasks.close()
        worker.join()
        scope.cancel()
    }

    private fun <T> executeTask(database: Database, task: Task<T>) {
        try {
            task.result.complete(transaction(database) { task.block(this) })
        } catch (failure: Throwable) {
            task.result.completeExceptionally(failure)
        }
    }
}

class DatabaseRuntime private constructor(
    private val dataSource: HikariDataSource,
    val queue: DatabaseQueue,
) {
    suspend fun shutdown() {
        dataSource.use {
            queue.shutdown()
        }
    }

    companion object {
        fun create(settings: DatabaseSettings = DatabaseSettings.fromEnvironment()): DatabaseRuntime {
            require(settings.initialAdminId.length <= 128) {
                "HERMIMORPH_BILL_INITIAL_ADMIN_ID must not exceed 128 characters"
            }
            Flyway.configure()
                .dataSource(settings.jdbcUrl, settings.user, settings.password)
                .load()
                .migrate()

            val dataSource = HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = settings.jdbcUrl
                    username = settings.user
                    password = settings.password
                    driverClassName = "org.postgresql.Driver"
                    maximumPoolSize = 1
                    minimumIdle = 1
                    poolName = "rhythmnest-database"
                    connectionTimeout = 30_000
                    initializationFailTimeout = 10_000
                },
            )

            try {
                dataSource.connection.use { connection ->
                    check(connection.isValid(5)) { "PostgreSQL connection validation failed" }
                }
                dataSource.connection.use { connection ->
                    connection.prepareStatement(
                        "INSERT INTO administrators (user_id) VALUES (?) ON CONFLICT DO NOTHING",
                    ).use { statement ->
                        statement.setString(1, settings.initialAdminId)
                        statement.executeUpdate()
                    }
                }
                val database = Database.connect(dataSource)
                return DatabaseRuntime(dataSource, DatabaseQueue(database))
            } catch (failure: Throwable) {
                dataSource.close()
                throw failure
            }
        }
    }
}
