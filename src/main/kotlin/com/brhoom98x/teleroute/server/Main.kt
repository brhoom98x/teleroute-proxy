package com.brhoom98x.teleroute.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

/**
 * Entry point.
 *
 *   teleroute-proxy [/etc/teleroute/teleroute.conf]
 *
 * Runs the same route-steering logic as the Android app, but as a long-lived SOCKS5 server that a
 * phone points at over the network. That is the only shape that works for iOS, where an app cannot
 * keep a loopback listener alive in the background for Telegram to connect to.
 */
fun main(args: Array<String>) {
    val config = try {
        Config.load(args.firstOrNull())
    } catch (e: Exception) {
        System.err.println("Configuration error: " + (e.message ?: e.toString()))
        exitProcess(2)
    }

    val ranked = AtomicReference<List<RouteResult>>(emptyList())
    val server = SocksServer(config) { ranked.get() }

    val boundPort = try {
        server.start()
    } catch (e: Exception) {
        Log.error("Could not bind " + config.bindAddress + ":" + config.port + " - " + (e.message ?: e.toString()))
        exitProcess(1)
    }

    Log.info("TeleRoute proxy listening on " + config.bindAddress + ":" + boundPort)
    Log.info(
        if (config.allowNonTelegram) {
            "Destination policy: ANY. This is an open relay to anyone holding the password - only " +
                "run it this way on a trusted network."
        } else {
            "Destination policy: Telegram networks only"
        }
    )

    Runtime.getRuntime().addShutdownHook(
        Thread {
            Log.info("Shutting down")
            server.stop()
        }
    )

    runBlocking {
        launch(Dispatchers.IO) {
            while (isActive) {
                val results = runCatching { RouteScanner.scan() }.getOrElse {
                    Log.warn("Scan failed: " + (it.message ?: it.toString()))
                    emptyList()
                }
                if (results.isNotEmpty()) {
                    ranked.set(results)
                    val reachable = results.count { r -> r.ok }
                    val best = results.firstOrNull { r -> r.ok }
                    Log.info(
                        "Scanned " + results.size + " routes, " + reachable + " answering" +
                            (best?.let { b -> ", best DC" + b.route.dc + " " + b.route.label + " at " + b.latencyMs + " ms" } ?: "")
                    )
                }
                delay(config.scanIntervalSeconds * 1000)
            }
        }
    }
}
