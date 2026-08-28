package bypass.whitelist.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the importer with whole links rather than the JSON inside them, so the
 * base64 layer is exercised too — it is hand-written here, because neither
 * platform decoder is usable (see [HappRouting.decodeBase64]).
 *
 * The assertions that matter most are about the *reporting*. Losing a rule
 * silently is the failure mode with no symptom: the user reads their list back,
 * sees what they expected, and finds out months later from a site that never
 * took the tunnel.
 */
class HappRoutingTest {

    /**
     * A full profile: both halves of every list, a regexp entry this app cannot
     * honour, and the two fields that are structural here.
     *
     * {"ProxySites":["geosite:ru-blocked","myanimelist.net","*.rutracker.org"],
     *  "ProxyIp":["geoip:ru-blocked","geoip:re-filter","104.16.0.0/13"],
     *  "DirectSites":["geosite:private","gosuslugi.ru"],
     *  "DirectIp":["geoip:private","10.0.0.0/8"],
     *  "BlockSites":["geosite:category-ads","regexp:.*doubleclick.*"],
     *  "BlockIp":["geoip:cn","0.0.0.0/8"],
     *  "GlobalProxy":"false","RouteOrder":"Proxy,Direct,Block",
     *  "DomainStrategy":"IPIfNonMatch"}
     */
    private val link = HappRouting.LINK_PREFIX +
        "eyJQcm94eVNpdGVzIjpbImdlb3NpdGU6cnUtYmxvY2tlZCIsIm15YW5pbWVsaXN0Lm5ldCIsIioucn" +
        "V0cmFja2VyLm9yZyJdLCJQcm94eUlwIjpbImdlb2lwOnJ1LWJsb2NrZWQiLCJnZW9pcDpyZS1maWx0" +
        "ZXIiLCIxMDQuMTYuMC4wLzEzIl0sIkRpcmVjdFNpdGVzIjpbImdlb3NpdGU6cHJpdmF0ZSIsImdvc3" +
        "VzbHVnaS5ydSJdLCJEaXJlY3RJcCI6WyJnZW9pcDpwcml2YXRlIiwiMTAuMC4wLjAvOCJdLCJCbG9j" +
        "a1NpdGVzIjpbImdlb3NpdGU6Y2F0ZWdvcnktYWRzIiwicmVnZXhwOi4qZG91YmxlY2xpY2suKiJdLC" +
        "JCbG9ja0lwIjpbImdlb2lwOmNuIiwiMC4wLjAuMC84Il0sIkdsb2JhbFByb3h5IjoiZmFsc2UiLCJS" +
        "b3V0ZU9yZGVyIjoiUHJveHksRGlyZWN0LEJsb2NrIiwiRG9tYWluU3RyYXRlZ3kiOiJJUElmTm9uTW" +
        "F0Y2gifQ"

    @Test
    fun `a real link lands in the three lists`() {
        val config = HappRouting.parse(link).config
        // Sites and ip arrive as separate keys and end up in one list, in the
        // order Happ wrote them.
        assertEquals(
            listOf(
                "geosite:ru-blocked", "myanimelist.net", "*.rutracker.org",
                "geoip:ru-blocked", "geoip:re-filter", "104.16.0.0/13",
            ),
            config.proxy,
        )
        assertEquals(
            listOf("geosite:private", "gosuslugi.ru", "geoip:private", "10.0.0.0/8"),
            config.direct,
        )
        assertEquals(listOf("geosite:category-ads", "geoip:cn", "0.0.0.0/8"), config.block)
    }

    @Test
    fun `what came in can be written out and read back`() {
        val config = HappRouting.parse(link).config
        assertEquals(config.proxy, RoutingConfig.parseList(RoutingConfig.formatList(config.proxy)))
        assertEquals(config.direct, RoutingConfig.parseList(RoutingConfig.formatList(config.direct)))
        assertEquals(config.block, RoutingConfig.parseList(RoutingConfig.formatList(config.block)))
    }

    @Test
    fun `every entry is either applied or reported`() {
        val result = HappRouting.parse(link)
        assertEquals(13, result.applied.size)
        assertEquals(result.config.ruleCount, result.applied.size)

        val dropped = result.dropped.map { it.value }
        assertTrue(dropped.contains("regexp:.*doubleclick.*"))
        assertTrue(dropped.any { it.startsWith("RouteOrder:") })
        assertTrue(dropped.any { it.startsWith("DomainStrategy:") })
        assertEquals(3, result.dropped.size)
        // Nothing is dropped without saying why — the report is the whole point.
        assertTrue(result.dropped.all { it.reason.isNotBlank() })
        // And nothing turns up in both columns.
        assertTrue(result.applied.none { rule -> dropped.contains(rule) })
    }

    /**
     * The one place the two vocabularies meet: Happ's GlobalProxy on is this
     * app's split routing off, and getting that backwards would send a whole
     * imported profile the wrong way round without failing anything.
     */
    @Test
    fun `global proxy is read whether it is a boolean or a word, and inverted`() {
        assertTrue(HappRouting.parse(link).config.splitRouting)
        assertFalse(HappRouting.parse(linkOf("{\"GlobalProxy\":true}")).config.splitRouting)
        assertTrue(HappRouting.parse(linkOf("{\"GlobalProxy\":false}")).config.splitRouting)
        assertFalse(HappRouting.parse(linkOf("{\"GlobalProxy\":\"TRUE\"}")).config.splitRouting)
        assertTrue(HappRouting.parse(linkOf("{\"GlobalProxy\":\"false\"}")).config.splitRouting)
        // Absent means everything through the tunnel: a config that forgot to
        // say costs speed, never cover.
        assertFalse(HappRouting.parse(linkOf("{}")).config.splitRouting)
    }

    @Test
    fun `a list written as one blob of text is still a list`() {
        val result = HappRouting.parse(linkOf("{\"ProxySites\":\"example.com\\nexample.org\"}"))
        assertEquals(listOf("example.com", "example.org"), result.config.proxy)
    }

    @Test
    fun `a link that carries nothing usable says so`() {
        assertMessage("expected") { HappRouting.parse("https://example.com/nope") }
        assertMessage("payload") { HappRouting.parse(HappRouting.LINK_PREFIX) }
        assertMessage("base64") { HappRouting.parse(HappRouting.LINK_PREFIX + "not base64!") }
        assertMessage("JSON") { HappRouting.parse(HappRouting.LINK_PREFIX + "aGVsbG8") }
    }

    @Test
    fun `both base64 alphabets decode, padded or not`() {
        val expected = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0xFD.toByte())
        assertTrue(expected.contentEquals(HappRouting.decodeBase64("//79")))
        assertTrue(expected.contentEquals(HappRouting.decodeBase64("__79")))
        assertTrue("A".toByteArray().contentEquals(HappRouting.decodeBase64("QQ")))
        assertTrue("A".toByteArray().contentEquals(HappRouting.decodeBase64("QQ==")))
    }

    /** The prefix and a query tail are both things a shared link arrives with. */
    @Test
    fun `a query tail is not part of the payload`() {
        val tagged = "$link?name=work"
        assertEquals(HappRouting.parse(link).config, HappRouting.parse(tagged).config)
    }

    private fun linkOf(json: String): String =
        HappRouting.LINK_PREFIX + encodeBase64(json.toByteArray(Charsets.UTF_8))

    private fun encodeBase64(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bits += 8
            while (bits >= 6) {
                bits -= 6
                out.append(alphabet[(buffer shr bits) and 0x3F])
            }
        }
        if (bits > 0) out.append(alphabet[(buffer shl (6 - bits)) and 0x3F])
        return out.toString()
    }

    private fun assertMessage(fragment: String, block: () -> Unit) {
        val message = try {
            block()
            ""
        } catch (e: IllegalArgumentException) {
            e.message.orEmpty()
        }
        assertTrue("expected a message mentioning '$fragment', got '$message'", message.contains(fragment))
    }
}
