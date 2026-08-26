package bypass.whitelist.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * Runs against the real published blob, not a fixture. The rule format is
 * produced by a separate program on another machine, so the failure this guards
 * against is the two drifting apart — which a hand-written fixture would hide.
 */
class RuleSetTest {

    private val rules: RuleSet by lazy {
        val blob = javaClass.classLoader!!.getResourceAsStream("rules.bin")!!.readBytes()
        RuleSet.parse(blob)
    }

    @Test
    fun `parses the published blob`() {
        assertEquals(110722 + 4 + 223715, rules.size)
        assertFalse(rules.isEmpty)
    }

    @Test
    fun `blocked sites take the tunnel`() {
        assertEquals(Decision.PROXY, rules.decideDomain("003.su"))
        assertEquals(Decision.PROXY, rules.decideDomain("0.torlink.site"))
    }

    @Test
    fun `subdomains inherit their parent rule`() {
        // The whole point of suffix matching: nobody lists every host.
        assertEquals(Decision.PROXY, rules.decideDomain("deep.sub.003.su"))
    }

    @Test
    fun `ad domains are blocked`() {
        assertEquals(Decision.BLOCK, rules.decideDomain("0.avmarket.rs"))
    }

    @Test
    fun `private space stays direct`() {
        assertEquals(Decision.DIRECT, rules.decideDomain("10.in-addr.arpa"))
    }

    @Test
    fun `unlisted names are left undecided`() {
        // UNKNOWN, not DIRECT: the router turns "no rule" into the tunnel, and
        // conflating the two here would quietly send unmatched traffic direct.
        // NB: do not reach for example.invalid or anything under .local, .lan,
        // .test — geosite:private carries the RFC 6761 special-use TLDs, and
        // they are correctly DIRECT.
        assertEquals(Decision.UNKNOWN, rules.decideDomain("zzz-not-in-any-list.qqq"))
    }

    @Test
    fun `special-use TLDs never leave the device`() {
        // RFC 6761 names must not be tunnelled to a foreign exit: printer.local
        // means something on this LAN and nothing anywhere else.
        assertEquals(Decision.DIRECT, rules.decideDomain("printer.local"))
        assertEquals(Decision.DIRECT, rules.decideDomain("nas.lan"))
        assertEquals(Decision.DIRECT, rules.decideDomain("localhost"))
    }

    @Test
    fun `matches an address inside a listed prefix`() {
        assertTrue(rules.matchesIp(InetAddress.getByName("1.1.1.1")))
    }

    @Test
    fun `does not match an address outside every prefix`() {
        assertFalse(rules.matchesIp(InetAddress.getByName("192.0.2.1")))
    }

    @Test
    fun `case and trailing dot do not change the answer`() {
        assertEquals(Decision.PROXY, rules.decideDomain("003.SU."))
    }
}
