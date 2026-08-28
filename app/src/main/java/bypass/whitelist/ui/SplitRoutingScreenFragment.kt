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
import bypass.whitelist.routing.RuleKind
import bypass.whitelist.routing.RuleSet
import bypass.whitelist.tunnel.SplitTunnelingMode
import bypass.whitelist.tunnel.TunnelVpnService
import bypass.whitelist.util.Prefs
import java.lang.ref.WeakReference
import java.text.DateFormat
import java.util.Date

/**
 * Everything that decides where a connection goes, on one screen.
 *
 * The switch at the top is the only thing most people will ever touch: global
 * proxy on means the tunnel carries everything, which is what the app has
 * always done and stays the default. Off hands the decision to the three lists
 * below it.
 *
 * The profile and route-order rows are read-only on purpose. The rule blob is
 * compiled and published elsewhere and the order is wired into
 * [bypass.whitelist.routing.RoutingSocksServer], so offering them as settings
 * would be offering control that does not exist.
 */
class SplitRoutingScreenFragment : Fragment() {

    private lateinit var summary: TextView
    private lateinit var content: LinearLayout

    /**
     * Filled in off the main thread. The cached blob is megabytes and parsing
     * it is the only way to know how many rules it holds, which is not
     * something a screen may do while it is being laid out.
     */
    private var cachedRuleCount: Int? = null

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
        countCachedRules()
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
        summary.text = if (config.globalProxy) {
            getString(R.string.routing_summary_global)
        } else {
            resources.getQuantityString(R.plurals.routing_summary_split, config.ruleCount, config.ruleCount)
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
            this, card, R.drawable.ic_setting_tunnel,
            getString(R.string.routing_global_proxy),
            getString(R.string.routing_global_proxy_sub),
            config.globalProxy,
        ) { checked ->
            Prefs.routingGlobalProxy = checked
            notifyReconnectNeeded()
            rebuild()
        }
        return section
    }

    private fun buildRulesSection(config: RoutingConfig): View {
        val section = SettingsRows.newSection(this, content, R.string.routing_section_rules)
        val card = SettingsRows.card(section)
        addRuleListRow(card, config, Decision.PROXY, R.string.routing_list_proxy, R.drawable.ic_setting_tunnel)
        addRuleListRow(card, config, Decision.DIRECT, R.string.routing_list_direct, R.drawable.ic_setting_proxy)
        addRuleListRow(card, config, Decision.BLOCK, R.string.routing_list_block, R.drawable.ic_setting_trash)
        SettingsRows.addRow(
            this, card, R.drawable.ic_paste,
            getString(R.string.routing_row_import),
            getString(R.string.routing_row_import_sub),
            null,
        ) { promptImport() }
        return section
    }

    private fun addRuleListRow(
        card: LinearLayout,
        config: RoutingConfig,
        decision: Decision,
        titleRes: Int,
        iconRes: Int,
    ) {
        val rules = config.rulesFor(decision)
        val title = getString(titleRes)
        SettingsRows.addRow(this, card, iconRes, title, describe(rules), rules.size.toString()) {
            RuleListActionSheet.show(
                manager = parentFragmentManager,
                title = title,
                subtitle = getString(R.string.routing_rules_sub),
                initialValue = RoutingConfig.formatList(rules),
            ) { text ->
                Prefs.routingConfig = Prefs.routingConfig
                    .withRules(decision, RoutingConfig.parseList(text))
                notifyReconnectNeeded()
                rebuild()
            }
        }
    }

    /**
     * A preview of the list plus, if there is one, the count of lines this app
     * has no way to act on — a rule that will never match is worth saying out
     * loud rather than leaving to look like every other line.
     */
    private fun describe(rules: List<String>): String {
        if (rules.isEmpty()) return getString(R.string.routing_list_empty)
        val preview = rules.take(PREVIEW_RULES).joinToString(", ") +
            if (rules.size > PREVIEW_RULES) "…" else ""
        val unsupported = rules.count { RoutingConfig.kindOf(it) == RuleKind.UNSUPPORTED }
        if (unsupported == 0) return preview
        return preview + " · " + getString(R.string.routing_list_unsupported, unsupported)
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
            cachedRuleCount?.toString() ?: getString(R.string.routing_profile_counting),
        )

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
                Prefs.splitTunnelingPackages.size, mode.label, Prefs.splitTunnelingPackages.size,
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
                if (result.config.globalProxy) getString(R.string.routing_import_global_on)
                else getString(R.string.routing_import_global_off)
            )
            if (result.dropped.isEmpty()) {
                append("\n\n").append(getString(R.string.routing_import_nothing_dropped))
            } else {
                append("\n\n").append(getString(R.string.routing_import_dropped_header))
                result.dropped.forEach {
                    append('\n').append(getString(R.string.routing_import_dropped_line, it.value, it.reason))
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

    private fun countCachedRules() {
        val context = requireContext().applicationContext
        val weakSelf = WeakReference(this)
        val host = activity ?: return
        Thread {
            val count = RuleSet.loadCached(context, BuildConfig.PC_ROUTING_PROFILE).size
            if (weakSelf.get() == null) return@Thread
            host.runOnUiThread {
                val self = weakSelf.get() ?: return@runOnUiThread
                if (!self.isAdded) return@runOnUiThread
                self.cachedRuleCount = count
                self.rebuild()
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
