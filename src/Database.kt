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
import org.slf4j.LoggerFactory
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.nanoseconds

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
        val id: Long,
        val queuedAtNanos: Long,
        val operation: String,
        val input: String,
        val block: JdbcTransaction.() -> T,
        val result: CompletableDeferred<T>,
    )

    private val logger = LoggerFactory.getLogger(DatabaseQueue::class.java)
    private val transactionSequence = AtomicLong()
    private val tasks = Channel<Task<*>>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val worker = scope.launch {
        for (task in tasks) {
            executeTask(database, task)
        }
    }

    suspend fun <T> execute(
        operation: String = "anonymous",
        parameters: Map<String, Any?> = emptyMap(),
        block: JdbcTransaction.() -> T,
    ): T {
        val transactionId = transactionSequence.incrementAndGet()
        val result = CompletableDeferred<T>()
        val input = parameters.toSafeLogValue()
        logger.info(
            "Database transaction queued transactionId={} operation={} input={}",
            transactionId,
            operation,
            input,
        )
        tasks.send(Task(transactionId, System.nanoTime(), operation, input, block, result))
        return result.await()
    }

    internal suspend fun shutdown() {
        tasks.close()
        worker.join()
        scope.cancel()
    }

    private fun <T> executeTask(database: Database, task: Task<T>) {
        val startedAtNanos = System.nanoTime()
        logger.info(
            "Database transaction started transactionId={} operation={} input={} queueWaitMs={}",
            task.id,
            task.operation,
            task.input,
            (startedAtNanos - task.queuedAtNanos).nanoseconds.inWholeMilliseconds,
        )
        try {
            val value = transaction(database) { task.block(this) }
            logger.info(
                "Database transaction committed transactionId={} operation={} input={} output={} durationMs={}",
                task.id,
                task.operation,
                task.input,
                value.toSafeLogValue(),
                (System.nanoTime() - startedAtNanos).nanoseconds.inWholeMilliseconds,
            )
            task.result.complete(value)
        } catch (failure: Throwable) {
            logger.error(
                "Database transaction rolled back transactionId={} operation={} input={} durationMs={} failureType={} failureMessage={}",
                task.id,
                task.operation,
                task.input,
                (System.nanoTime() - startedAtNanos).nanoseconds.inWholeMilliseconds,
                failure::class.qualifiedName,
                failure.message.toSafeLogValue(),
            )
            task.result.completeExceptionally(failure)
        }
    }
}

private fun Any?.toSafeLogValue(): String {
    val sanitized = toString().replace('\r', ' ').replace('\n', ' ')
    return if (sanitized.length <= 16_384) sanitized else sanitized.take(16_384) + "<truncated>"
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
