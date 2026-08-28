package bypass.whitelist.routing

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Import of a Happ routing link — `happ://routing/add/<base64 JSON>`.
 *
 * Happ's model is wider than this app's: it splits every list into a sites half
 * and an ip half, and it lets the route order and the domain strategy be
 * chosen. Here the two halves are one list and both of those knobs are fixed by
 * [RoutingSocksServer]. Rather than pretend otherwise, the import returns what
 * it applied *and* what it could not, so an entry that will not take effect is
 * something the user is told about instead of something they find out later
 * from a site that fails to load.
 */
object HappRouting {

    const val LINK_PREFIX = "happ://routing/add/"

    /** A line or a field that did not survive the import, and why. */
    data class Dropped(val value: String, val reason: String)

    data class Import(
        val config: RoutingConfig,
        val applied: List<String>,
        val dropped: List<Dropped>,
    )

    /**
     * @throws IllegalArgumentException when the link is not a Happ routing
     * link, is not valid base64, or does not decode to a JSON object. The
     * caller shows the message; there is nothing sensible to fall back to.
     */
    fun parse(link: String): Import {
        val trimmed = link.trim()
        require(trimmed.startsWith(LINK_PREFIX, ignoreCase = true)) { "expected $LINK_PREFIX…" }
        // Sharing a link through a chat app can staple a query or a fragment
        // onto it; neither belongs to the payload.
        val payload = trimmed.substring(LINK_PREFIX.length).substringBefore('?').substringBefore('#')
        require(payload.isNotEmpty()) { "the link carries no payload" }

        val decoded = String(decodeBase64(payload), Charsets.UTF_8)
        val json = try {
            JSONObject(decoded)
        } catch (e: Exception) {
            throw IllegalArgumentException("payload is not JSON: ${e.message}")
        }

        val applied = ArrayList<String>()
        val dropped = ArrayList<Dropped>()

        fun collect(sitesKey: String, ipKey: String): List<String> {
            // Sites and ip are merged: the decision is what the two halves have
            // in common, and keeping them apart here would buy nothing but a
            // second text box per list.
            val merged = readList(json, sitesKey) + readList(json, ipKey)
            return RoutingConfig.parseList(merged.joinToString("\n")).filter { rule ->
                val kind = RoutingConfig.kindOf(rule)
                if (kind == RuleKind.UNSUPPORTED) {
                    dropped += Dropped(rule, REASON_UNSUPPORTED)
                    false
                } else {
                    applied += rule
                    true
                }
            }
        }

        val proxy = collect("ProxySites", "ProxyIp")
        val direct = collect("DirectSites", "DirectIp")
        val block = collect("BlockSites", "BlockIp")

        // Everything through the tunnel is the safe reading of a missing flag:
        // a config that forgot to say loses speed, never cover.
        val globalProxy = readFlag(json, "GlobalProxy", fallback = true)

        // Both are structural here, not settings, so they cannot be honoured
        // even when they agree with us — say so rather than let the user think
        // their ordering carried over.
        json.optString("RouteOrder").takeIf { it.isNotEmpty() }
            ?.let { dropped += Dropped("RouteOrder: $it", REASON_ROUTE_ORDER) }
        json.optString("DomainStrategy").takeIf { it.isNotEmpty() }
            ?.let { dropped += Dropped("DomainStrategy: $it", REASON_DOMAIN_STRATEGY) }

        return Import(
            config = RoutingConfig(globalProxy, proxy, direct, block),
            applied = applied,
            dropped = dropped,
        )
    }

    /** Happ writes arrays, but hand-edited configs turn up as one blob of text. */
    private fun readList(json: JSONObject, key: String): List<String> =
        when (val raw = json.opt(key)) {
            null, JSONObject.NULL -> emptyList()
            is JSONArray -> (0 until raw.length()).mapNotNull { raw.opt(it)?.toString() }
            else -> raw.toString().split('\n', ',')
        }

    /** The flag arrives as a JSON boolean from some builds and as a string from others. */
    private fun readFlag(json: JSONObject, key: String, fallback: Boolean): Boolean =
        when (val raw = json.opt(key)) {
            null, JSONObject.NULL -> fallback
            is Boolean -> raw
            else -> raw.toString().trim().lowercase() in TRUE_WORDS
        }

    /**
     * Happ pads its payload with the URL-safe alphabet and sometimes not at
     * all, and neither platform decoder is available: `java.util.Base64` wants
     * API 26 against a minSdk of 23, and `android.util.Base64` is a stub under
     * unit tests — it would return nothing and let the import test agree with
     * an empty config.
     */
    internal fun decodeBase64(value: String): ByteArray {
        val out = ByteArrayOutputStream(value.length * 3 / 4 + 3)
        var buffer = 0
        var bits = 0
        for (c in value) {
            if (c == '=' || c.isWhitespace()) continue
            val digit = digit(c)
            require(digit >= 0) { "payload is not base64 (bad character '$c')" }
            buffer = (buffer shl 6) or digit
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        val bytes = out.toByteArray()
        require(bytes.isNotEmpty()) { "payload decodes to nothing" }
        return bytes
    }

    private fun digit(c: Char): Int = when (c) {
        in 'A'..'Z' -> c - 'A'
        in 'a'..'z' -> c - 'a' + 26
        in '0'..'9' -> c - '0' + 52
        '+', '-' -> 62
        '/', '_' -> 63
        else -> -1
    }

    private val TRUE_WORDS = setOf("true", "1", "yes", "on")

    private const val REASON_UNSUPPORTED =
        "not a category, domain or CIDR"
    private const val REASON_ROUTE_ORDER =
        "route order is fixed: block, then direct, then proxy"
    private const val REASON_DOMAIN_STRATEGY =
        "domain strategy is fixed: the requested name is matched before its address"
}
