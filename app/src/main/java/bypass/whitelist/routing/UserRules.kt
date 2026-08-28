package bypass.whitelist.routing

import androidx.annotation.StringRes
import bypass.whitelist.R
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Whether a line the user typed is deciding anything, and if not, why.
 *
 * The reason is carried as a string resource because two places have to say it
 * in the same words — the screen, beside the rule, and the session log, which is
 * the copy that survives long enough to be sent back.
 */
enum class RuleStatus(@StringRes val labelRes: Int) {
    ACTIVE(R.string.routing_status_active),
    UNPARSEABLE(R.string.routing_status_unparseable),
    UNKNOWN_CATEGORY(R.string.routing_status_unknown),
    UNAVAILABLE_CATEGORY(R.string.routing_status_unavailable),
    NOT_DOWNLOADED(R.string.routing_status_missing),
}

/**
 * The three lists the user edits, compiled into something the router can ask.
 *
 * [RuleSet] cannot hold these. Its IP half answers yes or no, because every
 * prefix in the published blob means the same thing — take the tunnel — while
 * `10.0.0.0/8` means direct in one list and block in another. So addresses go
 * into an [IpTable] and names into a [DomainTable], both carrying a decision
 * per entry.
 *
 * Order is the one the screen states and [RoutingSocksServer] implements: block,
 * then direct, then proxy. It beats specificity deliberately. A name covered by
 * a block rule and by a longer direct rule is blocked, because "block → direct
 * → proxy" is the sentence the user was shown, and letting the longer rule win
 * would quietly make that sentence false.
 *
 * Every line is accounted for. A category nobody publishes, one not downloaded
 * yet, a line that parses as nothing — all of them stay in [entries] with a
 * reason. A rule that decides nothing is worse than no rule at all, because the
 * user believes it is working; the only defence is to say so.
 */
class UserRules private constructor(
    private val domains: DomainTable,
    private val nets: IpTable,
    val entries: List<Entry>,
) {

    /** One line of one list, and what became of it. */
    class Entry(
        val list: Decision,
        val rule: String,
        val kind: RuleKind,
        val status: RuleStatus,
        /** Rules it contributed: a category is worth thousands, a domain one. */
        val covers: Int,
    ) {
        val isActive: Boolean get() = status == RuleStatus.ACTIVE
    }

    val isEmpty: Boolean get() = domains.isEmpty && nets.isEmpty

    val size: Int get() = entries.sumOf { it.covers }

    fun entriesFor(list: Decision): List<Entry> = entries.filter { it.list == list }

    /** [Decision.UNKNOWN] when the user's own lists have nothing to say. */
    fun decideDomain(host: String): Decision = domains.lookup(host) ?: Decision.UNKNOWN

    fun decideIp(address: InetAddress): Decision = nets.lookup(address) ?: Decision.UNKNOWN

    companion object {

        val EMPTY = UserRules(DomainTable(), IpTable(), emptyList())

        /**
         * Every category the config names, so the catalogue can fetch them in
         * one pass instead of one download per line.
         */
        fun categoriesIn(config: RoutingConfig): Set<CategoryName> {
            val out = LinkedHashSet<CategoryName>()
            for (list in ORDER) {
                for (rule in config.rulesFor(list)) {
                    val kind = RoutingConfig.kindOf(rule)
                    if (kind == RuleKind.GEOSITE || kind == RuleKind.GEOIP) {
                        out += CategoryName(kind, categoryName(rule))
                    }
                }
            }
            return out
        }

        /**
         * Category payloads run to megabytes, so this is never work for the main
         * thread — and with a [CategorySource] that fetches, it is network too.
         */
        fun build(config: RoutingConfig, categories: CategorySource): UserRules {
            val domains = DomainTable()
            val nets = IpTable()
            val entries = ArrayList<Entry>()
            for (list in ORDER) {
                for (rule in config.rulesFor(list)) {
                    entries += admit(list, rule, domains, nets, categories)
                }
            }
            return UserRules(domains, nets, entries)
        }

        private val ORDER = listOf(Decision.BLOCK, Decision.DIRECT, Decision.PROXY)

        private fun admit(
            list: Decision,
            rule: String,
            domains: DomainTable,
            nets: IpTable,
            categories: CategorySource,
        ): Entry {
            val kind = RoutingConfig.kindOf(rule)
            val value = rule.trim().lowercase()
            return when (kind) {
                RuleKind.DOMAIN -> {
                    // `*.x`, `.x` and `x` are the same suffix; the editor takes
                    // all three spellings, so they land in one place.
                    domains.addSuffix(value.removePrefix("*.").removePrefix("."), list)
                    Entry(list, rule, kind, RuleStatus.ACTIVE, 1)
                }
                RuleKind.CIDR -> {
                    val prefix = parsePrefix(value)
                    if (prefix == null) {
                        Entry(list, rule, kind, RuleStatus.UNPARSEABLE, 0)
                    } else {
                        nets.add(prefix.first, prefix.second, list)
                        Entry(list, rule, kind, RuleStatus.ACTIVE, 1)
                    }
                }
                RuleKind.GEOSITE, RuleKind.GEOIP ->
                    admitCategory(list, rule, kind, domains, nets, categories)
                RuleKind.UNSUPPORTED -> Entry(list, rule, kind, RuleStatus.UNPARSEABLE, 0)
            }
        }

        private fun admitCategory(
            list: Decision,
            rule: String,
            kind: RuleKind,
            domains: DomainTable,
            nets: IpTable,
            categories: CategorySource,
        ): Entry {
            val lookup = categories.lookup(kind, categoryName(rule))
            val blob = lookup.blob ?: return Entry(list, rule, kind, lookup.status, 0)
            // Read once to prove it whole, then again to apply it. A payload
            // that stops halfway would otherwise leave part of a category in
            // force while the screen reported the rule as not in effect, and
            // the report has to be true or it is worse than absent.
            if (runCatching { RuleSet.read(blob, { _, _ -> }, { _, _ -> }, { _, _, _ -> }) }.isFailure) {
                return Entry(list, rule, kind, RuleStatus.UNPARSEABLE, 0)
            }
            // The action byte inside a category is whatever the compiler wrote
            // for the profile it was cut from. Here the list the name was typed
            // into is the decision, and nothing else is.
            var covers = 0
            RuleSet.read(
                blob,
                onV4 = { bits, length -> nets.addV4(bits, length, list); covers++ },
                onV6 = { network, length -> nets.addV6(network, length, list); covers++ },
                onDomain = { exact, _, name ->
                    if (exact) domains.addExact(name, list) else domains.addSuffix(name, list)
                    covers++
                },
            )
            return Entry(list, rule, kind, RuleStatus.ACTIVE, covers)
        }

        private fun categoryName(rule: String): String = rule.trim().lowercase()
            .removePrefix(RoutingConfig.GEOSITE_PREFIX)
            .removePrefix(RoutingConfig.GEOIP_PREFIX)

        /**
         * Handing the literal to the platform is safe here and only here:
         * [RoutingConfig.kindOf] has already established it is an address, so
         * there is nothing left for a resolver to look up.
         */
        private fun parsePrefix(value: String): Pair<InetAddress, Int>? {
            val slash = value.indexOf('/')
            val literal = if (slash < 0) value else value.substring(0, slash)
            val address = runCatching { InetAddress.getByName(literal) }.getOrNull() ?: return null
            val width = if (address is Inet4Address) 32 else 128
            val length = if (slash < 0) width else value.substring(slash + 1).toIntOrNull() ?: return null
            return if (length in 0..width) address to length else null
        }
    }
}

/**
 * Names carrying a decision each, matched by suffix.
 *
 * The walk is [RuleSet]'s — full name, then each parent — but the answer is
 * not. There it is the first hit, most specific first; here it is the strongest
 * hit, because these entries come from three lists whose order the user was
 * promised.
 */
internal class DomainTable {

    private val exact = HashMap<String, Decision>()
    private val suffix = HashMap<String, Decision>()

    val isEmpty: Boolean get() = exact.isEmpty() && suffix.isEmpty()

    fun addExact(name: String, decision: Decision) {
        exact[name] = strongest(exact[name], decision)
    }

    fun addSuffix(name: String, decision: Decision) {
        suffix[name] = strongest(suffix[name], decision)
    }

    fun lookup(host: String): Decision? {
        val name = host.lowercase().trimEnd('.')
        var best: Decision? = null
        exact[name]?.let {
            if (it == Decision.BLOCK) return it
            best = strongest(best, it)
        }
        var index = 0
        while (index >= 0 && index < name.length) {
            suffix[name.substring(index)]?.let {
                if (it == Decision.BLOCK) return it
                best = strongest(best, it)
            }
            val dot = name.indexOf('.', index)
            index = if (dot < 0) -1 else dot + 1
        }
        return best
    }
}

/**
 * Prefixes carrying a decision each.
 *
 * v4 is bucketed by prefix length like [RuleSet]'s, so a lookup is at most 33
 * hash probes; v6 is a linear scan, which is what a user's handful of v6 rules
 * and the odd country-sized category can afford.
 *
 * Longest-prefix is not the tie-break — block beats direct beats proxy however
 * long the prefixes are. Two orders would need explaining; the one the screen
 * already states wins.
 */
internal class IpTable {

    private val v4 = HashMap<Int, HashMap<Int, Decision>>()
    private val v6 = ArrayList<Triple<ByteArray, Int, Decision>>()

    val isEmpty: Boolean get() = v4.isEmpty() && v6.isEmpty()

    fun add(address: InetAddress, length: Int, decision: Decision) = when (address) {
        is Inet4Address -> addV4(
            ByteBuffer.wrap(address.address).order(ByteOrder.BIG_ENDIAN).int, length, decision)
        else -> addV6(address.address, length, decision)
    }

    fun addV4(bits: Int, length: Int, decision: Decision) {
        val masked = if (length == 0) 0 else bits and (-1 shl (32 - length))
        val bucket = v4.getOrPut(length) { HashMap() }
        bucket[masked] = strongest(bucket[masked], decision)
    }

    fun addV6(network: ByteArray, length: Int, decision: Decision) {
        v6.add(Triple(network, length, decision))
    }

    fun lookup(address: InetAddress): Decision? {
        var best: Decision? = null
        when (address) {
            is Inet4Address -> {
                val bits = ByteBuffer.wrap(address.address).order(ByteOrder.BIG_ENDIAN).int
                for (length in 32 downTo 0) {
                    val bucket = v4[length] ?: continue
                    val masked = if (length == 0) 0 else bits and (-1 shl (32 - length))
                    val hit = bucket[masked] ?: continue
                    if (hit == Decision.BLOCK) return hit
                    best = strongest(best, hit)
                }
            }
            is Inet6Address -> {
                val raw = address.address
                for ((network, length, decision) in v6) {
                    if (!covers(network, length, raw)) continue
                    if (decision == Decision.BLOCK) return decision
                    best = strongest(best, decision)
                }
            }
        }
        return best
    }

    private fun covers(network: ByteArray, length: Int, raw: ByteArray): Boolean {
        var remaining = length
        var index = 0
        while (remaining > 0 && index < 16) {
            val take = if (remaining >= 8) 8 else remaining
            val mask = (0xFF shl (8 - take)) and 0xFF
            if ((raw[index].toInt() and mask) != (network[index].toInt() and mask)) return false
            remaining -= take
            index++
        }
        return true
    }
}

/** Block, then direct, then proxy — the order the screen states. */
private fun strongest(held: Decision?, offered: Decision): Decision =
    if (held == null || rank(offered) > rank(held)) offered else held

private fun rank(decision: Decision): Int = when (decision) {
    Decision.BLOCK -> 3
    Decision.DIRECT -> 2
    Decision.PROXY -> 1
    Decision.UNKNOWN -> 0
}
