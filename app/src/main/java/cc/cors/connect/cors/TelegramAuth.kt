package cc.cors.connect.cors

import android.content.Context
import android.content.Intent
import android.net.Uri
import bypass.whitelist.BuildConfig
import bypass.whitelist.util.Prefs

/**
 * Acquires and stores the Telegram WebApp `initData` needed by the claim flow.
 *
 * Flow: the app opens `https://t.me/<bot>?startapp=...` in the Telegram app; the
 * bot's WebApp returns to this app via an HTTPS App Link
 * (`https://<CALLBACK_HOST>/tginit?initdata=...`) with the signed `initData`.
 * Telegram's WebView rejects non-standard (custom-scheme) links, so the callback
 * must be a real HTTPS URL that Android intercepts as an App Link (see the
 * intent-filter in `AndroidManifest.xml`). [handleCallback] is called from the
 * Activity's intent handling to ingest it.
 *
 * NOTE: the App Link host is intentionally *not* tied to the configurable API
 * server URL ([bypass.whitelist.util.Prefs.corsBaseUrl]). The bot always
 * redirects to the fixed host declared in the manifest, so the matcher must
 * agree with the manifest + `tg-auth.html`, not with the API server.
 */
object TelegramAuth {

    /**
     * The HTTPS App Link the bot's WebApp redirects to. The host comes from
     * `PC_CALLBACK_HOST` in the build config, which also fills the manifest's
     * intent-filter placeholder — so the matcher here and the filter that makes
     * Android intercept the URL can never disagree. It must equally match the
     * host serving `/webapp` and `/.well-known/assetlinks.json`.
     */
    private const val CALLBACK_SCHEME = "https"
    private val CALLBACK_HOST = BuildConfig.PC_CALLBACK_HOST
    private const val CALLBACK_PATH = "/tginit"

    /** Scheme/host/path of the App Link the bot returns to. */
    val SCHEME: String get() = CALLBACK_SCHEME
    val HOST: String get() = CALLBACK_HOST
    val PATH: String get() = CALLBACK_PATH

    val botUsername: String = BuildConfig.PC_TG_BOT

    val hasInitData: Boolean get() = Prefs.corsTgInitData.isNotEmpty()
    fun initData(): String = Prefs.corsTgInitData

    fun storeInitData(value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return
        // Light validation: real Telegram WebApp initData is a query string
        // containing at least a "hash=" and "auth_date=" or "query_id=".
        if (!looksLikeInitData(trimmed)) return
        Prefs.corsTgInitData = trimmed
    }

    fun looksLikeInitData(value: String): Boolean {
        val v = value.lowercase()
        return v.contains("hash=") && (v.contains("auth_date=") || v.contains("query_id=") || v.contains("user="))
    }

    fun clear() {
        Prefs.corsTgInitData = ""
    }

    /** True if [uri] is an `https://<host>/tginit?initdata=...` App Link callback. */
    fun isCallback(uri: Uri?): Boolean {
        if (uri == null) return false
        if (!uri.scheme.orEmpty().equals(SCHEME, ignoreCase = true)) return false
        if (!uri.host.orEmpty().equals(HOST, ignoreCase = true)) return false
        // Match the manifest's pathPrefix="/tginit"; ignore a trailing slash.
        val path = uri.path.orEmpty().trimEnd('/')
        return path == PATH.trimEnd('/')
    }

    /**
     * Extracts the initData from a callback URI.
     *
     * Telegram's initData is itself a query string (contains & and =), so the
     * bot must pass it percent-encoded. We try the standard query param first,
     * then fall back to a manual scan of the raw encoded scheme-specific part
     * to be resilient to URI-parsing quirks across WebView/browser bridges.
     */
    fun extractInitData(uri: Uri): String? {
        // Fast path: standard query parameter (already URL-decoded by Android).
        val direct = runCatching {
            uri.getQueryParameter("initdata") ?: uri.getQueryParameter("initData")
        }.getOrNull()
        if (!direct.isNullOrEmpty()) return direct.trim()

        // Fallback: pull the raw (still percent-encoded) string after "initdata=".
        // Works whether it sits in the query or the fragment, and preserves the
        // inner &/= of the initData payload.
        val raw = uri.toString()
        val idx = raw.indexOf("initdata=", ignoreCase = true)
        if (idx < 0) return null
        val after = raw.substring(idx + "initdata=".length)
        // Cut at the next top-level separator if present.
        val end = indexOfFirstSeparator(after)
        val token = after.substring(0, end)
        return decode(token).trim().takeIf { it.isNotEmpty() }
    }

    private fun indexOfFirstSeparator(s: String): Int {
        // Split only on an unencoded '&' or '#' or ';'.
        for (i in s.indices) {
            val c = s[i]
            if (c == '&' || c == '#' || c == ';') return i
        }
        return s.length
    }

    private fun decode(s: String): String =
        try { android.net.Uri.decode(s) } catch (_: Exception) { s }

    /**
     * Opens the Cors.Connect bot in Telegram with a `startapp` payload.
     * Returns false if no app can handle the t.me link.
     *
     * The bot is expected to open a WebApp that ultimately navigates to
     * `https://<host>/tginit?initdata=<URL-encoded initData>` (an HTTPS App Link
     * the Android intent-filter intercepts).
     */
    fun startLogin(context: Context, payload: String = "login"): Boolean {
        val bot = botUsername.trim().removePrefix("@")
        val link = "https://t.me/$bot?startapp=${Uri.encode(payload)}"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            intent.resolveActivity(context.packageManager)?.let {
                context.startActivity(intent)
                true
            } ?: false
        } catch (_: Exception) {
            false
        }
    }
}
