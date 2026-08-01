package cc.cors.connect.api

import bypass.whitelist.BuildConfig
import bypass.whitelist.util.Prefs
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Minimal client for the Cors.Connect Android instance-creation API
 * (the /api/app endpoints) plus the Telegram login endpoint (/api/auth/telegram).
 *
 * Every request carries the shared static secret in the X-App-Token header
 * (WB_APP_TOKEN on the server). Methods are blocking — call them off the
 * main thread. Errors are surfaced as [CorsException].
 */
class CorsClient(
    private val baseUrl: String = Prefs.corsBaseUrl,
    private val appToken: String = BuildConfig.CORS_APP_TOKEN,
) {

    init {
        require(baseUrl.isNotBlank()) { "CORS_BASE_URL not set" }
    }

    val isConfigured: Boolean get() = appToken.isNotBlank() && appToken != "REPLACE_WITH_WB_APP_TOKEN"

    // ---- endpoints -------------------------------------------------------

    fun health(): Health = Health.parse(request("GET", "/api/app/health"))

    fun createInstance(
        serviceId: Int? = null,
        telegramInitData: String? = null,
    ): CreateInstanceOut {
        // Always send a JSON body: the server requires a request body to be
        // present even when every field is optional (Pydantic "Field required"
        // on body otherwise). service_id is omitted to use the server default.
        //
        // When telegramInitData is supplied, the server REUSES the user's
        // still-live instance (if any) instead of spawning a duplicate, or
        // creates + claims on their behalf. The response then carries a session
        // token (CreateInstanceOut.token) and reused=true/false so the caller
        // can skip the separate /claim step.
        val body = JSONObject()
        if (serviceId != null) body.put("service_id", serviceId)
        if (!telegramInitData.isNullOrEmpty()) body.put("telegram_init_data", telegramInitData)
        return CreateInstanceOut.parse(request("POST", "/api/app/instances", body))
    }

    fun claim(instanceId: Int, claimToken: String, telegramInitData: String): ClaimOut {
        val body = JSONObject()
            .put("telegram_init_data", telegramInitData)
            .put("claim_token", claimToken)
        return ClaimOut.parse(request("POST", "/api/app/instances/$instanceId/claim", body))
    }

    fun getInstance(instanceId: Int): InstanceState =
        InstanceState.parse(request("GET", "/api/app/instances/$instanceId"))

    fun stop(instanceId: Int, claimToken: String? = null) {
        // Always send a JSON body for consistency; claim_token is omitted when
        // stopping a post-claim instance.
        val body = JSONObject()
        if (claimToken != null) body.put("claim_token", claimToken)
        request("DELETE", "/api/app/instances/$instanceId", body)
    }

    /**
     * Extend a claimed instance's lifetime (authenticated clients only).
     * [sessionToken] is the bearer token returned by claim/login; the app sends
     * it as `Authorization: Bearer`. Throws [CorsException] on 401/403/404/410.
     */
    fun heartbeat(instanceId: Int, sessionToken: String): HeartbeatOut =
        HeartbeatOut.parse(request(
            "POST", "/api/app/instances/$instanceId/heartbeat",
            body = JSONObject(), bearer = sessionToken,
        ))

    /** Standalone Telegram login — issues a session token for stored initData. */
    fun telegramLogin(initData: String): LoginOut {
        val body = JSONObject().put("initData", initData)
        return LoginOut.parse(request("POST", "/api/auth/telegram", body))
    }

    // ---- plumbing --------------------------------------------------------

    private fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        bearer: String? = null,
    ): JSONObject {
        val conn = (URL(buildRequestUrl(path)).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("X-App-Token", appToken)
            // Optional bearer (session token) for authenticated endpoints such
            // as the heartbeat. Empty/null is silently omitted.
            //
            // When routed through the Yandex Cloud Function proxy, a literal
            // "Authorization" header is intercepted by the Yandex platform
            // itself for *invocation* auth — it tries to validate our app
            // session token as a Yandex IAM/API-key credential, fails, and
            // rejects the request with its own 403 before our proxy code (or
            // the real backend) ever sees it. This silently broke every
            // heartbeat call for users on the proxy (every other endpoint
            // works because none of them send Authorization). Send it under
            // a non-reserved header name instead; the proxy function
            // translates it back to a normal "Authorization: Bearer" header
            // on the outbound request to the real backend.
            if (!bearer.isNullOrBlank()) {
                if (isYandexFunctionProxy) {
                    setRequestProperty(PROXY_BEARER_HEADER, bearer)
                } else {
                    setRequestProperty("Authorization", "Bearer $bearer")
                }
            }
            doInput = true
            if (body != null) {
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                doOutput = true
            }
        }
        try {
            if (body != null) {
                val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val detail = parseDetail(text)
                throw CorsException(code, detail)
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } catch (e: CorsException) {
            throw e
        } catch (e: Exception) {
            // Network / protocol failure (unreachable host, timeout, malformed JSON).
            throw CorsException(0, e.message ?: "network error", e)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Builds the request URL for [path] (e.g. `/api/app/health`).
     *
     * For a normal server ([baseUrl] like `https://beta.cors-fox.cc`) the path
     * is appended directly. When [baseUrl] points at a Yandex Cloud Function
     * (`functions.yandexcloud.net`) the path is *not* appended — that domain
     * does not support path routing and treats `/<id>/api/...` as a different
     * function id (failing with `invalid functionID`). Instead the backend path
     * is passed as the `__path` query parameter and the function is invoked by
     * its clean URL; the proxy forwards `__path` to the real backend. The HTTP
     * method, headers and body are sent unchanged in both cases.
     */
    private fun buildRequestUrl(path: String): String {
        if (!isYandexFunctionProxy) return baseUrl + path
        val encoded = URLEncoder.encode(path, "UTF-8")
        // baseUrl has no query string by construction; append cleanly.
        return baseUrl + "?$PROXY_PATH_PARAM=$encoded"
    }

    private val isYandexFunctionProxy: Boolean by lazy {
        runCatching { URL(baseUrl).host.equals(YANDEX_FUNCTIONS_HOST, ignoreCase = true) }
            .getOrDefault(false)
    }

    private fun parseDetail(text: String): String =
        try { JSONObject(text).optString("detail").ifEmpty { text.trim() } }
        catch (_: Exception) { text.trim() }

    companion object {
        private const val TIMEOUT_MS = 15_000
        /** Yandex Cloud Functions direct-invocation host (no path routing). */
        private const val YANDEX_FUNCTIONS_HOST = "functions.yandexcloud.net"
        /** Query param carrying the real backend path when using a function proxy. */
        private const val PROXY_PATH_PARAM = "__path"
        /**
         * Non-reserved header carrying the app session bearer token when routed
         * through the Yandex Function proxy (see [request] for why a literal
         * "Authorization" header can't be used there). Must match the proxy
         * function's own constant.
         */
        const val PROXY_BEARER_HEADER = "X-Cors-Session-Token"
    }
}

/** Result of `/api/auth/telegram`. */
data class LoginOut(
    val token: String,
    val role: String,
    val username: String,
    val mustChangePassword: Boolean,
) {
    companion object {
        fun parse(o: JSONObject) = LoginOut(
            token = o.optString("token"),
            role = o.optString("role"),
            username = o.optString("username"),
            mustChangePassword = o.optBoolean("must_change_password"),
        )
    }
}
