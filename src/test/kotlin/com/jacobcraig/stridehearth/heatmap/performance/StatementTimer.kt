package com.jacobcraig.stridehearth.heatmap.performance

import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import javax.sql.DataSource

/** A parameter binding a statement received, such as `setLong(3, 42)`, so it can be replayed on another one. */
class Binding(
    private val method: Method,
    private val args: Array<Any?>,
) {
    fun bindTo(statement: PreparedStatement) {
        method.invoke(statement, *args)
    }
}

/** A statement a renderer ran: its SQL, its parameter bindings, and how long it took to run and fetch. */
class RecordedStatement(
    val sql: String,
    val bindings: List<Binding>,
    val millis: Double,
)

/**
 * Wraps a [DataSource] so the harness sees what a [com.jacobcraig.stridehearth.heatmap.HeatmapTileRenderer]
 * asks of PostgreSQL without knowing its SQL: the time each thread spends in statements and, on request, every
 * statement with its bindings, so the harness can `EXPLAIN ANALYZE` exactly what the renderer ran.
 */
class StatementTimer(
    target: DataSource,
) {
    private val databaseNanos = ThreadLocal.withInitial { 0L }
    private val recording = ThreadLocal<MutableList<RecordedStatement>?>()

    val dataSource: DataSource =
        proxy(DataSource::class.java) { method, args ->
            val result = forward(target, method, args)
            if (method.name == "getConnection") connection(result as Connection) else result
        }

    /** Runs [block] and returns its result with the milliseconds this thread spent in statements meanwhile. */
    fun <T> timed(block: () -> T): Pair<T, Double> {
        databaseNanos.set(0L)
        val result = block()
        return result to databaseNanos.get() / 1e6
    }

    /** Runs [block] and returns the statements this thread ran meanwhile. */
    fun recorded(block: () -> Unit): List<RecordedStatement> {
        val statements = mutableListOf<RecordedStatement>()
        recording.set(statements)
        try {
            block()
        } finally {
            recording.remove()
        }
        return statements
    }

    private fun connection(connection: Connection): Connection =
        proxy(Connection::class.java) { method, args ->
            val result = forward(connection, method, args)
            if (method.name == "prepareStatement") statement(result as PreparedStatement, args!![0] as String) else result
        }

    private fun statement(
        statement: PreparedStatement,
        sql: String,
    ): PreparedStatement {
        val bindings = mutableListOf<Binding>()
        return proxy(PreparedStatement::class.java) { method, args ->
            if (method.name.startsWith("set") && method.parameterCount >= 2 && method.parameterTypes[0] == Int::class.java) {
                bindings.add(Binding(method, args!!.clone()))
            }
            if (method.name.startsWith("execute") && method.parameterCount == 0) {
                val started = System.nanoTime()
                val result = forward(statement, method, args)
                val nanos = System.nanoTime() - started
                databaseNanos.set(databaseNanos.get() + nanos)
                recording.get()?.add(RecordedStatement(sql, bindings.toList(), nanos / 1e6))
                result
            } else {
                forward(statement, method, args)
            }
        }
    }

    private fun <T : Any> proxy(
        type: Class<T>,
        handle: (Method, Array<Any?>?) -> Any?,
    ): T =
        type.cast(
            Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(type),
                InvocationHandler {
                    _,
                    method,
                    args,
                    ->
                    handle(method, args)
                },
            ),
        )

    private fun forward(
        target: Any,
        method: Method,
        args: Array<Any?>?,
    ): Any? =
        try {
            method.invoke(target, *(args ?: emptyArray()))
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
}
