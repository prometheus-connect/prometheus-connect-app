package bypass.whitelist.routing

import android.content.Context
import android.util.Log
import bypass.whitelist.util.Prefs
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/** What should happen to a connection. */
enum class Decision { PROXY, BLOCK, DIRECT, UNKNOWN }

/**
 * Destination rules for split routing: IP prefixes and domain rules, compiled
 * server-side from the upstream geoip/geosite lists.
 *
 * Both halves matter. IP rules alone miss anything blocked by name on shared
 * hosting, which is most of it; domain rules alone miss traffic that never
 * carries a name. The router asks about the domain first, because that is what
 * the client actually requested — a strictly better signal than the address it
 * happens to resolve to on a shared CDN.
 *
 * IP matching is longest-prefix by construction: prefixes are bucketed by
 * length and a lookup walks /32 downwards, at most 32 hash probes.
 */
class RuleSet private constructor(
    private val v4: Map<Int, HashSet<Int>>,
    private val v6: List<Pair<ByteArray, Int>>,
    private val exact: Map<String, Decision>,
    private val suffix: Map<String, Decision>,
) {

    val size: Int get() = v4.values.sumOf { it.size } + v6.size + exact.size + suffix.size
    val isEmpty: Boolean get() = size == 0

    /**
     * Decision for a hostname, or [Decision.UNKNOWN] when no rule covers it.
     *
     * Walks from the full name up through its parents, so the most specific
     * rule wins: a `direct` entry for `cdn.example.com` beats a `block` on
     * `example.com`.
     */
    fun decideDomain(host: String): Decision {
        val name = host.lowercase().trimEnd('.')
        exact[name]?.let { return it }
        var index = 0
        while (index >= 0 && index < name.length) {
            suffix[name.substring(index)]?.let { return it }
            val dot = name.indexOf('.', index)
            index = if (dot < 0) -1 else dot + 1
        }
        return Decision.UNKNOWN
    }

    fun matchesIp(address: InetAddress): Boolean = when (address) {
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
                    ok = false; break
                }
                remaining -= take; index++
            }
            if (ok) return true
        }
        return false
    }

    companion object {
        private const val TAG = "RuleSet"
        private val MAGIC = byteArrayOf('P'.code.toByte(), 'C'.code.toByte(),
            'R'.code.toByte(), 'T'.code.toByte())

        val EMPTY = RuleSet(emptyMap(), emptyList(), emptyMap(), emptyMap())

        /**
         * Loads the cached rules, refreshing them when the server has a newer
         * revision.
         *
         * Upstream rebuilds its lists at least daily, so a stale cache means a
         * newly blocked site quietly goes direct. The manifest is a few hundred
         * bytes and carries the revision, so checking costs nothing and the 5 MB
         * download happens only when something actually changed.
         *
         * A failed refresh keeps whatever was already cached: stale rules beat
         * no rules, and the caller treats "no rules" as "tunnel everything".
         */
        fun load(context: Context, manifestKey: String, blobKey: String, profile: String): RuleSet {
            val cache = cacheFile(context, profile)
            runCatching { refresh(manifestKey, blobKey, profile, cache) }
                .onFailure { Log.w(TAG, "rule refresh skipped: ${it.message}") }
            if (!cache.isFile) return EMPTY
            return runCatching { parse(cache.readBytes()) }
                .onFailure { Log.w(TAG, "rule parse failed: ${it.message}") }
                .getOrDefault(EMPTY)
        }

        /** Where the blob lands, so a screen can report its size and age. */
        fun cacheFile(context: Context, profile: String): File =
            File(context.filesDir, "routing-$profile.bin")

        /**
         * Whatever is already on disk, without touching the network. The
         * settings screen reports on the cache; making that report refresh it
         * would turn opening a screen into a 5 MB download.
         */
        fun loadCached(context: Context, profile: String): RuleSet {
            val cache = cacheFile(context, profile)
            if (!cache.isFile) return EMPTY
            return runCatching { parse(cache.readBytes()) }
                .onFailure { Log.w(TAG, "rule parse failed: ${it.message}") }
                .getOrDefault(EMPTY)
        }

        private fun refresh(manifestKey: String, blobKey: String, profile: String, cache: File) {
            val manifest = org.json.JSONObject(PublicDisk.read(manifestKey).toString(Charsets.UTF_8))
            val revision = manifest.optString("revision")
            if (revision.isEmpty()) throw IllegalStateException("manifest has no revision")
            if (revision == Prefs.routingRulesRevision && cache.isFile) {
                Log.i(TAG, "rules already at $revision")
                return
            }
            val blob = PublicDisk.read(blobKey)
            parse(blob)  // never replace a good cache with something unparseable
            val tmp = File(cache.parentFile, cache.name + ".tmp")
            tmp.writeBytes(blob)
            if (!tmp.renameTo(cache)) throw IllegalStateException("cannot replace cache")
            Prefs.routingRulesRevision = revision
            Log.i(TAG, "rules updated to $revision (${blob.size / 1024} KB)")
        }

        /** Visible for tests: the format is the part most likely to rot. */
        @JvmStatic
        internal fun parse(blob: ByteArray): RuleSet {
            val v4 = HashMap<Int, HashSet<Int>>()
            val v6 = ArrayList<Pair<ByteArray, Int>>()
            val exact = HashMap<String, Decision>()
            val suffix = HashMap<String, Decision>()
            read(
                blob,
                onV4 = { bits, length -> v4.getOrPut(length) { HashSet() }.add(bits) },
                onV6 = { network, length -> v6.add(network to length) },
                onDomain = { isExact, decision, name ->
                    (if (isExact) exact else suffix)[name] = decision
                },
            )
            return RuleSet(v4, v6, exact, suffix)
        }

        /**
         * Reads the format, handing over one rule at a time.
         *
         * Two callers want the same bytes in different shapes: this class
         * buckets them for lookups, [UserRules] re-labels each under the list
         * the category name was typed into. Handing them over one by one rather
         * than as a list in between, because the profile blob is 330 000 rules
         * and building it twice would double the peak to pass it straight on.
         *
         * @throws IllegalArgumentException on anything that is not a v2 blob,
         * and IndexOutOfBounds on one that stops early — a caller part-way
         * through has to treat what it took as void.
         */
        internal fun read(
            blob: ByteArray,
            onV4: (bits: Int, length: Int) -> Unit,
            onV6: (network: ByteArray, length: Int) -> Unit,
            onDomain: (exact: Boolean, decision: Decision, name: String) -> Unit,
        ) {
            require(blob.size >= 16) { "blob too short" }
            require(blob.copyOfRange(0, 4).contentEquals(MAGIC)) { "bad magic" }
            require(blob[4].toInt() == 2) { "unsupported rule format ${blob[4]}" }
            val header = ByteBuffer.wrap(blob, 8, 12).order(ByteOrder.LITTLE_ENDIAN)
            val n4 = header.int
            val n6 = header.int
            val nd = header.int

            var offset = 20
            repeat(n4) {
                val bits = ByteBuffer.wrap(blob, offset, 4).order(ByteOrder.BIG_ENDIAN).int
                val length = blob[offset + 4].toInt() and 0xFF
                offset += 5
                onV4(if (length == 0) 0 else bits and (-1 shl (32 - length)), length)
            }
            repeat(n6) {
                onV6(blob.copyOfRange(offset, offset + 16), blob[offset + 16].toInt() and 0xFF)
                offset += 17
            }
            repeat(nd) {
                val kind = blob[offset].toInt()
                val action = blob[offset + 1].toInt()
                val length = blob[offset + 2].toInt() and 0xFF
                val value = String(blob, offset + 3, length, StandardCharsets.UTF_8)
                offset += 3 + length
                val decision = when (action) {
                    0 -> Decision.PROXY
                    1 -> Decision.BLOCK
                    else -> Decision.DIRECT
                }
                onDomain(kind == 1, decision, value)
            }
        }
    }
}
