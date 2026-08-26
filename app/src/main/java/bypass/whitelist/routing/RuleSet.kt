package bypass.whitelist.routing

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * The set of destinations that should take the tunnel when split routing is on.
 *
 * Everything here is IP-based. The upstream rule format also carries domain
 * rules, but those need a resolver in the routing path and the payload for them
 * is 70 MB — see the server-side builder for why neither is on the table. With
 * `IPIfNonMatch` semantics most domain rules resolve into these prefixes anyway.
 *
 * Matching is longest-prefix by construction: addresses are bucketed by prefix
 * length, and a lookup walks from /32 down. That is at most 32 hash probes,
 * which is nothing next to the network round trip it is deciding about, and it
 * is far easier to get right than a hand-rolled trie.
 */
class RuleSet private constructor(
    private val v4: Map<Int, HashSet<Int>>,
    private val v6: List<Pair<ByteArray, Int>>,
) {

    val size: Int get() = v4.values.sumOf { it.size } + v6.size

    fun matches(address: InetAddress): Boolean = when (address) {
        is Inet4Address -> matchV4(address)
        is Inet6Address -> matchV6(address)
        else -> false
    }

    private fun matchV4(address: Inet4Address): Boolean {
        val bits = ByteBuffer.wrap(address.address).order(ByteOrder.BIG_ENDIAN).int
        for (length in 32 downTo 0) {
            val bucket = v4[length] ?: continue
            val masked = if (length == 0) 0 else bits and (-1 shl (32 - length))
            if (bucket.contains(masked)) return true
        }
        return false
    }

    private fun matchV6(address: Inet6Address): Boolean {
        val raw = address.address
        for ((network, length) in v6) {
            var remaining = length
            var index = 0
            var ok = true
            while (remaining > 0 && index < 16) {
                val take = if (remaining >= 8) 8 else remaining
                val mask = (0xFF shl (8 - take)) and 0xFF
                if ((raw[index].toInt() and mask) != (network[index].toInt() and mask)) {
                    ok = false
                    break
                }
                remaining -= take
                index++
            }
            if (ok) return true
        }
        return false
    }

    companion object {
        private const val TAG = "RuleSet"
        private val MAGIC = byteArrayOf('P'.code.toByte(), 'C'.code.toByte(),
            'R'.code.toByte(), 'T'.code.toByte())

        /** Empty set — matches nothing, so every connection goes direct. */
        val EMPTY = RuleSet(emptyMap(), emptyList())

        /**
         * Loads the cached blob, refreshing it from the rendezvous first when
         * possible.
         *
         * A stale cache beats no rules: if the download fails we keep whatever
         * we already had, because the alternative is silently routing a user's
         * blocked traffic direct.
         */
        fun load(context: Context, publicKey: String, profile: String): RuleSet {
            val cache = File(context.filesDir, "routing-$profile.bin")
            if (shouldRefresh(cache)) {
                runCatching { download(publicKey, cache) }
                    .onFailure { Log.w(TAG, "rule refresh failed: ${it.message}") }
            }
            if (!cache.isFile) return EMPTY
            return runCatching { parse(cache.readBytes()) }
                .onFailure { Log.w(TAG, "rule parse failed: ${it.message}") }
                .getOrDefault(EMPTY)
        }

        private fun shouldRefresh(cache: File): Boolean =
            !cache.isFile || System.currentTimeMillis() - cache.lastModified() > REFRESH_AFTER_MS

        private fun download(publicKey: String, cache: File) {
            // Same trick as the call pool: the anonymous public-resource API is
            // reachable on a whitelist channel and needs no credentials, so the
            // download href comes from there rather than being hardcoded.
            val metaUrl = "https://cloud-api.yandex.net/v1/disk/public/resources/download" +
                "?public_key=" + URLEncoder.encode(publicKey, StandardCharsets.UTF_8.name())
            val href = readJsonField(metaUrl, "href")
                ?: throw IllegalStateException("no download href")
            val tmp = File(cache.parentFile, cache.name + ".tmp")
            (URL(href).openConnection() as HttpURLConnection).run {
                connectTimeout = 15_000
                readTimeout = 120_000
                try {
                    inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                } finally {
                    disconnect()
                }
            }
            // Only replace a good cache once the new file is known to parse.
            parse(tmp.readBytes())
            if (!tmp.renameTo(cache)) throw IllegalStateException("cannot replace cache")
        }

        private fun readJsonField(url: String, field: String): String? {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 20_000
            }
            return try {
                val text = conn.inputStream.bufferedReader(StandardCharsets.UTF_8)
                    .use { it.readText() }
                org.json.JSONObject(text).optString(field).ifEmpty { null }
            } finally {
                conn.disconnect()
            }
        }

        private fun parse(blob: ByteArray): RuleSet {
            require(blob.size >= 12) { "blob too short" }
            require(blob.copyOfRange(0, 4).contentEquals(MAGIC)) { "bad magic" }
            require(blob[4].toInt() == 1) { "unsupported rule format ${blob[4]}" }
            val header = ByteBuffer.wrap(blob, 8, 8).order(ByteOrder.LITTLE_ENDIAN)
            val n4 = header.int
            val n6 = header.int

            val v4 = HashMap<Int, HashSet<Int>>()
            var offset = 16
            repeat(n4) {
                val bits = ByteBuffer.wrap(blob, offset, 4).order(ByteOrder.BIG_ENDIAN).int
                val length = blob[offset + 4].toInt() and 0xFF
                offset += 5
                val masked = if (length == 0) 0 else bits and (-1 shl (32 - length))
                v4.getOrPut(length) { HashSet() }.add(masked)
            }
            val v6 = ArrayList<Pair<ByteArray, Int>>(n6)
            repeat(n6) {
                val network = blob.copyOfRange(offset, offset + 16)
                val length = blob[offset + 16].toInt() and 0xFF
                offset += 17
                v6.add(network to length)
            }
            return RuleSet(v4, v6)
        }

        private const val REFRESH_AFTER_MS = 7L * 24 * 60 * 60 * 1000
    }
}
