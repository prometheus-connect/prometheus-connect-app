package bypass.whitelist.routing

import android.util.Log
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * A SOCKS5 front end that decides, per connection, whether it takes the tunnel.
 *
 * It sits between tun2socks and the relay:
 *
 *     tun2socks -> RoutingSocksServer -+- matched  -> relay SOCKS5 -> tunnel
 *                                      \- unmatched -> protected socket -> direct
 *
 * The split cannot be done with VpnService routes: the rule set is ~110 000
 * prefixes and Android's route table is budgeted in thousands. Doing it here
 * costs one hash lookup per connection and nothing per packet.
 *
 * **TCP only.** A UDP ASSOCIATE request is passed to the relay untouched, so UDP
 * — DNS and QUIC included — always takes the tunnel. That is the conservative
 * side to err on: DNS in the tunnel cannot leak which sites are being resolved,
 * and QUIC falls back to TCP often enough that the cost is small. Splitting UDP
 * would mean relaying datagrams by hand for no benefit worth the bug surface.
 */
class RoutingSocksServer(
    private val listenPort: Int,
    private val relayPort: Int,
    private val user: String,
    private val pass: String,
    private val rules: RuleSet,
    /**
     * The user's own three lists, asked before the blob.
     *
     * Empty by default, which is the blob-only behaviour this server had before
     * the lists existed. Anything else would make an untouched install route by
     * whatever happened to be typed into a screen it never opened.
     */
    private val overlay: UserRules = UserRules.EMPTY,
    /** VpnService.protect — without it a direct socket loops back into the tunnel. */
    private val protect: (Socket) -> Boolean,
    /**
     * Diagnostics into the app's own log, not just logcat.
     *
     * This server passed six integration tests and still carried nothing on a
     * real device: the test client and the server were written by the same hand
     * from the same reading of the spec, so a misreading agreed with itself.
     * What is actually needed is a trace of the real tun2socks handshake, and
     * it has to reach somewhere the user can send back.
     */
    private val trace: (String) -> Unit = {},
) {

    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), listenPort))
        server = socket
        Log.i(TAG, "routing socks on 127.0.0.1:$listenPort -> relay :$relayPort, " +
            "${rules.size} rules, ${overlay.size} from the user's lists")
        trace("split routing: listening on 127.0.0.1:$listenPort, relay :$relayPort, " +
            "${rules.size} rules, ${overlay.size} from the user's lists")
        thread(name = "routing-socks", isDaemon = true) {
            while (running.get()) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    if (running.get()) trace("split routing: accept failed — ${e.message}")
                    break
                }
                pool.execute { runCatching { handle(client) }.onFailure { close(client) } }
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { server?.close() }
        pool.shutdownNow()
    }

    // ---- one connection ---------------------------------------------------

    private fun handle(client: Socket) {
        client.tcpNoDelay = true
        val input = DataInputStream(client.getInputStream().buffered())
        val output = client.getOutputStream()

        if (!greet(input, output)) {
            trace("socks: greeting rejected")
            close(client); return
        }

        // Request: VER CMD RSV ATYP ADDR PORT
        val version = input.read()
        val command = input.read()
        input.read() // reserved
        if (version != 5) { close(client); return }
        val atyp = input.read()
        trace("socks: ver=$version cmd=$command atyp=$atyp")
        val (host, rawAddr) = readAddress(input, atyp) ?: run {
            trace("socks: unsupported address type $atyp")
            close(client); return
        }
        val port = (input.read() shl 8) or input.read()

        if (command != CMD_CONNECT) {
            // UDP associate and bind go to the relay untouched; see the class note.
            proxyThrough(client, input, output, atyp, rawAddr, host, port, command)
            return
        }

        val verdict = decide(atyp, host)
        trace("socks: $host:$port -> $verdict")
        when (verdict) {
            Decision.BLOCK -> {
                // Refuse rather than tunnel: an ad domain should cost nothing,
                // least of all bandwidth on a 4-6 Mbit/s transport.
                runCatching {
                    output.write(byteArrayOf(5, 2, 0, ATYP_IPV4.toByte(), 0, 0, 0, 0, 0, 0))
                    output.flush()
                }
                close(client)
            }
            Decision.DIRECT -> {
                val target = resolve(host)
                if (target == null) {
                    // Cannot resolve it ourselves, so let the tunnel try: on a
                    // filtered network the resolver is often the broken part,
                    // and failing here would strand the connection.
                    proxyThrough(client, input, output, atyp, rawAddr, host, port, command)
                } else {
                    direct(client, output, target, port)
                }
            }
            else -> proxyThrough(client, input, output, atyp, rawAddr, host, port, command)
        }
    }

    /**
     * Two orderings, and they are different questions.
     *
     * By signal: the name the client asked for beats the address it resolves
     * to, which on shared hosting it shares with everything else on that CDN.
     * By source: the user's own lists beat the published blob, so a rule typed
     * by hand is not overruled by a list compiled on another machine.
     *
     * Hence name-overlay, name-blob, address-overlay, address-blob. Asking the
     * overlay's addresses before the blob's names would invert the first
     * ordering and cost a DNS lookup on every connection the blob could already
     * have answered by name.
     *
     * Anything with no rule at all takes the tunnel. That is the deliberate
     * default — being slow is recoverable, being exposed is not.
     */
    private fun decide(atyp: Int, host: String): Decision {
        if (atyp == ATYP_DOMAIN) {
            val byUser = overlay.decideDomain(host)
            if (byUser != Decision.UNKNOWN) return byUser
            val byName = rules.decideDomain(host)
            if (byName != Decision.UNKNOWN) return byName
        }
        val target = resolve(host) ?: return Decision.PROXY
        val byAddress = overlay.decideIp(target)
        if (byAddress != Decision.UNKNOWN) return byAddress
        return if (rules.matchesIp(target)) Decision.PROXY else Decision.DIRECT
    }

    /**
     * A destination we cannot resolve is sent through the tunnel rather than
     * direct: on a filtered network the resolver itself may be what is broken,
     * and guessing "direct" there would strand the connection entirely.
     */
    private fun resolve(host: String): InetAddress? =
        runCatching { InetAddress.getByName(host) }.getOrNull()

    private fun greet(input: DataInputStream, output: OutputStream): Boolean {
        val ver = input.read()
        if (ver != 5) { trace("socks: bad version byte $ver"); return false }
        val methods = input.read()
        val offered = ByteArray(methods)
        input.readFully(offered)
        // tun2socks is configured with the relay's credentials, so speak the
        // same dialect back at it rather than inventing a second convention.
        trace("socks: client offers ${offered.joinToString(",") { it.toString() }}")
        return if (offered.contains(AUTH_USERPASS)) {
            output.write(byteArrayOf(5, AUTH_USERPASS)); output.flush()
            checkUserPass(input, output)
        } else {
            output.write(byteArrayOf(5, AUTH_NONE)); output.flush()
            true
        }
    }

    private fun checkUserPass(input: DataInputStream, output: OutputStream): Boolean {
        if (input.read() != 1) return false
        val u = ByteArray(input.read()); input.readFully(u)
        val p = ByteArray(input.read()); input.readFully(p)
        val ok = String(u) == user && String(p) == pass
        if (!ok) trace("socks: credentials rejected (user len=${u.size})")
        output.write(byteArrayOf(1, if (ok) 0 else 1)); output.flush()
        return ok
    }

    private fun readAddress(input: DataInputStream, atyp: Int): Pair<String, ByteArray>? =
        when (atyp) {
            ATYP_IPV4 -> ByteArray(4).also { input.readFully(it) }
                .let { InetAddress.getByAddress(it).hostAddress!! to it }
            ATYP_IPV6 -> ByteArray(16).also { input.readFully(it) }
                .let { InetAddress.getByAddress(it).hostAddress!! to it }
            ATYP_DOMAIN -> {
                val raw = ByteArray(input.read()).also { input.readFully(it) }
                String(raw) to raw
            }
            else -> null
        }

    /** Hands the connection to the relay, replaying the request verbatim. */
    private fun proxyThrough(
        client: Socket, clientIn: InputStream, clientOut: OutputStream,
        atyp: Int, rawAddr: ByteArray, host: String, port: Int, command: Int,
    ) {
        val relay = Socket()
        try {
            relay.tcpNoDelay = true
            relay.connect(InetSocketAddress("127.0.0.1", relayPort), 10_000)
            val relayIn = DataInputStream(relay.getInputStream().buffered())
            val relayOut = relay.getOutputStream()

            relayOut.write(byteArrayOf(5, 1, AUTH_USERPASS)); relayOut.flush()
            relayIn.read(); val method = relayIn.read()
            if (method == AUTH_USERPASS.toInt()) {
                val u = user.toByteArray(); val p = pass.toByteArray()
                relayOut.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p)
                relayOut.flush()
                relayIn.read(); if (relayIn.read() != 0) throw IllegalStateException("relay auth rejected")
            }

            val head = if (atyp == ATYP_DOMAIN) {
                byteArrayOf(5, command.toByte(), 0, ATYP_DOMAIN.toByte(), rawAddr.size.toByte()) + rawAddr
            } else {
                byteArrayOf(5, command.toByte(), 0, atyp.toByte()) + rawAddr
            }
            relayOut.write(head + byteArrayOf((port shr 8).toByte(), port.toByte()))
            relayOut.flush()

            // Reply header is variable length; copy it through exactly.
            val reply = ByteArray(4)
            relayIn.readFully(reply)
            clientOut.write(reply)
            val tail = when (reply[3].toInt()) {
                ATYP_IPV4 -> ByteArray(4 + 2)
                ATYP_IPV6 -> ByteArray(16 + 2)
                ATYP_DOMAIN -> ByteArray((relayIn.read().also { clientOut.write(it) }) + 2)
                else -> ByteArray(0)
            }
            if (tail.isNotEmpty()) { relayIn.readFully(tail); clientOut.write(tail) }
            clientOut.flush()

            splice(client, relay, clientIn, relayIn, clientOut, relayOut)
        } catch (e: Exception) {
            trace("socks: relay leg failed for $host:$port — ${e.message}")
            Log.w(TAG, "proxy $host:$port failed: ${e.message}")
            close(client); close(relay)
        }
    }

    /** Dials the destination outside the tunnel. */
    private fun direct(client: Socket, clientOut: OutputStream, target: InetAddress, port: Int) {
        val remote = Socket()
        try {
            remote.tcpNoDelay = true
            if (!protect(remote)) throw IllegalStateException("protect() refused")
            remote.connect(InetSocketAddress(target, port), 10_000)
            clientOut.write(byteArrayOf(5, 0, 0, ATYP_IPV4.toByte(), 0, 0, 0, 0, 0, 0))
            clientOut.flush()
            splice(client, remote, client.getInputStream(), remote.getInputStream(),
                clientOut, remote.getOutputStream())
        } catch (e: Exception) {
            trace("socks: direct dial failed for ${target.hostAddress}:$port — ${e.message}")
            runCatching {
                clientOut.write(byteArrayOf(5, 5, 0, ATYP_IPV4.toByte(), 0, 0, 0, 0, 0, 0))
                clientOut.flush()
            }
            close(client); close(remote)
        }
    }

    private fun splice(
        a: Socket, b: Socket, aIn: InputStream, bIn: InputStream,
        aOut: OutputStream, bOut: OutputStream,
    ) {
        val done = AtomicBoolean(false)
        fun pump(from: InputStream, to: OutputStream) {
            try {
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = from.read(buf)
                    if (n < 0) break
                    to.write(buf, 0, n)
                    to.flush()
                }
            } catch (_: Exception) {
            } finally {
                if (done.compareAndSet(false, true)) { close(a); close(b) }
            }
        }
        pool.execute { pump(bIn, aOut) }
        pump(aIn, bOut)
    }

    private fun close(socket: Socket) = runCatching { socket.close() }

    private companion object {
        const val TAG = "RoutingSocks"
        const val CMD_CONNECT = 1
        const val ATYP_IPV4 = 1
        const val ATYP_DOMAIN = 3
        const val ATYP_IPV6 = 4
        const val AUTH_NONE: Byte = 0
        const val AUTH_USERPASS: Byte = 2
    }
}
