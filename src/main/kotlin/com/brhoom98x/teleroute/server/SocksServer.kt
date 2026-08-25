package com.brhoom98x.teleroute.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * SOCKS5 relay that steers Telegram connections onto the fastest measured address of the same
 * data centre, the same trick the Android app does on loopback -- but reachable from a phone that
 * cannot run the proxy itself, which is the whole point on iOS.
 *
 * Three things differ from the on-device version, all because this socket faces the internet:
 *
 *  1. **Username/password authentication is required** (RFC 1929), not optional. Anonymous SOCKS5
 *     on a public port is an open relay.
 *  2. **Non-Telegram destinations are refused** by default. The Android build relays anything
 *     because only apps on that phone can reach loopback; here, narrowing the destination set is
 *     what stops a leaked password from becoming a general-purpose relay for someone else.
 *  3. **Concurrent connections are capped**, so a single client cannot exhaust file descriptors.
 */
class SocksServer(
    private val config: Config,
    private val routes: () -> List<RouteResult>
) {
    private var serverSocket: ServerSocket? = null
    private var scope: CoroutineScope? = null
    private val liveConnections = AtomicInteger(0)

    private val expectedUser = config.username.toByteArray(Charsets.UTF_8)
    private val expectedPass = config.password.toByteArray(Charsets.UTF_8)

    /** Binds and starts accepting. Returns the port actually bound. */
    fun start(): Int {
        stop()
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(
            InetSocketAddress(InetAddress.getByName(config.bindAddress), config.port),
            BACKLOG
        )
        serverSocket = socket

        val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = serverScope
        serverScope.launch { acceptLoop(socket, serverScope) }
        return socket.localPort
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        scope?.cancel()
        scope = null
    }

    private fun acceptLoop(server: ServerSocket, serverScope: CoroutineScope) {
        while (serverScope.isActive && !server.isClosed) {
            val client = try {
                server.accept()
            } catch (_: Exception) {
                break
            }
            if (liveConnections.get() >= MAX_CONNECTIONS) {
                Log.warn("Refusing " + client.remoteSocketAddress + ": connection cap reached")
                runCatching { client.close() }
                continue
            }
            liveConnections.incrementAndGet()
            serverScope.launch {
                try {
                    handle(client)
                } finally {
                    liveConnections.decrementAndGet()
                }
            }
        }
    }

    private fun handle(client: Socket) {
        val peer = client.remoteSocketAddress?.toString() ?: "unknown"
        var upstream: Socket? = null
        try {
            client.tcpNoDelay = true
            // Bounded until the client has proven itself, so a silent peer cannot hold a slot.
            client.soTimeout = HANDSHAKE_TIMEOUT_MS
            val input = client.getInputStream()
            val output = client.getOutputStream()

            if (!negotiate(input, output)) {
                Log.warn("Rejected " + peer + ": no acceptable auth method")
                return
            }
            if (!authenticate(input, output)) {
                Log.warn("Rejected " + peer + ": bad credentials")
                return
            }

            val request = readRequest(input)
            if (request == null) {
                reply(output, REP_COMMAND_NOT_SUPPORTED)
                return
            }

            if (!isAllowed(request)) {
                Log.warn("Refused " + peer + " -> " + request.host + ":" + request.port + ": not a Telegram destination")
                reply(output, REP_NOT_ALLOWED)
                return
            }

            upstream = connectUpstream(request)
            if (upstream == null) {
                reply(output, REP_HOST_UNREACHABLE)
                return
            }

            reply(output, REP_SUCCEEDED)
            client.soTimeout = 0
            relay(client, upstream)
        } catch (_: Exception) {
            // Client vanished, or spoke something other than SOCKS5. Nothing useful to report.
        } finally {
            runCatching { client.close() }
            runCatching { upstream?.close() }
        }
    }

    /** SOCKS5 greeting. Only username/password is offered; anonymous is never acceptable. */
    private fun negotiate(input: InputStream, output: OutputStream): Boolean {
        val head = input.readExact(2)
        if (head[0].toInt() != SOCKS_VERSION) return false
        val methodCount = head[1].toInt() and 0xFF
        val methods = if (methodCount > 0) input.readExact(methodCount) else ByteArray(0)

        if (methods.none { it == METHOD_USERNAME_PASSWORD }) {
            output.write(byteArrayOf(SOCKS_VERSION.toByte(), METHOD_NONE_ACCEPTABLE))
            output.flush()
            return false
        }
        output.write(byteArrayOf(SOCKS_VERSION.toByte(), METHOD_USERNAME_PASSWORD))
        output.flush()
        return true
    }

    /**
     * RFC 1929 username/password sub-negotiation.
     *
     * Both fields are compared with [MessageDigest.isEqual], which does not return early on the
     * first differing byte -- a plain `==` leaks how much of a guessed password was correct
     * through response timing, and this endpoint is reachable by anyone who finds the port.
     */
    private fun authenticate(input: InputStream, output: OutputStream): Boolean {
        val version = input.readExact(1)[0].toInt() and 0xFF
        if (version != AUTH_VERSION) return false

        val userLength = input.readExact(1)[0].toInt() and 0xFF
        val user = input.readExact(userLength)
        val passLength = input.readExact(1)[0].toInt() and 0xFF
        val pass = input.readExact(passLength)

        val ok = MessageDigest.isEqual(user, expectedUser) && MessageDigest.isEqual(pass, expectedPass)
        output.write(byteArrayOf(AUTH_VERSION.toByte(), if (ok) AUTH_SUCCESS else AUTH_FAILURE))
        output.flush()
        return ok
    }

    private fun readRequest(input: InputStream): Destination? {
        val head = input.readExact(4)
        if (head[0].toInt() != SOCKS_VERSION) return null
        if (head[1].toInt() != CMD_CONNECT) return null

        val addressType = head[3].toInt() and 0xFF
        val host = when (addressType) {
            ATYP_IPV4 -> input.readExact(4).joinToString(".") { (it.toInt() and 0xFF).toString() }
            ATYP_DOMAIN -> {
                val length = input.readExact(1)[0].toInt() and 0xFF
                String(input.readExact(length), Charsets.US_ASCII)
            }
            ATYP_IPV6 -> InetAddress.getByAddress(input.readExact(16)).hostAddress ?: return null
            else -> return null
        }
        val portBytes = input.readExact(2)
        val port = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)
        return Destination(host, port)
    }

    /**
     * The destination policy. Telegram hands its clients literal IPv4 front-end addresses, so a
     * request that is not a Telegram IPv4 address is not Telegram traffic and gets refused --
     * that is what keeps this from being a general-purpose relay.
     */
    internal fun isAllowed(destination: Destination): Boolean {
        if (config.allowNonTelegram) return true
        if (!TelegramRoutes.isIpv4(destination.host)) return false
        return TelegramRoutes.isTelegram(destination.host)
    }

    /**
     * Ordered connection attempts: fastest measured routes of the requested DC first, then the
     * address the client actually asked for.
     */
    private fun candidatesFor(destination: Destination): List<InetSocketAddress> {
        val original = InetSocketAddress(destination.host, destination.port)
        if (!TelegramRoutes.isIpv4(destination.host)) return listOf(original)
        val dc = TelegramRoutes.dcFor(destination.host) ?: return listOf(original)

        val ranked = routes()
            .filter { it.ok && it.route.dc == dc }
            .map { InetSocketAddress(it.route.ip, it.route.port) }

        return (ranked + original).distinct().take(MAX_ATTEMPTS)
    }

    private fun connectUpstream(destination: Destination): Socket? {
        for (address in candidatesFor(destination)) {
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.connect(address, CONNECT_TIMEOUT_MS)
                val reachedHost = address.address?.hostAddress
                if (reachedHost != destination.host || address.port != destination.port) {
                    Log.info(
                        "Routed " + destination.host + ":" + destination.port +
                            " via " + reachedHost + ":" + address.port
                    )
                }
                return socket
            } catch (_: Exception) {
                runCatching { socket.close() }
            }
        }
        Log.warn("No route to " + destination.host + ":" + destination.port)
        return null
    }

    private fun reply(output: OutputStream, code: Byte) {
        // VER, REP, RSV, ATYP=IPv4, BND.ADDR 0.0.0.0, BND.PORT 0
        output.write(
            byteArrayOf(SOCKS_VERSION.toByte(), code, 0, ATYP_IPV4.toByte(), 0, 0, 0, 0, 0, 0)
        )
        output.flush()
    }

    private fun relay(client: Socket, upstream: Socket) {
        val up = Thread { pump(client, upstream) }
        val down = Thread { pump(upstream, client) }
        up.start()
        down.start()
        up.join()
        down.join()
    }

    private fun pump(from: Socket, to: Socket) {
        val buffer = ByteArray(BUFFER_SIZE)
        try {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                output.flush()
            }
            runCatching { to.shutdownOutput() }
        } catch (_: Exception) {
            // Either side closed; handle() closes both sockets in its finally block.
        } finally {
            runCatching { from.close() }
            runCatching { to.close() }
        }
    }

    private fun InputStream.readExact(count: Int): ByteArray {
        val buffer = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = read(buffer, offset, count - offset)
            if (read < 0) throw EOFException("stream closed early")
            offset += read
        }
        return buffer
    }

    internal data class Destination(val host: String, val port: Int)

    private companion object {
        const val SOCKS_VERSION = 5
        const val AUTH_VERSION = 1
        const val METHOD_USERNAME_PASSWORD: Byte = 2
        const val METHOD_NONE_ACCEPTABLE: Byte = -1 // 0xFF
        const val AUTH_SUCCESS: Byte = 0
        const val AUTH_FAILURE: Byte = 1

        const val CMD_CONNECT = 1
        const val ATYP_IPV4 = 1
        const val ATYP_DOMAIN = 3
        const val ATYP_IPV6 = 4

        const val REP_SUCCEEDED: Byte = 0
        const val REP_NOT_ALLOWED: Byte = 2
        const val REP_HOST_UNREACHABLE: Byte = 4
        const val REP_COMMAND_NOT_SUPPORTED: Byte = 7

        const val BACKLOG = 128
        const val BUFFER_SIZE = 32 * 1024
        const val CONNECT_TIMEOUT_MS = 6000
        const val HANDSHAKE_TIMEOUT_MS = 15000
        const val MAX_ATTEMPTS = 4
        const val MAX_CONNECTIONS = 512
    }
}
