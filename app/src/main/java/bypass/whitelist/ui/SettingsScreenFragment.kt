package bypass.whitelist.ui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import bypass.whitelist.App
import bypass.whitelist.R
import bypass.whitelist.tunnel.TunnelMode
import bypass.whitelist.util.Callback
import bypass.whitelist.util.ParamCallback
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import bypass.whitelist.BuildConfig
import bypass.whitelist.update.UpdateCheck
import bypass.whitelist.util.Prefs
import bypass.whitelist.util.ThemeMode
import java.lang.ref.WeakReference

class SettingsScreenFragment : Fragment(R.layout.fragment_settings_screen) {

    interface Host {
        fun onTunnelModeChanged(mode: TunnelMode)
        fun onForgetAllDestinations()
        fun onResetAllSettings()
        fun onCorsSignInWithTelegram()
        fun onCorsForgetInstance()
        fun onCorsBaseUrlChanged()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val root = view.findViewById<LinearLayout>(R.id.settingsContent)
        root.removeAllViews()
        root.addView(buildAppearanceSection())
        root.addView(buildCorsSection())
        root.addView(buildTunnelSection())
        root.addView(buildNetworkSection())
        root.addView(buildBehaviorSection())
        root.addView(buildDangerSection())
    }

    override fun onResume() {
        super.onResume()
        rebuild()
        // One of the two moments the check can succeed: the user is here and
        // reading, on whatever network they have. It never blocks the screen —
        // the row appears if and when an answer arrives, and stays away when
        // none does.
        val host = activity ?: return
        val weakSelf = WeakReference(this)
        UpdateCheck.refresh {
            host.runOnUiThread {
                val self = weakSelf.get() ?: return@runOnUiThread
                if (self.isAdded) self.rebuild()
            }
        }
    }

    fun refresh() {
        rebuild()
    }

    private fun host(): Host? = activity as? Host

    private fun buildCorsSection(): View {
        val section = newSection(R.string.cors_section_service)
        val card = section.findViewById<LinearLayout>(R.id.sectionCard)

        val baseUrl = Prefs.corsBaseUrl
        addRow(card, R.drawable.ic_setting_tunnel, getString(R.string.cors_base_url), baseUrl, null) {
            InputActionSheet.show(
                manager = parentFragmentManager,
                title = getString(R.string.cors_base_url),
                subtitle = getString(R.string.cors_base_url_sub),
                fieldLabel = getString(R.string.cors_base_url_label),
                initialValue = baseUrl,
            ) { value ->
                val normalized = value.trim().trimEnd('/')
                if (normalized.isNotEmpty() && normalized != baseUrl) {
                    Prefs.corsBaseUrl = normalized
                    host()?.onCorsBaseUrlChanged()
                    rebuild()
                }
            }
        }

        val accountLabel = if (Prefs.corsSignedIn) Prefs.corsUsername
        else getString(R.string.cors_account_signed_out)
        addRow(card, R.drawable.ic_setting_theme, getString(R.string.cors_account), accountLabel, null) {
            host()?.onCorsSignInWithTelegram()
        }

        addRow(card, R.drawable.ic_setting_trash, getString(R.string.cors_forget_instance), getString(R.string.cors_forget_instance_sub), null, danger = true) {
            ConfirmActionSheet.show(
                manager = parentFragmentManager,
                title = getString(R.string.cors_forget_instance),
                subtitle = getString(R.string.cors_forget_instance_sub),
                confirmLabel = getString(R.string.confirm_forget),
                cancelLabel = getString(R.string.sheet_cancel),
                destructive = true,
            ) { host()?.onCorsForgetInstance() }
        }
        return section
    }

    private fun buildAppearanceSection(): View {
        val section = newSection(R.string.settings_section_appearance)
        val card = section.findViewById<LinearLayout>(R.id.sectionCard)
        addRow(card, R.drawable.ic_setting_theme, getString(R.string.settings_row_theme), getString(R.string.settings_row_theme_sub), Prefs.themeMode.label) {
            ChoiceActionSheet.show(
                manager = parentFragmentManager,
                title = getString(R.string.settings_row_theme),
                subtitle = getString(R.string.settings_row_theme_sub),
                options = ThemeMode.entries.map { ChoiceActionSheet.Option(it.name, it.label) },
                selectedId = Prefs.themeMode.name,
            ) { picked ->
                val mode = ThemeMode.valueOf(picked.id)
                if (mode != Prefs.themeMode) {
                    Prefs.themeMode = mode
                    App.applyTheme(mode)
                    rebuild()
                }
            }
        }
        return section
    }

    private fun buildTunnelSection(): View {
        val section = newSection(R.string.settings_section_tunnel)
        val card = section.findViewById<LinearLayout>(R.id.sectionCard)

        addRow(card, R.drawable.ic_setting_tunnel, getString(R.string.settings_row_tunnel_mode), null, Prefs.tunnelMode.label) {
            ChoiceActionSheet.show(
                manager = parentFragmentManager,
                title = getString(R.string.settings_row_tunnel_mode),
                options = TunnelMode.entries.map { ChoiceActionSheet.Option(it.name, it.label) },
                selectedId = Prefs.tunnelMode.name,
            ) { picked ->
                val newMode = TunnelMode.valueOf(picked.id)
                if (newMode != Prefs.tunnelMode) {
                    Prefs.tunnelMode = newMode
                    host()?.onTunnelModeChanged(newMode)
                    rebuild()
                }
            }
        }

        val vp8Sub = buildString {
            append(getString(R.string.settings_row_vp8_sub, Prefs.vp8Fps, Prefs.vp8Batch))
            if (Prefs.dualTrack) append(" / ").append(getString(R.string.settings_row_vp8_flag_dual))
            if (Prefs.reliable) append(" / ").append(getString(R.string.settings_row_vp8_flag_kcp))
        }
        addRow(card, R.drawable.ic_setting_vp8, getString(R.string.settings_row_vp8), vp8Sub, null) {
            Vp8ActionSheet.show(parentFragmentManager, Prefs.vp8Fps, Prefs.vp8Batch, Prefs.dualTrack, Prefs.reliable) { fps, batch, dual, reliable ->
                Prefs.vp8Fps = fps
                Prefs.vp8Batch = batch
                Prefs.dualTrack = dual
                Prefs.reliable = reliable
                rebuild()
            }
        }

        addRow(card, R.drawable.ic_setting_autofill, getString(R.string.settings_row_autofill), if (Prefs.autofillEnabled) Prefs.autofillName else getString(R.string.settings_row_autofill_off), null) {
            AutofillActionSheet.show(parentFragmentManager) { rebuild() }
        }

        return section
    }

    private fun buildNetworkSection(): View {
        val section = newSection(R.string.settings_section_network)
        val card = section.findViewById<LinearLayout>(R.id.sectionCard)

        // Per-app split tunneling lives one level deeper now, under the routing
        // screen: both answer "what skips the tunnel", and two sibling entries
        // asking that question had no way to say how they differed.
        val routing = Prefs.routingConfig
        val routingSummary = if (routing.splitRouting) {
            resources.getQuantityString(R.plurals.routing_summary_split, routing.ruleCount, routing.ruleCount)
        } else {
            getString(R.string.routing_summary_off)
        }
        addRow(card, R.drawable.ic_setting_split, getString(R.string.settings_row_split_routing), routingSummary, null) {
            (activity as? MainActivityHost)?.pushSubPage(SplitRoutingScreenFragment())
        }

        addRow(card, R.drawable.ic_setting_proxy, getString(R.string.settings_row_proxy), getString(R.string.settings_row_proxy_sub, Prefs.socksPort), null) {
            ProxyActionSheet.show(parentFragmentManager) { rebuild() }
        }

        addRow(card, R.drawable.ic_setting_dns, getString(R.string.settings_row_dns), Prefs.dnsMode.label, null) {
            DnsActionSheet.show(parentFragmentManager) { rebuild() }
        }

        return section
    }

    private fun buildBehaviorSection(): View {
        val section = newSection(R.string.settings_section_behavior)
        val card = section.findViewById<LinearLayout>(R.id.sectionCard)

        addSwitchRow(card, R.drawable.ic_setting_headless, getString(R.string.settings_row_headless), getString(R.string.settings_row_headless_sub), Prefs.headless) { checked ->
            Prefs.headless = checked
        }
        addSwitchRow(card, R.drawable.ic_setting_reconnect, getString(R.string.settings_row_reconnect), getString(R.string.settings_row_reconnect_sub), Prefs.connectOnStart) { checked ->
            Prefs.connectOnStart = checked
        }
        // Hidden unless debug is on. Lifting the pool guard is how the router
        // gets exercised at all on a network that bootstraps from the pool every
        // time — and it is also how someone strands themselves on a whitelist
        // tariff, so it stays out of reach of anyone not looking for it.
        if (Prefs.debug) {
            addSwitchRow(card, R.drawable.ic_setting_tunnel, getString(R.string.settings_row_split_routing_force), getString(R.string.settings_row_split_routing_force_sub), Prefs.splitRoutingForce) { checked ->
                Prefs.splitRoutingForce = checked
            }
        }
        addSwitchRow(card, R.drawable.ic_setting_debug, getString(R.string.settings_row_debug), getString(R.string.settings_row_debug_sub), Prefs.debug) { checked ->
            Prefs.debug = checked
        }
        // The build was invisible from inside the app, which makes both testing
        // and any later "which version are you on?" a guess. Tapping it copies
        // the full identifier, so a bug report can carry it verbatim.
        val build = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        addRow(card, R.drawable.ic_setting_debug, getString(R.string.settings_row_version),
            null, build) {
            val clipboard = requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("version", build))
            Toast.makeText(requireContext(), R.string.settings_row_version_copied,
                Toast.LENGTH_SHORT).show()
        }
        // Directly under the version it supersedes, so the two read as one
        // statement: this is what you are running, this is what there is. It
        // exists only when there is something newer — an app that is current
        // has nothing to say here, and says nothing.
        UpdateCheck.pending?.let { update ->
            val size = UpdateActionSheet.sizeLabel(requireContext(), update)
            addRow(card, R.drawable.ic_arrow_down,
                getString(R.string.update_row_title, update.version),
                size?.let { getString(R.string.update_row_sub_sized, it) }
                    ?: getString(R.string.update_row_sub),
                null) {
                UpdateActionSheet.show(parentFragmentManager, update)
            }
        }
        return section
    }

    private fun buildDangerSection(): View {
        val section = newSection(R.string.settings_section_danger)
        val card = section.findViewById<LinearLayout>(R.id.sectionCard)
        addRow(card, R.drawable.ic_setting_reset, getString(R.string.settings_reset_all), getString(R.string.settings_reset_all_sub), null, danger = true) {
            ConfirmActionSheet.show(
                manager = parentFragmentManager,
                title = getString(R.string.settings_reset_all),
                subtitle = getString(R.string.settings_reset_all_sub),
                confirmLabel = getString(R.string.confirm_reset),
                cancelLabel = getString(R.string.sheet_cancel),
                destructive = true,
            ) { host()?.onResetAllSettings() }
        }
        addRow(card, R.drawable.ic_setting_trash, getString(R.string.settings_forget_all_destinations), getString(R.string.settings_forget_all_destinations_sub), null, danger = true) {
            ConfirmActionSheet.show(
                manager = parentFragmentManager,
                title = getString(R.string.settings_forget_all_destinations),
                subtitle = getString(R.string.settings_forget_all_destinations_sub),
                confirmLabel = getString(R.string.confirm_forget),
                cancelLabel = getString(R.string.sheet_cancel),
                destructive = true,
            ) { host()?.onForgetAllDestinations() }
        }
        return section
    }

    private fun newSection(labelRes: Int): View =
        SettingsRows.newSection(this, view as ViewGroup?, labelRes)

    private fun addRow(
        card: LinearLayout,
        iconRes: Int,
        title: String,
        sub: String?,
        trail: String?,
        danger: Boolean = false,
        onClick: Callback,
    ) = SettingsRows.addRow(this, card, iconRes, title, sub, trail, danger, onClick)

    private fun addSwitchRow(
        card: LinearLayout,
        iconRes: Int,
        title: String,
        sub: String?,
        initial: Boolean,
        onToggled: ParamCallback<Boolean>,
    ) = SettingsRows.addSwitchRow(this, card, iconRes, title, sub, initial, onToggled)

    private fun rebuild() {
        if (!isAdded) return
        val rootView = view ?: return
        onViewCreated(rootView, null)
    }
}
