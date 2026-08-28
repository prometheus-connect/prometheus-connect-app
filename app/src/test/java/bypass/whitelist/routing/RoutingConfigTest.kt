package bypass.whitelist.routing

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the half of the config that decides what a typed line means.
 *
 * The classification is what the screen reports back to the user — "2 not
 * understood" is only useful if the count is right — so the cases that matter
 * are the ones sitting on a boundary: an address that also spells a legal
 * domain, a category name with a dash in it, a prefix nobody here can honour.
 */
class RoutingConfigTest {

    @Test
    fun `the shipped profile is the light one`() {
        // Pinned because it is what a fresh install routes by, and a silent
        // edit to it changes behaviour for everyone who never opens the screen.
        assertEquals(
            listOf(
                "geosite:ru-blocked",
                "myanimelist.net",
                "geoip:ru-blocked",
                "geoip:ru-blocked-community",
                "geoip:re-filter",
            ),
            RoutingConfig.DEFAULT.proxy,
        )
        assertEquals(listOf("geosite:private", "geoip:private"), RoutingConfig.DEFAULT.direct)
        assertEquals(listOf("geosite:category-ads"), RoutingConfig.DEFAULT.block)
        // Global proxy on: an untouched install keeps tunnelling everything.
        assertEquals(true, RoutingConfig.DEFAULT.globalProxy)
    }

    @Test
    fun `lists survive a trip through text`() {
        val rules = RoutingConfig.DEFAULT.proxy
        assertEquals(rules, RoutingConfig.parseList(RoutingConfig.formatList(rules)))
    }

    @Test
    fun `parsing drops blanks comments and repeats`() {
        val text = """
            geosite:ru-blocked

            # everything below is mine
            example.com
            example.com
              10.0.0.0/8  ,
        """.trimIndent()
        assertEquals(
            listOf("geosite:ru-blocked", "example.com", "10.0.0.0/8"),
            RoutingConfig.parseList(text),
        )
    }

    @Test
    fun `categories are recognised on both sides`() {
        assertEquals(RuleKind.GEOSITE, RoutingConfig.kindOf("geosite:ru-blocked"))
        assertEquals(RuleKind.GEOIP, RoutingConfig.kindOf("geoip:ru-blocked-community"))
        assertEquals(RuleKind.GEOSITE, RoutingConfig.kindOf("GeoSite:category-ads"))
        assertEquals(RuleKind.UNSUPPORTED, RoutingConfig.kindOf("geosite:"))
        assertEquals(RuleKind.UNSUPPORTED, RoutingConfig.kindOf("geosite:has spaces"))
    }

    @Test
    fun `an address is a network never a name`() {
        // "1.2.3.4" spells four legal labels, so the order the two checks run
        // in is the whole difference between a host route and a domain rule.
        assertEquals(RuleKind.CIDR, RoutingConfig.kindOf("1.2.3.4"))
        assertEquals(RuleKind.CIDR, RoutingConfig.kindOf("104.16.0.0/13"))
        assertEquals(RuleKind.CIDR, RoutingConfig.kindOf("2001:db8::/32"))
        assertEquals(RuleKind.UNSUPPORTED, RoutingConfig.kindOf("10.0.0.0/33"))
        assertEquals(RuleKind.UNSUPPORTED, RoutingConfig.kindOf("300.1.1.1/8"))
    }

    @Test
    fun `domains keep the spellings that mean the same thing`() {
        assertEquals(RuleKind.DOMAIN, RoutingConfig.kindOf("myanimelist.net"))
        assertEquals(RuleKind.DOMAIN, RoutingConfig.kindOf("*.rutracker.org"))
        assertEquals(RuleKind.DOMAIN, RoutingConfig.kindOf(".rutracker.org"))
        assertEquals(RuleKind.UNSUPPORTED, RoutingConfig.kindOf("localhost"))
        assertEquals(RuleKind.UNSUPPORTED, RoutingConfig.kindOf("regexp:.*ads.*"))
        assertEquals(RuleKind.UNSUPPORTED, RoutingConfig.kindOf("-bad.example.com"))
    }

    @Test
    fun `a list can be replaced without disturbing the others`() {
        val edited = RoutingConfig.DEFAULT.withRules(Decision.BLOCK, listOf("ads.example.com"))
        assertEquals(listOf("ads.example.com"), edited.block)
        assertEquals(RoutingConfig.DEFAULT.proxy, edited.proxy)
        assertEquals(RoutingConfig.DEFAULT.direct, edited.direct)
    }
}
