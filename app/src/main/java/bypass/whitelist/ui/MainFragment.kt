package bypass.whitelist.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.fragment.app.Fragment
import bypass.whitelist.R
import bypass.whitelist.tunnel.CallConfig
import bypass.whitelist.tunnel.CallPlatform
import bypass.whitelist.tunnel.TunnelMode
import bypass.whitelist.tunnel.VpnStatus
import bypass.whitelist.update.UpdateCheck
import bypass.whitelist.util.Prefs
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainFragment : Fragment(R.layout.fragment_main_screen) {

    private val scanQrLauncher = registerForActivityResult(ScanContract()) { result ->
        val scanned = result.contents?.trim().orEmpty()
        if (scanned.isNotEmpty()) {
            AddDestinationSheet.show(parentFragmentManager, scanned)
        }
    }

    private var content: MainFragmentView? = null
    private var pendingStatus: VpnStatus? = null
    private var connectedSinceMs: Long = 0L

    // Account state shown by the persistent card. Seeded from Prefs so a
    // restart doesn't render as "not signed in" before the first callback.
    private var corsSignedIn: Boolean = Prefs.corsSignedIn
    private var corsUsername: String = Prefs.corsUsername
    /** When the current anonymous session runs out; 0 when there isn't one. */
    private var anonExpiresAtMs: Long = 0L
    /** Signed in to Telegram, but the account carries no active subscription. */
    private var noSubscription: Boolean = false

    private val tickHandler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            refreshStats()
            renderCorsAccount()
            tickHandler.postDelayed(this, 1000L)
        }
    }

    interface Host {
        fun onConnectPressed(config: CallConfig)
        fun onDisconnectPressed()
        fun onPingPressed(callback: (success: Boolean, rttMs: Int) -> Unit)
        fun isTunnelActive(): Boolean
        fun currentStatus(): VpnStatus?
        fun onCorsConnectPressed()
        fun onCorsSignInPressed()
    }

    override fun onViewCreated(rootView: View, savedInstanceState: Bundle?) {
        val container = MainFragmentView(rootView)
        content = container

        container.bindCalls(Prefs.savedDestinations, Prefs.activeDestinationId)
        container.bindHero(connected = isHostConnected(), status = hostStatus())
        if (!isResumed) container.pauseAnimations()

        container.onAddCallClicked = {
            AddDestinationSheet.show(parentFragmentManager)
        }
        container.onCorsSignInClicked = {
            host()?.onCorsSignInPressed()
        }
        container.onUpdateClicked = {
            UpdateCheck.pending?.let { UpdateActionSheet.show(parentFragmentManager, it) }
        }
        container.onScanQrClicked = {
            scanQrLauncher.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt(getString(R.string.scan_qr_prompt))
                    .setBeepEnabled(false)
                    .setOrientationLocked(false)
                    .setCaptureActivity(QrCaptureActivity::class.java)
            )
        }
        container.onHeroPressed = {
            if (isHostConnected() || isHostConnecting()) {
                host()?.onDisconnectPressed()
            } else {
                // One button for the whole thing. With a saved call it joins
                // that; otherwise it runs the Prometheus flow, which creates a
                // tunnel and — if nobody is signed in — walks through the
                // Telegram sign-in on its own. Before this, the hero did
                // nothing at all until you had saved a call by hand.
                val active = Prefs.activeDestination
                if (active != null) {
                    host()?.onConnectPressed(active)
                } else {
                    host()?.onCorsConnectPressed()
                }
            }
        }
        container.onPingPressed = {
            container.showPingRunning()
            host()?.onPingPressed { success, rttMs ->
                container.showPingResult(success, rttMs)
            }
        }
        container.onCallSelected = { config ->
            Prefs.activeDestinationId = config.id
            container.bindCalls(Prefs.savedDestinations, Prefs.activeDestinationId)
        }
        container.onCallLongPressed = ::showRowMenu

        pendingStatus?.let { container.bindStatus(it) }
        pendingStatus = null
        renderCorsAccount()
        renderUpdate()
    }

    override fun onResume() {
        super.onResume()
        content?.bindCalls(Prefs.savedDestinations, Prefs.activeDestinationId)
        content?.bindHero(connected = isHostConnected(), status = hostStatus())
        content?.resumeAnimations()
        renderCorsAccount()
        renderUpdate()
        if (isHostConnected() || anonExpiresAtMs > 0L) {
            tickHandler.removeCallbacks(tickRunnable)
            tickHandler.postDelayed(tickRunnable, 1000L)
        }
    }

    override fun onPause() {
        super.onPause()
        content?.pauseAnimations()
        tickHandler.removeCallbacks(tickRunnable)
    }

    override fun onDestroyView() {
        tickHandler.removeCallbacks(tickRunnable)
        content?.detach()
        content = null
        super.onDestroyView()
    }

    fun onStatusChanged(status: VpnStatus) {
        val container = content
        if (container != null) {
            container.bindStatus(status)
        } else {
            pendingStatus = status
        }
        if (isHostConnected()) refreshStats()
    }

    fun onStatusTextChanged(text: String) {
        content?.bindStatusText(text)
    }

    /** The instance was claimed — the session is now unlimited. */
    fun onCorsSignedIn(username: String) {
        corsSignedIn = true
        corsUsername = username
        noSubscription = false
        anonExpiresAtMs = 0L
        renderCorsAccount()
    }

    /**
     * An anonymous instance is running and will expire after [ttlSeconds].
     * Passing a non-positive ttl just marks the session anonymous without a
     * countdown (we know the state but not the deadline).
     */
    fun onCorsAnonymous(ttlSeconds: Int) {
        corsSignedIn = false
        anonExpiresAtMs =
            if (ttlSeconds > 0) System.currentTimeMillis() + ttlSeconds * 1000L else 0L
        renderCorsAccount()
        // The countdown has to tick even before the tunnel reports connected,
        // otherwise the remaining time sits frozen during the join.
        tickHandler.removeCallbacks(tickRunnable)
        tickHandler.postDelayed(tickRunnable, 1000L)
    }

    /**
     * Restates the stored account without touching a running countdown.
     *
     * Called on every resume — including the resume that happens on the way
     * back from Telegram. Routing that through onCorsAnonymous() would zero the
     * deadline and freeze the timer at "sessions limited to 5 minutes" exactly
     * when the user most needs to see how long is left.
     */
    fun onCorsAccountRefreshed(signedIn: Boolean, username: String) {
        corsSignedIn = signedIn
        if (signedIn) {
            corsUsername = username
            anonExpiresAtMs = 0L
        }
        renderCorsAccount()
    }

    /**
     * Telegram identity confirmed, but the account has no active subscription.
     * The card stays amber — the session really is still capped — but says why
     * instead of asking the user to sign in again.
     */
    fun onCorsNoSubscription() {
        corsSignedIn = false
        noSubscription = true
        renderCorsAccount()
    }

    /** No session is running any more; drop any countdown but keep the account. */
    fun onCorsSessionEnded() {
        anonExpiresAtMs = 0L
        renderCorsAccount()
    }

    private fun renderUpdate() {
        val view = content ?: return
        val update = UpdateCheck.pending
        view.bindUpdate(
            version = update?.version,
            size = update?.let { UpdateActionSheet.sizeLabel(requireContext(), it) },
        )
    }

    private fun renderCorsAccount() {
        val view = content ?: return
        if (corsSignedIn) {
            view.bindCorsAccount(true, corsUsername, remaining = null, expired = false,
                noSubscription = false)
            return
        }
        val deadline = anonExpiresAtMs
        if (deadline <= 0L) {
            view.bindCorsAccount(false, "", remaining = null, expired = false,
                noSubscription = noSubscription)
            return
        }
        val leftMs = deadline - System.currentTimeMillis()
        if (leftMs <= 0L) {
            view.bindCorsAccount(false, "", remaining = null, expired = true,
                noSubscription = noSubscription)
            return
        }
        val totalSeconds = (leftMs / 1000L).toInt()
        val formatted = String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60)
        view.bindCorsAccount(false, "", remaining = formatted, expired = false,
            noSubscription = noSubscription)
    }

    fun onConnectedChanged(connected: Boolean) {
        if (connected) {
            if (connectedSinceMs == 0L) connectedSinceMs = System.currentTimeMillis()
            // The other moment worth asking GitHub: api.github.com is on no
            // operator whitelist, so for a good share of these users a tunnel
            // that just came up is the only network the question travels on.
            // Every path that reports a live tunnel funnels through here.
            val host = activity
            if (host != null) UpdateCheck.refresh { host.runOnUiThread { renderUpdate() } }
        } else {
            connectedSinceMs = 0L
        }
        if (!isResumed) return
        content?.bindHero(connected = connected, status = hostStatus())
        if (connected) {
            refreshStats()
            tickHandler.removeCallbacks(tickRunnable)
            tickHandler.postDelayed(tickRunnable, 1000L)
        } else if (anonExpiresAtMs <= 0L) {
            // Only stop when there is genuinely nothing left to update. The
            // anonymous countdown starts when the instance is created, which is
            // *before* the tunnel reports connected — stopping the ticker here
            // unconditionally froze it at 5:00 for the whole join, which is
            // exactly when the remaining time matters.
            tickHandler.removeCallbacks(tickRunnable)
        }
    }

    fun onDestinationsChanged() {
        content?.bindCalls(Prefs.savedDestinations, Prefs.activeDestinationId)
    }

    private fun showRowMenu(config: CallConfig) {
        val tunnelMode = (config.tunnelMode ?: Prefs.tunnelMode).forPlatform(config.platform)
        val vp8Sub = buildString {
            append(getString(R.string.settings_row_vp8_sub, config.vp8Fps ?: Prefs.vp8Fps, config.vp8Batch ?: Prefs.vp8Batch))
            if (config.dualTrack ?: Prefs.dualTrack) append(" / ").append(getString(R.string.settings_row_vp8_flag_dual))
            if (config.reliable ?: Prefs.reliable) append(" / ").append(getString(R.string.settings_row_vp8_flag_kcp))
        }
        MenuActionSheet.show(
            manager = parentFragmentManager,
            title = config.name,
            subtitle = config.url,
            items = listOf(
                MenuActionSheet.MenuItem("tunnel", getString(R.string.settings_row_tunnel_mode), R.drawable.ic_setting_tunnel, value = tunnelMode.label),
                MenuActionSheet.MenuItem("vp8", getString(R.string.settings_row_vp8), R.drawable.ic_setting_vp8, value = vp8Sub),
                MenuActionSheet.MenuItem("rename", getString(R.string.destination_menu_rename), R.drawable.ic_action_pencil),
                MenuActionSheet.MenuItem("delete", getString(R.string.destination_menu_delete), R.drawable.ic_setting_trash, danger = true),
            ),
        ) { item ->
            when (item.id) {
                "tunnel" -> editTunnelMode(config)
                "vp8" -> editVp8(config)
                "rename" -> promptRename(config)
                "delete" -> confirmDelete(config)
            }
        }
    }

    private fun editTunnelMode(config: CallConfig) {
        val current = (config.tunnelMode ?: Prefs.tunnelMode).forPlatform(config.platform)
        ChoiceActionSheet.show(
            manager = parentFragmentManager,
            title = getString(R.string.settings_row_tunnel_mode),
            options = TunnelMode.entries.filter { it == TunnelMode.VIDEO || (config.platform != CallPlatform.TELEMOST && config.platform != CallPlatform.DION) }.map { ChoiceActionSheet.Option(it.name, it.label) },
            selectedId = current.name,
        ) { picked ->
            val newMode = TunnelMode.valueOf(picked.id)
            Prefs.updateDestination(config.copy(tunnelMode = newMode))
            onDestinationsChanged()
            if (Prefs.activeDestinationId == config.id) {
                (activity as? SettingsScreenFragment.Host)?.onTunnelModeChanged(newMode)
            }
        }
    }

    private fun editVp8(config: CallConfig) {
        Vp8ActionSheet.show(
            parentFragmentManager,
            config.vp8Fps ?: Prefs.vp8Fps,
            config.vp8Batch ?: Prefs.vp8Batch,
            config.dualTrack ?: Prefs.dualTrack,
            config.reliable ?: Prefs.reliable,
        ) { fps, batch, dual, reliable ->
            Prefs.updateDestination(config.copy(vp8Fps = fps, vp8Batch = batch, dualTrack = dual, reliable = reliable))
            onDestinationsChanged()
        }
    }

    private fun promptRename(config: CallConfig) {
        InputActionSheet.show(
            manager = parentFragmentManager,
            title = getString(R.string.destination_rename_title),
            fieldLabel = getString(R.string.sheet_field_name),
            initialValue = config.name,
        ) { newName ->
            if (newName != config.name) {
                Prefs.renameDestination(config.id, newName)
                onDestinationsChanged()
            }
        }
    }

    private fun confirmDelete(config: CallConfig) {
        ConfirmActionSheet.show(
            manager = parentFragmentManager,
            title = getString(R.string.destination_delete_title),
            subtitle = getString(R.string.destination_delete_confirm, config.name),
            confirmLabel = getString(R.string.confirm_delete),
            cancelLabel = getString(R.string.sheet_cancel),
            destructive = true,
        ) {
            Prefs.removeDestination(config.id)
            onDestinationsChanged()
        }
    }

    private fun refreshStats() {
        val view = content ?: return
        val uptimeMs = if (connectedSinceMs > 0L) System.currentTimeMillis() - connectedSinceMs else 0L
        val active = Prefs.activeDestination
        val mode = if (active != null) Prefs.activeTunnelMode.forPlatform(active.platform) else Prefs.tunnelMode
        view.setStats(uptimeText = formatUptime(uptimeMs), mode = mode.label)
    }

    private fun formatUptime(ms: Long): String {
        if (ms <= 0L) return "00:00:00"
        val totalSeconds = ms / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds / 60L) % 60L
        val seconds = totalSeconds % 60L
        return "%02d:%02d:%02d".format(hours, minutes, seconds)
    }

    private fun host(): Host? = activity as? Host

    private fun isHostConnected(): Boolean = host()?.isTunnelActive() ?: false

    private fun isHostConnecting(): Boolean = when (hostStatus()) {
        VpnStatus.STOPPING,
        VpnStatus.CONNECTING,
        VpnStatus.STARTING,
        VpnStatus.CALL_CONNECTED,
        VpnStatus.DATACHANNEL_OPEN -> true
        else -> false
    }

    private fun hostStatus(): VpnStatus? = host()?.currentStatus()
}
