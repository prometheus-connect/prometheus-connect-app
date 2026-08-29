package bypass.whitelist.update

import android.util.Log
import bypass.whitelist.BuildConfig
import bypass.whitelist.util.Callback
import bypass.whitelist.util.Prefs
import cc.cors.connect.api.CorsClient
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Asks GitHub whether a newer APK has been published, at the moments the
 * question can actually be answered.
 *
 * `api.github.com` is on no operator whitelist, so on the tariffs this app
 * exists for a check at launch is a check that always fails. The two callers
 * are the settings screen and a tunnel that has just come up: one is a moment
 * the user is already reading, the other is the moment the network can carry
 * the question at all.
 *
 * Nothing here reports a failure. An unanswered check leaves whatever was
 * cached in place and says nothing — a user cannot act on "could not reach
 * GitHub", and on these networks it would be the normal case, not an incident.
 */
object UpdateCheck {

    /**
     * The release the last successful check found, or null when there is none.
     *
     * Re-tested against the running build rather than trusted: once the user
     * installs the update the cache still names it, and comparing again is what
     * makes the notice disappear on first launch of the new build instead of
     * waiting for a network answer that may not come for days.
     */
    val pending: AppUpdate?
        get() = Prefs.cachedUpdate?.takeIf { AppUpdate.isNewer(it.version, BuildConfig.VERSION_NAME) }

    /**
     * Asks, off the main thread, at most once every [MIN_INTERVAL_MS].
     *
     * @param onAnswer runs on the worker thread once an answer has been stored
     * — hop to the UI yourself. Not called when nothing was asked, and not
     * called when nothing answered.
     */
    fun refresh(onAnswer: Callback? = null) {
        val sinceLast = System.currentTimeMillis() - Prefs.updateCheckedAtMs
        // A clock that moved backwards makes the age negative. Ask again rather
        // than sit the check out until real time catches up.
        if (sinceLast in 0 until MIN_INTERVAL_MS) return
        if (!inFlight.compareAndSet(false, true)) return
        thread(name = "update-check", isDaemon = true) {
            try {
                val answer = AppUpdate.parse(latestRelease(), BuildConfig.VERSION_NAME)
                // A yanked or superseded release has to clear the notice, so
                // "there is no update" is stored as deliberately as an update.
                Prefs.cachedUpdate = answer
                Prefs.updateCheckedAtMs = System.currentTimeMillis()
                onAnswer?.invoke()
            } catch (e: Exception) {
                // Deliberately only a log line. The timestamp is left untouched
                // so the next settings screen or tunnel tries again, instead of
                // a single failure buying six hours of silence.
                Log.i(TAG, "update check skipped: ${e.message}")
            } finally {
                inFlight.set(false)
            }
        }
    }

    private fun latestRelease(): String = try {
        fetch(useSocks = false)
    } catch (_: Exception) {
        // The app excludes itself from its own VpnService, so our own requests
        // never ride the tunnel we just brought up — on a filtered network the
        // relay's local SOCKS5 is the only route to api.github.com. Costs a
        // refused connect to a closed local port when no tunnel is running,
        // which is immediate.
        fetch(useSocks = true)
    }

    private fun fetch(useSocks: Boolean): String {
        val url = URL(LATEST_RELEASE_URL)
        val opened = if (useSocks) url.openConnection(CorsClient.socksProxy()) else url.openConnection()
        val conn = (opened as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            // GitHub rejects a request with no User-Agent outright, and what
            // the platform sends by default is not one worth relying on.
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("github answered $code")
            conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private const val TAG = "UpdateCheck"
    /** The public endpoint, no token: anonymous and rate-limited by address. */
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/prometheus-connect/prometheus-connect-app/releases/latest"
    /** Releases are days apart. Asking oftener than this only spends battery. */
    private const val MIN_INTERVAL_MS = 6 * 60 * 60 * 1000L
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000
    private val USER_AGENT = "PrometheusConnect/${BuildConfig.VERSION_NAME}"
    private val inFlight = AtomicBoolean(false)
}
