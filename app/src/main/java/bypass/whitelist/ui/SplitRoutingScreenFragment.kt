package bypass.whitelist.ui

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import bypass.whitelist.BuildConfig
import bypass.whitelist.R
import bypass.whitelist.routing.Decision
import bypass.whitelist.routing.HappRouting
import bypass.whitelist.routing.RoutingConfig
import bypass.whitelist.routing.RuleCatalogue
import bypass.whitelist.routing.RuleKind
import bypass.whitelist.routing.RuleSet
import bypass.whitelist.routing.UserRules
import bypass.whitelist.tunnel.SplitTunnelingMode
import bypass.whitelist.tunnel.TunnelVpnService
import bypass.whitelist.util.Prefs
import java.lang.ref.WeakReference
import java.text.DateFormat
import java.util.Date

/**
 * Everything that decides where a connection goes, on one screen.
 *
 * The switch at the top is the only thing most people will ever touch, and it
 * is named for what it turns on. Off means the tunnel carries everything,
 * which is what the app has always done and stays the default; on hands the
 * decision to the three lists below it.
 *
 * The profile and route-order rows are read-only on purpose. The rule blob is
 * compiled and published elsewhere and the order is wired into
 * [bypass.whitelist.routing.RoutingSocksServer], so offering them as settings
 * would be offering control that does not exist.
 *
 * The three lists follow the same rule. Each says how much of itself is in
 * effect and names every line that is not, and when the router cannot run at
 * all they open read-only with the reason. An editable field is a promise that
 * what is typed into it will be acted on; the lists may only be editable while
 * that promise holds.
 */
class SplitRoutingScreenFragment : Fragment() {

    private lateinit var summary: TextView
    private lateinit var content: LinearLayout

    /**
     * Filled in off the main thread, and null until it is.
     *
     * Both halves are megabytes on disk — the cached blob, and the category
     * payloads the user's own lists name — and compiling the second is the only
     * honest way to know which typed rules actually decide anything. Neither is
     * something a screen may do while it is being laid out.
     */
    private var snapshot: Snapshot? = null

    /** What the router would be working from if it started right now. */
    private class Snapshot(val blobRules: Int, val overlay: UserRules)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_split_routing, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        summary = view.findViewById(R.id.routingSummary)
        content = view.findViewById(R.id.routingContent)

        view.findViewById<ImageButton>(R.id.backButton).setOnClickListener { popSelf() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                isEnabled = false
                popSelf()
            }
        })

        rebuild()
        loadSnapshot()
    }

    override fun onResume() {
        super.onResume()
        // The per-app screen is a level deeper and edits its own prefs; coming
        // back from it has to show what it did.
        rebuild()
    }

    private fun popSelf() {
        (activity as? MainActivityHost)?.popSubPage()
    }

    // ---- sections ---------------------------------------------------------

    private fun rebuild() {
        if (!isAdded) return
        val config = Prefs.routingConfig
        summary.text = if (config.splitRouting) {
            resources.getQuantityString(R.plurals.routing_summary_split, config.ruleCount, config.ruleCount)
        } else {
            getString(R.string.routing_summary_off)
        }
        content.removeAllViews()
        content.addView(buildModeSection(config))
        content.addView(buildRulesSection(config))
        content.addView(buildProfileSection())
        content.addView(buildOrderSection())
        content.addView(buildAppsSection())
    }

    private fun buildModeSection(config: RoutingConfig): View {
        val section = SettingsRows.newSection(this, content, R.string.routing_section_mode)
        val card = SettingsRows.card(section)
        SettingsRows.addSwitchRow(
            this, card, R.drawable.ic_setting_split,
            getString(R.string.routing_split_switch),
            getString(R.string.routing_split_switch_sub),
            config.splitRouting,
        ) { checked ->
            Prefs.splitRoutingEnabled = checked
            notifyReconnectNeeded()
            rebuild()
        }
        return section
    }

    private fun buildRulesSection(config: RoutingConfig): View {
        val section = SettingsRows.newSection(this, content, R.string.routing_section_rules)
        val card = SettingsRows.card(section)
        val inert = inertReason(config)
        addRuleListRow(card, config, inert, Decision.PROXY, R.string.routing_list_proxy, R.drawable.ic_setting_tunnel)
        addRuleListRow(card, config, inert, Decision.DIRECT, R.string.routing_list_direct, R.drawable.ic_setting_proxy)
        addRuleListRow(card, config, inert, Decision.BLOCK, R.string.routing_list_block, R.drawable.ic_setting_trash)
        SettingsRows.addRow(
            this, card, R.drawable.ic_paste,
            getString(R.string.routing_row_import),
            getString(R.string.routing_row_import_sub),
            null,
        ) { promptImport() }
        return section
    }

    /**
     * Why none of the three lists is deciding anything, or null when they are.
     *
     * Each of these stops the router from starting at all, which makes all
     * three lists inert whatever is typed into them. Null while the snapshot is
     * still loading: claiming a list is dead before knowing would be its own
     * kind of lie.
     */
    private fun inertReason(config: RoutingConfig): String? = when {
        !config.splitRouting -> getString(R.string.routing_lists_inert_off)
        !Prefs.splitRoutingUsable -> getString(R.string.routing_lists_inert_pool)
        snapshot?.blobRules == 0 -> getString(R.string.routing_lists_inert_blob)
        else -> null
    }

    private fun addRuleListRow(
        card: LinearLayout,
        config: RoutingConfig,
        inert: String?,
        decision: Decision,
        titleRes: Int,
        iconRes: Int,
    ) {
        val rules = config.rulesFor(decision)
        val title = getString(titleRes)
        SettingsRows.addRow(this, card, iconRes, title, describe(rules, decision, inert), rules.size.toString()) {
            RuleListActionSheet.show(
                manager = parentFragmentManager,
                title = title,
                subtitle = sheetSubtitle(decision, inert),
                initialValue = RoutingConfig.formatList(rules),
                readOnly = inert != null,
            ) { text ->
                Prefs.routingConfig = Prefs.routingConfig
                    .withRules(decision, RoutingConfig.parseList(text))
                notifyReconnectNeeded()
                rebuild()
                loadSnapshot()
            }
        }
    }

    /**
     * A preview of the list, plus how much of it is actually deciding
     * something. The number of rules and the number of rules in effect are not
     * the same number, and only the second one is worth printing beside a list
     * the user is about to trust.
     */
    private fun describe(rules: List<String>, decision: Decision, inert: String?): String {
        if (rules.isEmpty()) return getString(R.string.routing_list_empty)
        val preview = rules.take(PREVIEW_RULES).joinToString(", ") +
            if (rules.size > PREVIEW_RULES) "…" else ""
        if (inert != null) return preview + " · " + getString(R.string.routing_list_inert)
        val entries = snapshot?.overlay?.entriesFor(decision) ?: return preview
        val active = entries.count { it.isActive }
        if (active == entries.size) return preview
        return preview + " · " + getString(R.string.routing_list_effect, active, entries.size)
    }

    /**
     * The editor states what it will and will not honour before a line is typed
     * into it, entry by entry with the reason. Anything less is a text box that
     * accepts rules and quietly drops half of them.
     */
    private fun sheetSubtitle(decision: Decision, inert: String?): String = buildString {
        append(getString(R.string.routing_rules_sub))
        if (inert != null) {
            append("\n\n").append(inert)
            return@buildString
        }
        val entries = snapshot?.overlay?.entriesFor(decision)
        if (entries == null) {
            append('\n').append(getString(R.string.routing_rules_checking))
            return@buildString
        }
        val idle = entries.filter { !it.isActive }
        append('\n').append(getString(R.string.routing_list_effect, entries.size - idle.size, entries.size))
        idle.forEach {
            append('\n').append(getString(R.string.routing_import_dropped_line, it.rule, getString(it.status.labelRes)))
        }
    }

    private fun buildProfileSection(): View {
        val section = SettingsRows.newSection(this, content, R.string.routing_section_profile)
        val card = SettingsRows.card(section)

        SettingsRows.addInfoRow(
            this, card, R.drawable.ic_setting_dns,
            getString(R.string.routing_profile_name), null, BuildConfig.PC_ROUTING_PROFILE,
        )
        SettingsRows.addInfoRow(
            this, card, R.drawable.ic_setting_reconnect,
            getString(R.string.routing_profile_revision), null,
            Prefs.routingRulesRevision.ifEmpty { getString(R.string.routing_profile_none) },
        )
        SettingsRows.addInfoRow(
            this, card, R.drawable.ic_setting_split,
            getString(R.string.routing_profile_rules), null,
            snapshot?.blobRules?.toString() ?: getString(R.string.routing_profile_counting),
        )
        addCategoriesRow(card)

        val cache = RuleSet.cacheFile(requireContext(), BuildConfig.PC_ROUTING_PROFILE)
        val cacheTrail = if (cache.isFile) {
            Formatter.formatShortFileSize(requireContext(), cache.length()) + " · " +
                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(cache.lastModified()))
        } else {
            getString(R.string.routing_profile_none)
        }
        SettingsRows.addInfoRow(
            this, card, R.drawable.ic_setting_debug,
            getString(R.string.routing_profile_cache), null, cacheTrail,
        )
        return section
    }

    private fun buildOrderSection(): View {
        val section = SettingsRows.newSection(this, content, R.string.routing_section_order)
        val card = SettingsRows.card(section)
        SettingsRows.addInfoRow(
            this, card, R.drawable.ic_setting_reset,
            getString(R.string.routing_order_value),
            getString(R.string.routing_order_sub),
            null,
        )
        return section
    }

    private fun buildAppsSection(): View {
        val section = SettingsRows.newSection(this, content, R.string.routing_section_apps)
        val card = SettingsRows.card(section)
        val mode = Prefs.splitTunnelingMode
        val sub = if (mode == SplitTunnelingMode.NONE) {
            getString(R.string.split_tunneling_summary_off)
        } else {
            resources.getQuantityString(
                R.plurals.split_tunneling_summary_count,
                Prefs.splitTunnelingPackages.size, getString(mode.labelRes),
                Prefs.splitTunnelingPackages.size,
            )
        }
        SettingsRows.addRow(
            this, card, R.drawable.ic_setting_split,
            getString(R.string.routing_row_per_app), sub, null,
        ) {
            (activity as? MainActivityHost)?.pushSubPage(SplitTunnelingScreenFragment())
        }
        return section
    }

    // ---- import -----------------------------------------------------------

    private fun promptImport() {
        InputActionSheet.show(
            manager = parentFragmentManager,
            title = getString(R.string.routing_import_title),
            subtitle = getString(R.string.routing_import_sub),
            fieldLabel = getString(R.string.routing_import_field),
            initialValue = clipboardLink(),
        ) { link ->
            runCatching { HappRouting.parse(link) }
                .onSuccess { confirmImport(it) }
                .onFailure {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.routing_import_failed, it.message ?: ""),
                        Toast.LENGTH_LONG,
                    ).show()
                }
        }
    }

    /** A link is nearly always pasted, so save the paste when it is already there. */
    private fun clipboardLink(): String {
        val clipboard = requireContext()
            .getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return ""
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
        return if (text.startsWith(HappRouting.LINK_PREFIX, ignoreCase = true)) text else ""
    }

    /**
     * Shows what the link would do before it does it, dropped entries included.
     * An import that quietly loses half a profile is worse than one that fails:
     * the user would go on believing rules are in force that never arrived.
     */
    private fun confirmImport(result: HappRouting.Import) {
        val report = buildString {
            append(getString(R.string.routing_import_counts, result.applied.size, result.dropped.size))
            append('\n')
            append(
                if (result.config.splitRouting) getString(R.string.routing_import_split_on)
                else getString(R.string.routing_import_split_off)
            )
            if (result.dropped.isEmpty()) {
                append("\n\n").append(getString(R.string.routing_import_nothing_dropped))
            } else {
                append("\n\n").append(getString(R.string.routing_import_dropped_header))
                result.dropped.forEach {
                    append('\n').append(
                        getString(R.string.routing_import_dropped_line, it.value, getString(it.reason.labelRes))
                    )
                }
            }
        }
        ConfirmActionSheet.show(
            manager = parentFragmentManager,
            title = getString(R.string.routing_import_title),
            subtitle = report,
            confirmLabel = getString(R.string.routing_import_apply),
            cancelLabel = getString(R.string.sheet_cancel),
        ) {
            Prefs.routingConfig = result.config
            notifyReconnectNeeded()
            rebuild()
        }
    }

    // ---- helpers ----------------------------------------------------------

    /**
     * A category the lists name but the cache has not got is reported as not in
     * effect, and until the tunnel has come up once that is every one of them.
     * This row is the way out of that: it says how many resolved and fetches
     * the rest on demand, so the report can be acted on rather than only read.
     */
    private fun addCategoriesRow(card: LinearLayout) {
        val named = snapshot?.overlay?.entries
            ?.filter { it.kind == RuleKind.GEOSITE || it.kind == RuleKind.GEOIP }
        val sub = when {
            named == null -> getString(R.string.routing_profile_counting)
            named.isEmpty() -> getString(R.string.routing_row_categories_none)
            else -> getString(R.string.routing_row_categories_state, named.count { it.isActive }, named.size)
        }
        SettingsRows.addRow(
            this, card, R.drawable.ic_arrow_down,
            getString(R.string.routing_row_categories), sub, null,
        ) {
            Toast.makeText(requireContext(), R.string.routing_categories_updating, Toast.LENGTH_SHORT).show()
            loadSnapshot(fetchCategories = true)
        }
    }

    /**
     * @param fetchCategories false everywhere except the row that asks for it.
     * Opening a screen must not cost the user megabytes on a metered
     * connection, so the report is built from the cache unless they say
     * otherwise.
     */
    private fun loadSnapshot(fetchCategories: Boolean = false) {
        val context = requireContext().applicationContext
        val weakSelf = WeakReference(this)
        val host = activity ?: return
        val config = Prefs.routingConfig
        Thread {
            val blob = RuleSet.loadCached(context, BuildConfig.PC_ROUTING_PROFILE).size
            val overlay = UserRules.build(
                config,
                RuleCatalogue.resolve(
                    context,
                    BuildConfig.PC_ROUTING_CATALOGUE_KEY,
                    UserRules.categoriesIn(config),
                    allowNetwork = fetchCategories,
                ),
            )
            if (weakSelf.get() == null) return@Thread
            host.runOnUiThread {
                val self = weakSelf.get() ?: return@runOnUiThread
                if (!self.isAdded) return@runOnUiThread
                self.snapshot = Snapshot(blob, overlay)
                self.rebuild()
                if (fetchCategories) {
                    // The outcome, not "done": a fetch that reached nothing
                    // still finishes, and saying so is the whole point.
                    val named = overlay.entries.filter { it.kind == RuleKind.GEOSITE || it.kind == RuleKind.GEOIP }
                    Toast.makeText(
                        self.requireContext(),
                        self.getString(R.string.routing_categories_result, named.count { it.isActive }, named.size),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }.start()
    }

    /** Routing is read once, when the tunnel comes up; saying so beats a silent no-op. */
    private fun notifyReconnectNeeded() {
        if (TunnelVpnService.instance?.isRunning == true) {
            Toast.makeText(requireContext(), R.string.split_tunneling_mode_changed, Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        const val PREVIEW_RULES = 2
    }
}
