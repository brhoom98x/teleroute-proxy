package com.brhoom98x.teleroute.server

import java.time.Instant

/**
 * Line-per-event logging to stdout, which is what systemd captures into the journal. No logging
 * framework: this process has one job and a dependency that needs its own configuration file
 * would be a bigger moving part than the thing it is describing.
 *
 * Nothing here ever logs the configured credentials.
 */
object Log {
    fun info(message: String) = write("INFO ", message)
    fun warn(message: String) = write("WARN ", message)
    fun error(message: String) = write("ERROR", message)

    private fun write(level: String, message: String) {
        println(Instant.now().toString() + " " + level + " " + message)
        System.out.flush()
    }
}
