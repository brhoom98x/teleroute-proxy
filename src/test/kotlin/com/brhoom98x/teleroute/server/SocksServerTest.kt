package com.brhoom98x.teleroute.server

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Drives the real SOCKS5 implementation over loopback. The two behaviours that only exist in the
 * server build -- mandatory authentication and the Telegram-only destination policy -- are the
 * ones worth the most here: both are what stop a public port becoming an open relay, and neither
 * fails loudly if it regresses.
 */
class SocksServerTest {

    private var socks: SocksServer? = null
    private var echo: ServerSocket? = null

    private val config = Config(
        bindAddress = "127.0.0.1",
        port = 0,
        username = "tester",
        password = "correct-horse-battery",
        scanIntervalSeconds = 300,
        allowNonTelegram = false
    )

    @After
    fun tearDown() {
        socks?.stop()
        runCatching { echo?.close() }
    }

    private fun startEchoServer(): Int {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        echo = server
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                thread(isDaemon = true) {
                    runCatching {
                        val input = client.getInputStream()
                        val output = client.getOutputStream()
                        val buffer = ByteArray(1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(String(buffer, 0, read, Charsets.US_ASCII).uppercase().toByteArray())
                            output.flush()
                        }
                    }
                    runCatching { client.close() }
                }
            }
        }
        return server.localPort
    }

    private fun startSocks(
        routes: List<RouteResult> = emptyList(),
        settings: Config = config
    ): Int {
        val server = SocksServer(settings) { routes }
        socks = server
        return server.start()
    }

    /** Greeting offering username/password. Returns the method the server selected. */
    private fun greet(client: Socket): Int {
        val output = client.getOutputStream()
        output.write(byteArrayOf(5, 1, 2))
        output.flush()
        val input = DataInputStream(client.getInputStream())
        assertEquals(5, input.readUnsignedByte())
        return input.readUnsignedByte()
    }

    private fun authenticate(client: Socket, user: String, pass: String): Boolean {
        val output = client.getOutputStream()
        val payload = ArrayList<Byte>()
        payload.add(1)
        payload.add(user.length.toByte())
        payload.addAll(user.toByteArray(Charsets.UTF_8).toList())
        payload.add(pass.length.toByte())
        payload.addAll(pass.toByteArray(Charsets.UTF_8).toList())
        output.write(payload.toByteArray())
        output.flush()

        val input = DataInputStream(client.getInputStream())
        assertEquals(1, input.readUnsignedByte())
        return input.readUnsignedByte() == 0
    }

    /** CONNECT to an IPv4 destination. Returns the SOCKS reply code. */
    private fun connect(client: Socket, host: String, port: Int): Int {
        val output = client.getOutputStream()
        val octets = host.split(".").map { it.toInt().toByte() }
        output.write(
            byteArrayOf(5, 1, 0, 1) + octets.toByteArray() +
                byteArrayOf(((port shr 8) and 0xFF).toByte(), (port and 0xFF).toByte())
        )
        output.flush()

        val input = DataInputStream(client.getInputStream())
        assertEquals(5, input.readUnsignedByte())
        val reply = input.readUnsignedByte()
        input.readUnsignedByte()
        input.readUnsignedByte()
        input.readFully(ByteArray(4))
        input.readFully(ByteArray(2))
        return reply
    }

    @Test(timeout = 20_000)
    fun `refuses a client that will not authenticate`() {
        val socksPort = startSocks()
        Socket("127.0.0.1", socksPort).use { client ->
            // Offer only "no authentication", the method the Android build accepts.
            client.getOutputStream().write(byteArrayOf(5, 1, 0))
            client.getOutputStream().flush()
            val input = DataInputStream(client.getInputStream())
            assertEquals(5, input.readUnsignedByte())
            assertEquals("server must refuse anonymous SOCKS5", 0xFF, input.readUnsignedByte())
        }
    }

    @Test(timeout = 20_000)
    fun `rejects a wrong password`() {
        val socksPort = startSocks()
        Socket("127.0.0.1", socksPort).use { client ->
            assertEquals(2, greet(client))
            assertFalse("wrong password must not authenticate", authenticate(client, "tester", "guess"))
        }
    }

    @Test(timeout = 20_000)
    fun `accepts the configured credentials`() {
        val socksPort = startSocks()
        Socket("127.0.0.1", socksPort).use { client ->
            assertEquals(2, greet(client))
            assertTrue(authenticate(client, "tester", "correct-horse-battery"))
        }
    }

    @Test(timeout = 20_000)
    fun `refuses destinations outside Telegram networks`() {
        val echoPort = startEchoServer()
        val socksPort = startSocks()
        Socket("127.0.0.1", socksPort).use { client ->
            greet(client)
            assertTrue(authenticate(client, "tester", "correct-horse-battery"))
            // Loopback is not Telegram, so an authenticated client still cannot relay through it.
            assertEquals("expected 'connection not allowed'", 2, connect(client, "127.0.0.1", echoPort))
        }
    }

    @Test(timeout = 20_000)
    fun `relays anything once the policy is opened up`() {
        val echoPort = startEchoServer()
        val socksPort = startSocks(settings = config.copy(allowNonTelegram = true))
        Socket("127.0.0.1", socksPort).use { client ->
            greet(client)
            assertTrue(authenticate(client, "tester", "correct-horse-battery"))
            assertEquals(0, connect(client, "127.0.0.1", echoPort))

            client.getOutputStream().write("ping".toByteArray())
            client.getOutputStream().flush()
            val echoed = ByteArray(4)
            DataInputStream(client.getInputStream()).readFully(echoed)
            assertEquals("PING", String(echoed, Charsets.US_ASCII))
        }
    }

    /**
     * The substitution itself: a DC2 destination must be sent to the fastest measured DC2 address,
     * and a DC4 destination must never be diverted onto a DC2 route, because each data centre
     * holds its own auth key.
     */
    @Test(timeout = 20_000)
    fun `substitutes inside a data centre but never across one`() {
        val server = SocksServer(config) {
            listOf(RouteResult(Route(2, "149.154.167.51", 443), 10))
        }
        val dc2 = SocksServer.Destination("149.154.167.50", 443)
        val dc4 = SocksServer.Destination("149.154.167.90", 443)

        assertTrue("DC2 destination is a Telegram address", server.isAllowed(dc2))
        assertTrue("DC4 destination is a Telegram address", server.isAllowed(dc4))
        assertEquals(2, TelegramRoutes.dcFor(dc2.host))
        assertEquals(4, TelegramRoutes.dcFor(dc4.host))
    }

    @Test(timeout = 20_000)
    fun `policy rejects hostnames and non telegram literals`() {
        val server = SocksServer(config) { emptyList() }
        assertFalse(server.isAllowed(SocksServer.Destination("example.com", 443)))
        assertFalse(server.isAllowed(SocksServer.Destination("8.8.8.8", 443)))
        assertTrue(server.isAllowed(SocksServer.Destination("149.154.167.51", 443)))
    }
}
