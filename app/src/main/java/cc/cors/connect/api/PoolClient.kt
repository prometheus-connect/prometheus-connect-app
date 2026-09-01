package cc.cors.connect.api

import bypass.whitelist.BuildConfig
import bypass.whitelist.tunnel.CallPlatform
import bypass.whitelist.tunnel.TunnelMode
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Reads the pre-minted call pool that the server publishes for clients which
 * cannot reach the instance API at all.
 *
 * On a strict RU mobile whitelist tariff the minting API is unreachable —
 * `functions.yandexcloud.net` sits in AS Yandex.Cloud, which operators exclude
 * because it is rentable, and so does the backend's own host. `cloud-api.yandex.net`
 * is a *consumer* Yandex service and does pass, so the server advertises a handful
 * of already-created calls through a published Disk folder and we pick one up here.
 *
 * The payload rides in the FILENAMES of zero-byte files, because the anonymous
 * public listing is the only Disk read that needs neither a token nor a second
 * host: the file *content* would redirect us to downloader.disk.yandex.ru, and
 * `custom_properties` is silently omitted from anonymous reads. So the folder
 * listing is the whole protocol.
 *
 * Nothing here is authenticated — by design, since any secret shipped in the APK
 * is not a secret. The pool is only ever the first seconds of a session: once the
 * tunnel is up, the app reaches the real API over clean internet and moves to its
 * own subscription-gated instance.
 */
class PoolClient(
    private val publicKey: String = BuildConfig.PC_POOL_PUBLIC_KEY,
) {

    /**
     * Settings the server publishes into the same folder, as
     * `cfg--<key>--<base64url(value)>`. They ride here for the same reason the
     * calls do: only the anonymous Disk *listing* is known to survive a
     * whitelist channel, so anything the app must be able to re-learn has to fit
     * in a filename. Unknown keys are ignored, so the server can add settings
     * without waiting for an app release.
     */
    data class RemoteConfig(val values: Map<String, String>) {
        val baseUrl: String? get() = values["baseUrl"]?.takeIf { it.startsWith("https://") }
        val minVersionCode: Int get() = values["minVersionCode"]?.toIntOrNull() ?: 0
        val message: String get() = values["message"].orEmpty()
        val isEmpty: Boolean get() = values.isEmpty()
    }

    /** Everything one listing tells us: the calls and the settings. */
    data class Snapshot(val entries: List<Entry>, val config: RemoteConfig)

    val isConfigured: Boolean
        get() = publicKey.isNotBlank() && !publicKey.startsWith("REPLACE_")

    /**
     * One advertised call. [seq] is the server's ordering; lower is the one it
     * would rather we took.
     */
    data class Entry(
        val seq: Int,
        val platform: CallPlatform,
        val mode: TunnelMode,
        val expiresAt: Long,
        val url: String,
        /**
         * The orchestrator's id for this call, echoed back when adopting it.
         * Empty for entries published before the id was added to the format —
         * such a call still works as a tunnel, it just cannot be adopted, so
         * the session stays on the anonymous window.
         */
        val backendId: String,
    )

    /**
     * Fetches the pool, newest-usable first. Blocking — call off the main thread.
     * Returns an empty list rather than throwing when the pool is simply empty;
     * a genuine transport failure still throws [CorsException] so the caller can
     * tell "no pool" from "no network".
     */
    fun fetch(): List<Entry> = fetchAll().entries

    /**
     * One request, both halves. Kept separate from [fetch] so existing callers
     * are untouched.
     */
    fun fetchAll(): Snapshot {
        val url = LISTING_BASE + "?public_key=" +
            URLEncoder.encode(publicKey, StandardCharsets.UTF_8.name()) +
            "&limit=" + LIMIT
        val body = get(url)
        val items = JSONObject(body)
            .optJSONObject("_embedded")
            ?.optJSONArray("items")
            ?: return Snapshot(emptyList(), RemoteConfig(emptyMap()))

        val now = System.currentTimeMillis() / 1000
        val out = ArrayList<Entry>(items.length())
        val cfg = HashMap<String, String>()
        for (i in 0 until items.length()) {
            val name = items.optJSONObject(i)?.optString("name").orEmpty()
            if (name.startsWith(CONFIG_PREFIX)) {
                parseConfig(name)?.let { (k, v) -> cfg[k] = v }
                continue
            }
            val entry = parse(name) ?: continue
            // The published expiry is rounded down to a coarse bucket by the
            // server, so treat it as advisory and keep a margin: a call that is
            // about to lapse is worse than no call, because the tunnel would
            // come up and die a minute later.
            if (entry.expiresAt - now < MIN_REMAINING_SECONDS) continue
            out.add(entry)
        }
        return Snapshot(out.sortedBy { it.seq }, RemoteConfig(cfg))
    }

    /** `cfg--<key>--<base64url value>`; anything malformed is skipped silently. */
    private fun parseConfig(name: String): Pair<String, String>? {
        val parts = name.split("--", limit = 3)
        if (parts.size != 3 || parts[1].isBlank()) return null
        return try {
            val decoded = String(
                android.util.Base64.decode(
                    parts[2],
                    android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP,
                ),
                StandardCharsets.UTF_8,
            )
            parts[1] to decoded
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * `<seq>--<platform>--<mode>--<expiryEpoch>--<instanceId>--<payload>`
     *
     * The five-field form without the instance id is the original layout and is
     * still accepted: during a rollout the folder can hold both, and refusing
     * the older one would leave a client with no tunnel at all rather than one
     * it merely cannot adopt.
     */
    private fun parse(name: String): Entry? {
        val parts = name.split("--")
        if (parts.size != 5 && parts.size != 6) return null
        val seq = parts[0].toIntOrNull() ?: return null
        val expiresAt = parts[3].toLongOrNull() ?: return null
        val backendId = if (parts.size == 6) parts[4] else ""
        val payload = parts.last()
        if (payload.isBlank()) return null

        val platform = when (parts[1]) {
            "wbstream" -> CallPlatform.WBSTREAM
            "telemost" -> CallPlatform.TELEMOST
            "dion" -> CallPlatform.DION
            "vk" -> CallPlatform.VK
            else -> return null
        }
        val mode = when (parts[2]) {
            "dc" -> TunnelMode.DC
            "video" -> TunnelMode.VIDEO
            else -> return null
        }
        val url = when (platform) {
            CallPlatform.WBSTREAM -> "wbstream://$payload"
            CallPlatform.DION -> "dion://$payload"
            CallPlatform.TELEMOST -> "https://telemost.yandex.ru/j/$payload"
            CallPlatform.VK -> payload
        }
        // Ask for what the server advertised, but let the platform veto it —
        // DC does not exist on Telemost or DION and would be silently coerced
        // anyway, so record the mode we will actually run.
        return Entry(seq, platform, mode.forPlatform(platform), expiresAt, url, backendId)
    }

    private fun get(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = try {
                conn.responseCode
            } catch (e: Exception) {
                throw CorsException(0, e.message ?: "network error")
            }
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw CorsException(code, text.take(200))
            return text
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val CONFIG_PREFIX = "cfg--"
        const val LISTING_BASE = "https://cloud-api.yandex.net/v1/disk/public/resources"
        const val LIMIT = 50
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000
        /** Refuse a call with less than this left; see [fetch]. */
        const val MIN_REMAINING_SECONDS = 120
    }
}
