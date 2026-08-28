package bypass.whitelist.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The overlay is the half of routing the user can see and edit, so what it does
 * with a line has to be exactly what the screen says it does with that line.
 *
 * Three things are pinned here. The order between the lists, because it is
 * printed on the screen as a promise. The four rule kinds, because each one is
 * offered in the editor's hint. And the fate of a rule that cannot be honoured,
 * because a category quietly resolving to nothing is the failure that would
 * look exactly like success.
 */
class UserRulesTest {

    @Test
    fun `block beats direct beats proxy`() {
        // Named in all three lists at once, which is what a pasted profile and
        // a hand-typed line do to each other sooner or later.
        val rules = build(
            proxy = listOf("example.com"),
            direct = listOf("example.com"),
            block = listOf("example.com"),
        )
        assertEquals(Decision.BLOCK, rules.decideDomain("example.com"))

        val without = build(proxy = listOf("example.com"), direct = listOf("example.com"))
        assertEquals(Decision.DIRECT, without.decideDomain("example.com"))
    }

    @Test
    fun `a longer rule does not outrank a stronger list`() {
        // RuleSet resolves a clash by specificity; here the lists resolve it,
        // because "block, then direct, then proxy" is the sentence the screen
        // shows and a longer rule silently winning would falsify it.
        val rules = build(direct = listOf("cdn.example.com"), block = listOf("example.com"))
        assertEquals(Decision.BLOCK, rules.decideDomain("cdn.example.com"))
    }

    @Test
    fun `a bare domain covers everything under it`() {
        val rules = build(direct = listOf("example.com"))
        assertEquals(Decision.DIRECT, rules.decideDomain("a.b.example.com"))
        assertEquals(Decision.DIRECT, rules.decideDomain("EXAMPLE.COM."))
        // Not a suffix match, just a name that ends in the same letters.
        assertEquals(Decision.UNKNOWN, rules.decideDomain("notexample.com"))
    }

    @Test
    fun `the three spellings of a suffix mean one rule`() {
        for (spelling in listOf("example.com", "*.example.com", ".example.com")) {
            val rules = build(block = listOf(spelling))
            assertEquals(spelling, Decision.BLOCK, rules.decideDomain("deep.example.com"))
        }
    }

    @Test
    fun `a v4 CIDR decides for its range and nothing else`() {
        val rules = build(direct = listOf("10.0.0.0/8"))
        assertEquals(Decision.DIRECT, rules.decideIp(InetAddress.getByName("10.1.2.3")))
        assertEquals(Decision.UNKNOWN, rules.decideIp(InetAddress.getByName("11.0.0.1")))
    }

    @Test
    fun `a bare v4 address is a host route`() {
        // "1.2.3.4" also spells four legal domain labels, so this is really a
        // test that the classifier ran before the address parser.
        val rules = build(block = listOf("1.2.3.4"))
        assertEquals(Decision.BLOCK, rules.decideIp(InetAddress.getByName("1.2.3.4")))
        assertEquals(Decision.UNKNOWN, rules.decideIp(InetAddress.getByName("1.2.3.5")))
    }

    @Test
    fun `a v6 CIDR decides for its range and nothing else`() {
        val rules = build(direct = listOf("2001:db8::/32"))
        assertEquals(Decision.DIRECT, rules.decideIp(InetAddress.getByName("2001:db8:1234::1")))
        assertEquals(Decision.UNKNOWN, rules.decideIp(InetAddress.getByName("2001:db9::1")))
    }

    @Test
    fun `an unknown category is reported, not dropped`() {
        val rules = UserRules.build(
            config(proxy = listOf("geosite:no-such-thing", "example.com")),
            CategorySource { _, _ -> CategoryLookup(null, RuleStatus.UNKNOWN_CATEGORY) },
        )
        val entry = rules.entries.single { it.rule == "geosite:no-such-thing" }
        assertEquals(RuleStatus.UNKNOWN_CATEGORY, entry.status)
        assertEquals("it must not claim to cover anything", 0, entry.covers)
        // The rest of the list carries on: one dead line is not a dead list.
        assertEquals(Decision.PROXY, rules.decideDomain("example.com"))
    }

    @Test
    fun `a category the publisher withholds is named as such`() {
        val rules = UserRules.build(
            config(proxy = listOf("geosite:ru-blocked-all")),
            CategorySource { _, _ -> CategoryLookup(null, RuleStatus.UNAVAILABLE_CATEGORY) },
        )
        assertEquals(RuleStatus.UNAVAILABLE_CATEGORY, rules.entries.single().status)
    }

    @Test
    fun `a category not downloaded yet leaves the list honest`() {
        val rules = UserRules.build(
            config(block = listOf("geosite:category-ads")),
            CategorySource.NONE,
        )
        assertEquals(RuleStatus.NOT_DOWNLOADED, rules.entries.single().status)
        assertTrue("nothing may be enforced from a category that never arrived", rules.isEmpty)
    }

    @Test
    fun `a line that parses as nothing is kept and marked`() {
        val rules = build(proxy = listOf("regexp:.*ads.*"))
        val entry = rules.entries.single()
        assertEquals(RuleKind.UNSUPPORTED, entry.kind)
        assertEquals(RuleStatus.UNPARSEABLE, entry.status)
    }

    @Test
    fun `a category is decided by the list it was named in`() {
        // Every payload the publisher cuts carries action 0 — the profile it was
        // compiled for said "proxy". Naming it in the block list has to mean
        // block, or the lists would only ever be able to add proxy rules.
        val payload = blob(domains = listOf(Triple(0, 0, "ads.example")))
        val source = CategorySource { _, _ -> CategoryLookup(payload, RuleStatus.ACTIVE) }
        val blocking = UserRules.build(config(block = listOf("geosite:category-ads")), source)
        assertEquals(Decision.BLOCK, blocking.decideDomain("banner.ads.example"))

        val going = UserRules.build(config(direct = listOf("geosite:category-ads")), source)
        assertEquals(Decision.DIRECT, going.decideDomain("banner.ads.example"))
    }

    @Test
    fun `a geoip category decides by address`() {
        val payload = blob(v4 = listOf("192.0.2.0" to 24), v6 = listOf("2001:db8::" to 32))
        val rules = UserRules.build(
            config(direct = listOf("geoip:private")),
            CategorySource { _, _ -> CategoryLookup(payload, RuleStatus.ACTIVE) },
        )
        assertEquals(Decision.DIRECT, rules.decideIp(InetAddress.getByName("192.0.2.77")))
        assertEquals(Decision.DIRECT, rules.decideIp(InetAddress.getByName("2001:db8::5")))
        assertEquals(Decision.UNKNOWN, rules.decideIp(InetAddress.getByName("198.51.100.1")))
        assertEquals("two prefixes, and the entry must say so", 2, rules.entries.single().covers)
    }

    @Test
    fun `every category the config names is collected once`() {
        val wanted = UserRules.categoriesIn(
            config(
                proxy = listOf("geosite:ru-blocked", "geoip:ru-blocked", "example.com"),
                direct = listOf("geosite:ru-blocked"),
            )
        )
        assertEquals(
            setOf(
                CategoryName(RuleKind.GEOSITE, "ru-blocked"),
                CategoryName(RuleKind.GEOIP, "ru-blocked"),
            ),
            wanted,
        )
    }

    // ---- helpers ----------------------------------------------------------

    private fun config(
        proxy: List<String> = emptyList(),
        direct: List<String> = emptyList(),
        block: List<String> = emptyList(),
    ) = RoutingConfig(globalProxy = false, proxy = proxy, direct = direct, block = block)

    private fun build(
        proxy: List<String> = emptyList(),
        direct: List<String> = emptyList(),
        block: List<String> = emptyList(),
    ) = UserRules.build(config(proxy, direct, block), CategorySource.NONE)

    /** A PCRT v2 payload, the shape a single category arrives in. */
    private fun blob(
        v4: List<Pair<String, Int>> = emptyList(),
        v6: List<Pair<String, Int>> = emptyList(),
        domains: List<Triple<Int, Int, String>> = emptyList(),
    ): ByteArray {
        var out = "PCRT".toByteArray() + byteArrayOf(2, 0, 0, 0) +
            intLe(v4.size) + intLe(v6.size) + intLe(domains.size)
        for ((network, length) in v4) {
            out += InetAddress.getByName(network).address + byteArrayOf(length.toByte())
        }
        for ((network, length) in v6) {
            out += InetAddress.getByName(network).address + byteArrayOf(length.toByte())
        }
        for ((kind, action, value) in domains) {
            val raw = value.toByteArray()
            out += byteArrayOf(kind.toByte(), action.toByte(), raw.size.toByte()) + raw
        }
        return out
    }

    private fun intLe(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
}
