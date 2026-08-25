package com.brhoom98x.teleroute.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.InetSocketAddress
import java.net.Socket

/** Measures TCP handshake latency to every candidate Telegram front-end. */
object RouteScanner {

    suspend fun scan(timeoutMs: Int = 1500, parallelism: Int = 12): List<RouteResult> =
        coroutineScope {
            val gate = Semaphore(parallelism)
            TelegramRoutes.ALL
                .map { route -> async(Dispatchers.IO) { gate.withPermit { probe(route, timeoutMs) } } }
                .awaitAll()
                .sortedWith(compareBy<RouteResult>({ !it.ok }, { it.latencyMs }))
        }

    private fun probe(route: Route, timeoutMs: Int): RouteResult {
        val socket = Socket()
        return try {
            val started = System.nanoTime()
            socket.connect(InetSocketAddress(route.ip, route.port), timeoutMs)
            RouteResult(route, ((System.nanoTime() - started) / 1_000_000L).toInt())
        } catch (_: Exception) {
            RouteResult(route, -1)
        } finally {
            runCatching { socket.close() }
        }
    }
}
