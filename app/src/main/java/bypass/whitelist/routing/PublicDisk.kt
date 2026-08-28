package bypass.whitelist.routing

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Reads a file out of a published Yandex Disk resource.
 *
 * Anonymous by design. `cloud-api.yandex.net` is one of the few hosts that
 * survives a strict whitelist tariff, which is why the rule blob and the
 * category catalogue are published there rather than served from the backend —
 * they have to be reachable on exactly the networks the tunnel does not exist
 * on yet.
 *
 * The API hands back a one-shot `href` rather than the bytes, so every read is
 * two requests. Both live here so there is one place that knows it.
 */
internal object PublicDisk {

    /**
     * @param path a file inside a published folder, `/manifest.json` style.
     * Null when the key points at a single file, which is how the rule blob and
     * its manifest are published.
     */
    fun read(publicKey: String, path: String? = null, readTimeoutMs: Int = 180_000): ByteArray {
        val api = buildString {
            append("https://cloud-api.yandex.net/v1/disk/public/resources/download")
            append("?public_key=").append(URLEncoder.encode(publicKey, StandardCharsets.UTF_8.name()))
            if (path != null) append("&path=").append(URLEncoder.encode(path, StandardCharsets.UTF_8.name()))
        }
        val href = org.json.JSONObject(fetch(api, 20_000).toString(Charsets.UTF_8))
            .optString("href").ifEmpty { throw IllegalStateException("no href") }
        return fetch(href, readTimeoutMs)
    }

    private fun fetch(url: String, readTimeoutMs: Int): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = readTimeoutMs
        }
        return try {
            // The catalogue bundles run to megabytes and arrive without a
            // reliable Content-Length, so grow a buffer rather than trust one.
            conn.inputStream.use { input ->
                val out = ByteArrayOutputStream(DEFAULT_BUFFER_SIZE)
                input.copyTo(out)
                out.toByteArray()
            }
        } finally {
            conn.disconnect()
        }
    }
}
