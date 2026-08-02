package cc.cors.connect.cors

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import bypass.whitelist.tunnel.CallConfig
import bypass.whitelist.util.Prefs
import cc.cors.connect.api.CorsClient
import cc.cors.connect.api.CorsException
import cc.cors.connect.api.CreateInstanceOut
import cc.cors.connect.api.InstanceState

/**
 * Orchestrates the Cors.Connect instance lifecycle as described in
 * ANDROID_INTEGRATION.md §5:
 *
 *  1. health check
 *  2. create temp instance
 *  3. poll until output_link appears
 *  4. hand the output_link to the host (which connects through the existing
 *     tunnel/relay pipeline so the user authorizes inside the spawned service)
 *  5. claim once Telegram initData is available (or signal that login is needed)
 *  6. stop on disconnect
 *
 * After a successful claim, the controller runs a periodic heartbeat that
 * extends the instance lifetime (authenticated users only). The heartbeat runs
 * on a dedicated [HandlerThread] and briefly holds a partial wake lock per beat
 * so it keeps firing when the screen is off / the app is backgrounded (the
 * tunnel's foreground service keeps the process alive; the wake lock defeats
 * Doze batching of the handler).
 *
 * The controller runs network work on a background thread and reports progress
 * on the main thread via [Host]. It is single-use: create a new one per connect.
 */
class CorsInstanceController(
    private val context: Context,
    private val client: CorsClient,
    private val host: Host,
) {

    interface Host {
        /** Called on the main thread when a human-readable status changes. */
        fun onCorsStatus(text: String)
        /** Called on the main thread when the output_link is ready; the host should connect to it. */
        fun onCorsOutputReady(config: CallConfig)
        /**
         * Called on the main thread when the instance is running but unclaimed,
         * with the seconds it has left. The UI needs this to say *which* kind of
         * session is up — without it "connected" looks the same whether it lasts
         * five minutes or indefinitely.
         */
        fun onCorsAnonymous(ttlSeconds: Int)

        /** Called on the main thread when Telegram login is required to complete the claim. */
        fun onCorsNeedsTelegram()
        /** Called on the main thread when the flow has succeeded (claimed). */
        fun onCorsClaimed(username: String)
        /** Called on the main thread when the flow has failed terminally. */
        fun onCorsFailed(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var stopped = false
    @Volatile private var currentInstanceId: Int = 0
    @Volatile private var currentClaimToken: String? = null

    // ---- heartbeat (post-claim lifetime extension) ----------------------
    @Volatile private var sessionToken: String = ""
    private val heartbeatThread: HandlerThread by lazy {
        HandlerThread("cors-heartbeat").apply { start() }
    }
    private val heartbeatHandler: Handler by lazy { Handler(heartbeatThread.looper) }
    private val wakeLock: PowerManager.WakeLock by lazy {
        (context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CorsConnect:heartbeat")
            .apply { setReferenceCounted(false) }
    }
    @Volatile private var heartbeatRunning: Boolean = false

    fun start() {
        stopped = false
        bg {
            if (!client.isConfigured) {
                failRes("cors_status_token_missing")
                return@bg
            }
            try {
                status("cors_status_creating")
                val health = client.health()
                if (!health.serviceAvailable) { failRes("cors_status_unavailable"); return@bg }
                if (!health.telegramEnabled) post { host.onCorsStatus(resString("cors_status_telegram_off")) }

                // Send Telegram initData when we have it: the server then REUSES
                // any still-live instance for this user (no duplicate), or creates
                // + claims on our behalf. Without initData we get a 5-min temp +
                // claim_token and must claim manually later.
                val created = createInstanceResilient()
                currentInstanceId = created.instanceId
                currentClaimToken = created.claimToken
                Prefs.corsInstanceId = created.instanceId
                Prefs.corsClaimToken = created.claimToken.orEmpty()

                // The server may have pre-claimed (created+claimed) or reused an
                // already-claimed instance: in both cases it returns a session
                // token, so we record it and skip the manual /claim step.
                val alreadyClaimed = created.token != null
                if (!alreadyClaimed) {
                    val ttl = created.tempTtlSeconds ?: 0
                    post { host.onCorsAnonymous(ttl) }
                }
                if (alreadyClaimed) {
                    sessionToken = created.token!!
                    Prefs.corsSessionToken = created.token!!
                    created.username?.let { Prefs.corsUsername = it }
                    currentClaimToken = null
                    Prefs.corsClaimToken = ""
                }

                // output_link may already be present (especially on reuse);
                // otherwise poll until it appears.
                val ready = created.outputLink ?: pollForOutput(created.instanceId)
                if (stopped) { stopTracked(); return@bg }
                if (ready == null) { failRes("cors_status_failed"); return@bg }

                val config = CallConfig.newWith(
                    name = resString("cors_instance_name"),
                    url = ready,
                )
                post {
                    if (!stopped) {
                        host.onCorsStatus(resString("cors_status_ready"))
                        host.onCorsOutputReady(config)
                    }
                }

                if (alreadyClaimed) {
                    // Server already owns/claimed this instance for us — just keep
                    // it alive via heartbeat. No /claim call needed.
                    startHeartbeat()
                    val name = Prefs.corsUsername.ifEmpty { created.username.orEmpty() }
                    if (name.isNotEmpty()) post { host.onCorsClaimed(name) }
                } else {
                    // Temp instance: best-effort claim. If initData isn't available
                    // yet, ask the host to perform Telegram login; claim resumes
                    // via [resumeClaim].
                    tryClaim()
                }
            } catch (e: CorsException) {
                when (e.code) {
                    0 -> failRes("cors_status_network")
                    404 -> failRes("cors_status_disabled")
                    429 -> failRes("cors_status_cap")
                    else -> failDetail("cors_status_failed", e.detail)
                }
            } catch (e: Exception) {
                failDetail("cors_status_failed", e.message ?: "error")
            }
        }
    }

    /** Resumes the claim step after Telegram initData has arrived. */
    fun resumeClaim() {
        bg { tryClaim() }
    }

    /**
     * Calls [CorsClient.createInstance], falling back to the anonymous flow if
     * the cached Telegram initData turns out to be stale/invalid (401).
     *
     * initData is captured once (on Telegram sign-in) and cached indefinitely
     * in [Prefs]; the server enforces a replay window on it (~24h by default),
     * so it routinely goes stale between sign-in and use. Without this
     * fallback, a 401 here used to abort the whole connect attempt and leave
     * the stale initData in place, permanently breaking every future connect
     * until the user found their way back to "Sign in with Telegram" manually.
     * Clearing it here lets the flow fall through to the normal temp +
     * claim_token path (which itself re-prompts for Telegram sign-in via
     * [tryClaim]) instead of failing outright.
     */
    private fun createInstanceResilient(): CreateInstanceOut {
        val initData = TelegramAuth.initData()
        if (initData.isEmpty()) {
            return client.createInstance(serviceId = null, telegramInitData = null)
        }
        return try {
            client.createInstance(serviceId = null, telegramInitData = initData)
        } catch (e: CorsException) {
            if (e.code == 401) {
                TelegramAuth.clear()
                client.createInstance(serviceId = null, telegramInitData = null)
            } else {
                throw e
            }
        }
    }

    /** Stops the current temp instance (idempotent). Safe to call from any thread. */
    fun stop() {
        stopped = true
        stopHeartbeat()
        bg { stopTracked() }
    }

    // ---- internals -------------------------------------------------------

    private fun pollForOutput(instanceId: Int): String? {
        var attempts = 0
        while (!stopped && attempts < MAX_POLL_ATTEMPTS) {
            attempts++
            try {
                val state: InstanceState = client.getInstance(instanceId)
                if (state.isTerminal) {
                    if (state.status != "stopped") failDetail("cors_status_failed", state.error ?: state.status)
                    return null
                }
                state.outputLink?.let { return it }
            } catch (_: CorsException) { /* transient — keep polling */ }
            sleep(POLL_INTERVAL_MS)
        }
        return null
    }

    private fun tryClaim() {
        if (stopped) return
        if (currentInstanceId == 0) return
        // Only the temp (unauthenticated) flow has a claim_token. If the server
        // already pre-claimed/reused, currentClaimToken is null and there is
        // nothing to claim.
        val claimToken = currentClaimToken
        if (claimToken.isNullOrEmpty()) return
        val initData = TelegramAuth.initData()
        if (initData.isEmpty()) {
            post { host.onCorsNeedsTelegram() }
            return
        }
        try {
            post { host.onCorsStatus(resString("cors_status_claiming")) }
            val out = client.claim(currentInstanceId, claimToken, initData)
            Prefs.corsSessionToken = out.token
            Prefs.corsUsername = out.username
            sessionToken = out.token
            // Claim consumed the token.
            Prefs.corsClaimToken = ""
            currentClaimToken = null
            // Start heartbeating to keep the instance alive beyond the 5-min
            // default (authorized users only — the server rejects heartbeats
            // from accounts without can_create_instances with 403).
            startHeartbeat()
            post { host.onCorsClaimed(out.username) }
        } catch (e: CorsException) {
            // 401 → bad/expired initData; ask to re-login. Others are non-fatal for
            // the ongoing session (the instance still runs under its temp TTL).
            when (e.code) {
                401 -> { TelegramAuth.clear(); post { host.onCorsNeedsTelegram() } }
                409, 410 -> { /* already claimed / ended — nothing to do */ }
                else -> post { host.onCorsStatus(detailMessage("cors_status_failed", e.detail)) }
            }
        }
    }

    // ---- heartbeat -------------------------------------------------------

    /**
     * Begins the periodic lifetime-extension heartbeat. Each beat POSTs to the
     * heartbeat endpoint with the session token, adding ~5 min to the instance's
     * remaining lifetime. Runs on a background [HandlerThread] and holds a brief
     * partial wake lock per beat so it survives screen-off / backgrounding.
     */
    private fun startHeartbeat() {
        if (heartbeatRunning) {
            Log.d(TAG, "startHeartbeat: already running, ignoring")
            return
        }
        if (currentInstanceId == 0 || sessionToken.isEmpty()) {
            Log.w(TAG, "startHeartbeat: refusing to start (instanceId=$currentInstanceId, tokenEmpty=${sessionToken.isEmpty()})")
            return
        }
        Log.i(TAG, "startHeartbeat: instanceId=$currentInstanceId, firstBeatIn=${HEARTBEAT_FIRST_DELAY_MS}ms")
        heartbeatRunning = true
        // Hold the wake lock across the whole first-delay wait, not just the
        // network call: a bare Handler.postDelayed() does NOT wake the CPU
        // from Doze/App Standby, so if the screen turns off before this fires
        // the callback simply runs late (or not at all until something else
        // wakes the device) and the instance silently expires at its original
        // 5-minute TTL even though the account is fully authorized. Keeping
        // the lock held between beats (renewed each time, bounded by a
        // timeout as a safety valve) guarantees the timer actually fires on
        // schedule.
        acquireHeartbeatWakeLock(HEARTBEAT_FIRST_DELAY_MS)
        heartbeatHandler.postDelayed(this::doHeartbeat, HEARTBEAT_FIRST_DELAY_MS)
    }

    private fun stopHeartbeat() {
        heartbeatRunning = false
        heartbeatHandler.removeCallbacksAndMessages(null)
        runCatching { if (wakeLock.isHeld) wakeLock.release() }
        // Tear down the handler thread only if it was actually started; quit()
        // is a no-op if already stopped, and the lazy init is harmless to touch.
        runCatching { heartbeatThread.quit() }
    }

    private fun acquireHeartbeatWakeLock(coveringMs: Long) {
        // setReferenceCounted(false) makes acquire() idempotent — this just
        // (re)arms the auto-release timeout further out. The buffer accounts
        // for the network call that follows the wait.
        runCatching { wakeLock.acquire(coveringMs + HEARTBEAT_WAKELOCK_TIMEOUT_MS) }
    }

    private fun doHeartbeat() {
        if (stopped || !heartbeatRunning) {
            Log.d(TAG, "doHeartbeat: skipping (stopped=$stopped, running=$heartbeatRunning)")
            return
        }
        if (currentInstanceId == 0 || sessionToken.isEmpty()) {
            Log.w(TAG, "doHeartbeat: no instance/token, stopping (instanceId=$currentInstanceId, tokenEmpty=${sessionToken.isEmpty()})")
            heartbeatRunning = false
            return
        }
        // Keep the wake lock held for the network call itself (already armed
        // by the scheduler that got us here).
        Log.i(TAG, "doHeartbeat: POSTing for instanceId=$currentInstanceId, wakeLockHeld=${wakeLock.isHeld}")
        val t0 = System.currentTimeMillis()
        try {
            val out = client.heartbeat(currentInstanceId, sessionToken)
            Log.i(TAG, "doHeartbeat: OK in ${System.currentTimeMillis() - t0}ms, expiresAt=${out.expiresAt}")
            // success — schedule the next beat, keeping the CPU awake for the
            // full wait until then (see startHeartbeat for why).
            if (!stopped && heartbeatRunning) {
                acquireHeartbeatWakeLock(HEARTBEAT_INTERVAL_MS)
                heartbeatHandler.postDelayed(this::doHeartbeat, HEARTBEAT_INTERVAL_MS)
            } else {
                runCatching { if (wakeLock.isHeld) wakeLock.release() }
            }
        } catch (e: CorsException) {
            Log.w(TAG, "doHeartbeat: FAILED after ${System.currentTimeMillis() - t0}ms code=${e.code} detail=${e.detail}")
            // Terminal auth/ownership/state errors stop the heartbeat; the
            // instance will expire on its own. Transient network errors are
            // retried on the next scheduled beat.
            when (e.code) {
                // 403 specifically = unauthorized to extend: the server caps the
                // instance at 5 minutes. Surface a clear message rather than a
                // generic failure; the instance will expire on its own.
                403 -> {
                    heartbeatRunning = false
                    runCatching { if (wakeLock.isHeld) wakeLock.release() }
                    post { host.onCorsStatus(resString("cors_status_limited_5min")) }
                }
                401, 404, 410 -> {
                    heartbeatRunning = false
                    runCatching { if (wakeLock.isHeld) wakeLock.release() }
                    post { host.onCorsStatus(detailMessage("cors_status_failed", e.detail)) }
                }
                else -> {
                    if (!stopped && heartbeatRunning) {
                        acquireHeartbeatWakeLock(HEARTBEAT_INTERVAL_MS)
                        heartbeatHandler.postDelayed(this::doHeartbeat, HEARTBEAT_INTERVAL_MS)
                    } else {
                        runCatching { if (wakeLock.isHeld) wakeLock.release() }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "doHeartbeat: unexpected exception after ${System.currentTimeMillis() - t0}ms", e)
            if (!stopped && heartbeatRunning) {
                acquireHeartbeatWakeLock(HEARTBEAT_INTERVAL_MS)
                heartbeatHandler.postDelayed(this::doHeartbeat, HEARTBEAT_INTERVAL_MS)
            } else {
                runCatching { if (wakeLock.isHeld) wakeLock.release() }
            }
        }
    }

    private fun stopTracked() {
        val id = currentInstanceId
        if (id == 0) return
        currentInstanceId = 0
        // Use the claim token for a temp (pre-claim) instance; after claim the
        // bearer-authenticated client API also works, but the app endpoint is
        // tolerant of either and the token is cleared post-claim anyway.
        val token = currentClaimToken
        runCatching { client.stop(id, token) }
        Prefs.corsInstanceId = 0
        if (!token.isNullOrEmpty()) Prefs.corsClaimToken = ""
    }

    // ---- helpers ---------------------------------------------------------

    private fun bg(block: () -> Unit) = Thread(block, "cors-controller").start()
    private fun post(block: () -> Unit) = main.post(block)
    private fun sleep(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }

    private fun resString(key: String): String = runCatching { AppRes.string(key) }.getOrElse { key }

    /**
     * Builds a "label: detail" message WITHOUT String.format, so a missing format
     * argument can never throw. [detail] is coerced to a non-null String.
     */
    private fun detailMessage(key: String, detail: Any?): String {
        val label = runCatching { AppRes.string(key) }.getOrElse { key }
        val text = detail?.toString()?.takeIf { it.isNotBlank() } ?: ""
        // Strip a trailing "%1$s" / ": %1$s" placeholder if the resource still has one.
        val clean = label
            .replace("%1\\\$s".toRegex(), "")
            .replaceFirst(Regex(":\\s*$"), "")
        return if (text.isEmpty()) clean else "$clean: $text"
    }

    private fun status(key: String) = post { host.onCorsStatus(resString(key)) }
    private fun failRes(key: String) = post { host.onCorsFailed(resString(key)) }
    private fun failDetail(key: String, detail: Any?) = post { host.onCorsFailed(detailMessage(key, detail)) }
    private fun fail(msg: String) = post { host.onCorsFailed(msg) }

    companion object {
        private const val TAG = "CorsInstanceController"
        private const val POLL_INTERVAL_MS = 1_500L
        private const val MAX_POLL_ATTEMPTS = 80  // ~2 min at 1.5s

        // Heartbeat cadence. Backend adds HEARTBEAT_EXT (300s) per beat; pinging
        // every ~120s keeps the deadline comfortably ahead of the wall clock
        // even if a beat is dropped. The first beat fires shortly after claim.
        private const val HEARTBEAT_FIRST_DELAY_MS = 30_000L      // 30s after claim
        private const val HEARTBEAT_INTERVAL_MS = 120_000L        // every 2 min
        private const val HEARTBEAT_WAKELOCK_TIMEOUT_MS = 20_000L // max per-beat hold
    }
}
