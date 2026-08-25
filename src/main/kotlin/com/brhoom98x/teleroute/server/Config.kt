package com.brhoom98x.teleroute.server

import java.io.File
import java.util.Properties

/**
 * Runtime configuration, read from a properties file and overridable per key by environment
 * variable (`TELEROUTE_PORT`, `TELEROUTE_USERNAME`, ...).
 *
 * Two defaults here are deliberately strict rather than convenient, because this build listens on
 * a public interface rather than on loopback like the Android one:
 *
 *  - **Credentials are mandatory.** A SOCKS5 proxy reachable from the internet with no
 *    authentication is an open relay: anyone who finds the port can push traffic through your
 *    server's IP address, and scanners find open proxies within hours. There is no
 *    "just for testing" mode.
 *  - **Only Telegram destinations are relayed.** The proxy exists to reach Telegram, so refusing
 *    everything else means a leaked password costs you Telegram bandwidth rather than an open
 *    relay someone can point at anything.
 */
data class Config(
    val bindAddress: String,
    val port: Int,
    val username: String,
    val password: String,
    val scanIntervalSeconds: Long,
    val allowNonTelegram: Boolean
) {
    companion object {

        fun load(path: String?): Config {
            val properties = Properties()
            if (path != null) {
                val file = File(path)
                require(file.isFile) { "Config file not found: " + file.absolutePath }
                file.inputStream().use { properties.load(it) }
            }

            fun value(key: String, envKey: String, fallback: String? = null): String? =
                System.getenv(envKey) ?: properties.getProperty(key) ?: fallback

            val username = value("username", "TELEROUTE_USERNAME").orEmpty()
            val password = value("password", "TELEROUTE_PASSWORD").orEmpty()

            require(username.isNotBlank() && password.isNotBlank()) {
                "username and password are required. A SOCKS5 proxy on a public interface with no " +
                    "authentication is an open relay; set them in the config file or as " +
                    "TELEROUTE_USERNAME / TELEROUTE_PASSWORD."
            }
            require(password.length >= MIN_PASSWORD_LENGTH) {
                "password must be at least " + MIN_PASSWORD_LENGTH + " characters. This credential " +
                    "is the only thing between the internet and your bandwidth."
            }

            val port = value("port", "TELEROUTE_PORT", DEFAULT_PORT.toString())!!.trim().toInt()
            require(port in 1..65535) { "port out of range: " + port }

            return Config(
                bindAddress = value("bind", "TELEROUTE_BIND", DEFAULT_BIND)!!.trim(),
                port = port,
                username = username,
                password = password,
                scanIntervalSeconds = value(
                    "scanIntervalSeconds",
                    "TELEROUTE_SCAN_INTERVAL",
                    DEFAULT_SCAN_INTERVAL_SECONDS.toString()
                )!!.trim().toLong(),
                allowNonTelegram = value(
                    "allowNonTelegram",
                    "TELEROUTE_ALLOW_NON_TELEGRAM",
                    "false"
                )!!.trim().toBoolean()
            )
        }

        const val DEFAULT_PORT = 19808
        const val DEFAULT_BIND = "0.0.0.0"
        const val DEFAULT_SCAN_INTERVAL_SECONDS = 300L
        const val MIN_PASSWORD_LENGTH = 12
    }
}
