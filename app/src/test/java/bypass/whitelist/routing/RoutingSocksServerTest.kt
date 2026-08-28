package bypass.whitelist.routing

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Drives the router with a real SOCKS5 client over real sockets.
 *
 * This is the half of split routing that had never executed: the rule lookup is
 * covered by [RuleSetTest], but nothing had ever spoken the protocol to this
 * server or watched where it sent a connection. Both stand-ins here are real
 * servers on real ports, so the handshake, the request framing and the choice
 * between tunnel and direct are all exercised as they would be in the app.
 */
class RoutingSocksServerTest {

    private lateinit var relay: FakeSocks
    private lateinit var origin: FakeOrigin
    private var server: RoutingSocksServer? = null
    private val protectedSockets = mutableListOf<Socket>()

    private val user = "u"
    private val pass = "p"

    @Before fun setUp() {
        relay = FakeSocks().also { it.start() }
        origin = FakeOrigin().also { it.start() }
    }

    @After fun tearDown() {
        server?.stop()
        relay.stop()
        origin.stop()
    }

    private fun startRouter(rules: RuleSet, overlay: UserRules = UserRules.EMPTY): Int {
        val port = freePort()
        server = RoutingSocksServer(
            listenPort = port,
            relayPort = relay.port,
            user = user,
            pass = pass,
            rules = rules,
            overlay = overlay,
            protect = { socket -> protectedSockets.add(socket); true },
        ).also { it.start() }
        return port
    }

    // ---- the cases that matter --------------------------------------------

    @Test fun `a proxied domain is handed to the relay`() {
        val port = startRouter(rules(proxy = setOf("blocked.example")))
        val client = connect(port, "blocked.example", 80)
        assertEquals("relay must have been asked for it", "blocked.example",
            relay.lastRequestedHost.get())
        assertTrue("nothing should have been dialled directly", protectedSockets.isEmpty())
        client.close()
    }

    @Test fun `an unmatched destination also takes the tunnel`() {
        // The deliberate default: no rule means tunnel, never direct.
        val port = startRouter(rules(proxy = emptySet()))
        val client = connect(port, "unlisted.example", 80)
        assertEquals("unlisted.example", relay.lastRequestedHost.get())
        client.close()
    }

    @Test fun `a direct domain bypasses the relay and is protected`() {
        val port = startRouter(rules(direct = setOf("origin.example")))
        // The name has to resolve for the direct branch to dial it, so point it
        // at the loopback origin by using an address literal.
        val client = connect(port, "127.0.0.1", origin.port, expectDirect = true)
        assertEquals("relay must not have been involved", null, relay.lastRequestedHost.get())
        assertEquals("the direct socket must be protected", 1, protectedSockets.size)
        client.close()
    }

    @Test fun `a blocked domain is refused outright`() {
        val port = startRouter(rules(block = setOf("ads.example")))
        val socket = Socket()
        socket.connect(InetSocketAddress("127.0.0.1", port), 3000)
        val input = DataInputStream(socket.getInputStream())
        greet(socket, input)
        request(socket, "ads.example", 80)
        assertEquals("version", 5, input.read())
        assertEquals("reply must be a refusal, not success", 2, input.read())
        assertTrue(relay.lastRequestedHost.get() == null)
        socket.close()
    }

    @Test fun `bad credentials are rejected`() {
        val port = startRouter(rules())
        val socket = Socket()
        socket.connect(InetSocketAddress("127.0.0.1", port), 3000)
        val input = DataInputStream(socket.getInputStream())
        socket.getOutputStream().write(byteArrayOf(5, 1, 2))
        assertEquals(5, input.read())
        assertEquals(2, input.read())
        val u = "wrong".toByteArray(); val p = "wrong".toByteArray()
        socket.getOutputStream().write(
            byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p)
        assertEquals(1, input.read())
        assertTrue("must not accept the wrong password", input.read() != 0)
        socket.close()
    }

    @Test fun `payload survives the direct path in both directions`() {
        val port = startRouter(rules(direct = setOf("origin.example")))
        val client = connect(port, "127.0.0.1", origin.port, expectDirect = true)
        client.getOutputStream().write("ping".toByteArray())
        client.getOutputStream().flush()
        val echoed = ByteArray(4)
        DataInputStream(client.getInputStream()).readFully(echoed)
        assertEquals("ping", String(echoed))
        client.close()
    }

    @Test fun `a user rule overrules the blob that disagrees with it`() {
        // The blob would send this one direct; the user put it in the block
        // list. Their line wins, or the lists are decoration.
        val port = startRouter(
            rules = rules(direct = setOf("ads.example")),
            overlay = overlay(block = listOf("ads.example")),
        )
        val socket = Socket()
        socket.connect(InetSocketAddress("127.0.0.1", port), 3000)
        val input = DataInputStream(socket.getInputStream())
        greet(socket, input)
        request(socket, "ads.example", 80)
        assertEquals("version", 5, input.read())
        assertEquals("reply must be a refusal, not success", 2, input.read())
        assertTrue(protectedSockets.isEmpty())
        socket.close()
    }

    @Test fun `a user rule can undo a block the blob applied`() {
        // The override has to work in both directions, or "user beats blob"
        // only means "the user may add restrictions".
        val port = startRouter(
            rules = rules(block = setOf("blocked.example")),
            overlay = overlay(proxy = listOf("blocked.example")),
        )
        val client = connect(port, "blocked.example", 80)
        assertEquals("blocked.example", relay.lastRequestedHost.get())
        client.close()
    }

    @Test fun `a user address rule beats the blob for a name it does not know`() {
        // Nothing carries the name, so the decision has to come off the address
        // it resolves to — and the user's prefix is asked before the blob's.
        val port = startRouter(
            rules = rules(),
            overlay = overlay(block = listOf("127.0.0.0/8")),
        )
        val socket = Socket()
        socket.connect(InetSocketAddress("127.0.0.1", port), 3000)
        val input = DataInputStream(socket.getInputStream())
        greet(socket, input)
        request(socket, "127.0.0.1", origin.port)
        assertEquals("version", 5, input.read())
        assertEquals("reply must be a refusal, not success", 2, input.read())
        assertTrue(relay.lastRequestedHost.get() == null)
        socket.close()
    }

    // ---- helpers ----------------------------------------------------------

    private fun overlay(
        proxy: List<String> = emptyList(),
        direct: List<String> = emptyList(),
        block: List<String> = emptyList(),
    ): UserRules = UserRules.build(
        RoutingConfig(splitRouting = true, proxy = proxy, direct = direct, block = block),
        CategorySource.NONE,
    )

    private fun rules(
        proxy: Set<String> = emptySet(),
        direct: Set<String> = emptySet(),
        block: Set<String> = emptySet(),
    ): RuleSet {
        val entries = ArrayList<Triple<Int, Int, String>>()
        proxy.forEach { entries.add(Triple(0, 0, it)) }
        block.forEach { entries.add(Triple(0, 1, it)) }
        direct.forEach { entries.add(Triple(0, 2, it)) }
        val parts = ArrayList<ByteArray>()
        parts.add("PCRT".toByteArray())
        parts.add(byteArrayOf(2, 0, 0, 0))
        parts.add(intLe(0) + intLe(0) + intLe(entries.size))
        for ((kind, action, value) in entries) {
            val raw = value.toByteArray()
            parts.add(byteArrayOf(kind.toByte(), action.toByte(), raw.size.toByte()) + raw)
        }
        return RuleSet.parse(parts.reduce { a, b -> a + b })
    }

    private fun intLe(v: Int) = byteArrayOf(
        v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun greet(socket: Socket, input: DataInputStream) {
        socket.getOutputStream().write(byteArrayOf(5, 1, 2))
        check(input.read() == 5)
        check(input.read() == 2)
        val u = user.toByteArray(); val p = pass.toByteArray()
        socket.getOutputStream().write(
            byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p)
        check(input.read() == 1)
        check(input.read() == 0) { "router rejected valid credentials" }
    }

    private fun request(socket: Socket, host: String, port: Int) {
        val literal = runCatching { InetAddress.getByName(host) }.getOrNull()
            ?.takeIf { host[0].isDigit() }
        val head = if (literal != null) {
            byteArrayOf(5, 1, 0, 1) + literal.address
        } else {
            val raw = host.toByteArray()
            byteArrayOf(5, 1, 0, 3, raw.size.toByte()) + raw
        }
        socket.getOutputStream().write(head + byteArrayOf((port shr 8).toByte(), port.toByte()))
        socket.getOutputStream().flush()
    }

    private fun connect(
        routerPort: Int, host: String, port: Int, expectDirect: Boolean = false,
    ): Socket {
        val socket = Socket()
        socket.connect(InetSocketAddress("127.0.0.1", routerPort), 3000)
        socket.soTimeout = 5000
        val input = DataInputStream(socket.getInputStream())
        greet(socket, input)
        request(socket, host, port)
        assertEquals("version", 5, input.read())
        assertEquals("connect must succeed", 0, input.read())
        input.read()                       // reserved
        when (input.read()) {              // bound address, length varies
            1 -> input.skipBytes(4 + 2)
            4 -> input.skipBytes(16 + 2)
            3 -> input.skipBytes(input.read() + 2)
        }
        if (expectDirect) assertTrue(relay.lastRequestedHost.get() == null)
        return socket
    }

    /** Stands in for the relay's SOCKS5: accepts, records the target, echoes. */
    private class FakeSocks {
        val port = ServerSocket(0).use { it.localPort }
        val lastRequestedHost = AtomicReference<String?>(null)
        private var server: ServerSocket? = null

        fun start() {
            val s = ServerSocket()
            s.reuseAddress = true
            s.bind(InetSocketAddress("127.0.0.1", port))
            server = s
            thread(isDaemon = true) {
                while (true) {
                    val c = try { s.accept() } catch (e: Exception) { break }
                    thread(isDaemon = true) { serve(c) }
                }
            }
        }

        private fun serve(c: Socket) {
            val input = DataInputStream(c.getInputStream())
            val out = c.getOutputStream()
            input.read(); val n = input.read(); input.skipBytes(n)
            out.write(byteArrayOf(5, 2)); out.flush()
            input.read()
            val ul = input.read(); input.skipBytes(ul)
            val pl = input.read(); input.skipBytes(pl)
            out.write(byteArrayOf(1, 0)); out.flush()
            input.read(); input.read(); input.read()
            val host = when (input.read()) {
                1 -> ByteArray(4).also { input.readFully(it) }
                    .let { InetAddress.getByAddress(it).hostAddress!! }
                3 -> ByteArray(input.read()).also { input.readFully(it) }.let { String(it) }
                else -> ByteArray(16).also { input.readFully(it) }
                    .let { InetAddress.getByAddress(it).hostAddress!! }
            }
            input.read(); input.read()
            lastRequestedHost.set(host)
            out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); out.flush()
            runCatching { input.copyTo(out) }
        }

        fun stop() { runCatching { server?.close() } }
    }

    /** Stands in for a destination reached directly: echoes whatever it gets. */
    private class FakeOrigin {
        val port = ServerSocket(0).use { it.localPort }
        private var server: ServerSocket? = null
        val ready = CountDownLatch(1)

        fun start() {
            val s = ServerSocket()
            s.reuseAddress = true
            s.bind(InetSocketAddress("127.0.0.1", port))
            server = s
            thread(isDaemon = true) {
                ready.countDown()
                while (true) {
                    val c = try { s.accept() } catch (e: Exception) { break }
                    thread(isDaemon = true) { runCatching { c.getInputStream().copyTo(c.getOutputStream()) } }
                }
            }
            ready.await(2, TimeUnit.SECONDS)
        }

        fun stop() { runCatching { server?.close() } }
    }
}
