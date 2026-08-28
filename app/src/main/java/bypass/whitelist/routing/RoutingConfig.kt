package bypass.whitelist.routing

/**
 * What a single line of a rule list denotes.
 *
 * [UNSUPPORTED] is a first-class outcome rather than a parse failure: an
 * imported profile may carry forms this app has no way to honour, and the user
 * has to be told which ones, not handed a config that silently lost half of
 * itself.
 */
enum class RuleKind { GEOSITE, GEOIP, DOMAIN, CIDR, UNSUPPORTED }

/**
 * The three routing lists as the user edits them, plus the master switch.
 *
 * The lists are kept as the lines the user typed, not as a compiled structure.
 * They are edited as text, imported as text and shown back as text, and every
 * round trip through a richer model is another chance to quietly normalise away
 * something that was meant. Classification happens per line, on demand, so a
 * line nobody can honour is *reported* instead of vanishing.
 *
 * [globalProxy] is the inverse of split routing: on, everything takes the
 * tunnel and the lists are inert. That is the default, and deliberately so —
 * see `Prefs.splitRoutingEnabled` for why sending traffic direct is the
 * dangerous side to guess wrong on.
 */
data class RoutingConfig(
    val globalProxy: Boolean,
    val proxy: List<String>,
    val direct: List<String>,
    val block: List<String>,
) {

    val ruleCount: Int get() = proxy.size + direct.size + block.size

    fun rulesFor(decision: Decision): List<String> = when (decision) {
        Decision.PROXY -> proxy
        Decision.DIRECT -> direct
        Decision.BLOCK -> block
        Decision.UNKNOWN -> emptyList()
    }

    fun withRules(decision: Decision, rules: List<String>): RoutingConfig = when (decision) {
        Decision.PROXY -> copy(proxy = rules)
        Decision.DIRECT -> copy(direct = rules)
        Decision.BLOCK -> copy(block = rules)
        Decision.UNKNOWN -> this
    }

    companion object {

        const val GEOSITE_PREFIX = "geosite:"
        const val GEOIP_PREFIX = "geoip:"

        /**
         * The light profile the project ships, seeded on first run.
         *
         * Not an empty config: with global proxy off and no proxy rules every
         * destination would look unmatched and go direct, which is the one
         * outcome nobody switching this on is asking for.
         */
        val DEFAULT = RoutingConfig(
            globalProxy = true,
            proxy = listOf(
                "geosite:ru-blocked",
                "myanimelist.net",
                "geoip:ru-blocked",
                "geoip:ru-blocked-community",
                "geoip:re-filter",
            ),
            direct = listOf(
                "geosite:private",
                "geoip:private",
            ),
            block = listOf(
                "geosite:category-ads",
            ),
        )

        /**
         * One rule per line. Blank lines and `#` comments are dropped, and so
         * are repeats — the editor is a text box, and pasting a list twice is
         * the most ordinary thing a user does to it.
         */
        fun parseList(text: String): List<String> = text
            .split('\n')
            .map { it.trim().removeSuffix(",").trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .distinct()

        fun formatList(rules: List<String>): String = rules.joinToString("\n")

        fun kindOf(rule: String): RuleKind {
            val value = rule.trim()
            if (value.isEmpty()) return RuleKind.UNSUPPORTED
            val lower = value.lowercase()
            return when {
                lower.startsWith(GEOSITE_PREFIX) ->
                    if (isCategory(lower.removePrefix(GEOSITE_PREFIX))) RuleKind.GEOSITE else RuleKind.UNSUPPORTED
                lower.startsWith(GEOIP_PREFIX) ->
                    if (isCategory(lower.removePrefix(GEOIP_PREFIX))) RuleKind.GEOIP else RuleKind.UNSUPPORTED
                lower.contains('/') -> if (isCidr(lower)) RuleKind.CIDR else RuleKind.UNSUPPORTED
                isAddress(lower) -> RuleKind.CIDR  // a bare address is a host route; Happ exports them that way
                isDomain(lower) -> RuleKind.DOMAIN
                else -> RuleKind.UNSUPPORTED
            }
        }

        /** Category names are what the server folds into file names, so keep them tame. */
        private fun isCategory(value: String): Boolean =
            value.isNotEmpty() && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }

        private fun isCidr(value: String): Boolean {
            val slash = value.indexOf('/')
            val network = value.substring(0, slash)
            val length = value.substring(slash + 1).toIntOrNull() ?: return false
            return when {
                isIpv4(network) -> length in 0..32
                isIpv6(network) -> length in 0..128
                else -> false
            }
        }

        private fun isAddress(value: String): Boolean = isIpv4(value) || isIpv6(value)

        // Written out rather than handed to InetAddress: that would resolve
        // anything that is not an address literal, turning a keystroke in a
        // text box into a DNS lookup.
        private fun isIpv4(value: String): Boolean {
            val parts = value.split('.')
            if (parts.size != 4) return false
            return parts.all { part ->
                part.isNotEmpty() && part.length <= 3 && part.all { it.isDigit() } &&
                    part.toInt() <= 255
            }
        }

        private fun isIpv6(value: String): Boolean =
            value.contains(':') && value.all { it.isDigit() || it in "abcdefABCDEF:." }

        /**
         * A leading `*.` or `.` is accepted and means what it looks like: the
         * rule set matches by suffix anyway, so rejecting the spelling would
         * lose entries for no gain.
         */
        private fun isDomain(value: String): Boolean {
            val name = value.removePrefix("*.").removePrefix(".")
            if (name.length !in 1..253) return false
            val labels = name.split('.')
            if (labels.size < 2) return false
            return labels.all { label ->
                label.length in 1..63 &&
                    label.all { it.isLetterOrDigit() || it == '-' || it == '_' } &&
                    !label.startsWith('-') && !label.endsWith('-')
            }
        }
    }
}
