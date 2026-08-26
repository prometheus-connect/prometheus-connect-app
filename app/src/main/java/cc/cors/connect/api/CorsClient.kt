package cc.cors.connect.api

import bypass.whitelist.BuildConfig
import bypass.whitelist.util.Prefs
import bypass.whitelist.util.SocksAuth
import org.json.JSONObject
import java.net.Authenticator
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal client for the Prometheus Connect instance-creation API
 * (the /api/app endpoints) plus the Telegram login endpoint (/api/auth/telegram).
 *
 * Every request carries the shared static secret in the X-App-Token header
 * (APP_TOKEN on the server). Methods are blocking — call them off the
 * main thread. Errors are surfaced as [CorsException].
 */
class CorsClient(
    private val baseUrl: String = Prefs.corsBaseUrl,
    private val appToken: String = BuildConfig.PC_APP_TOKEN,
    /**
     * Route requests through the relay's own local SOCKS5 instead of the
     * device's default network.
     *
     * The app excludes itself from its own VpnService — it has to, since the
     * relay runs in this process and would otherwise tunnel through itself —
     * which means our HTTP never rides the tunnel we just brought up. On a
     * whitelist tariff that leaves the API permanently unreachable, so anything
     * that must talk to the backend *after* connecting has to dial through the
     * relay explicitly. See [viaTunnel].
     */
    private val viaSocks: Boolean = false,
) {

    init {
        require(baseUrl.isNotBlank()) { "PC_BASE_URL not set" }
    }

    val isConfigured: Boolean get() = appToken.isNotBlank() && appToken != "REPLACE_WITH_APP_TOKEN"

    // ---- endpoints -------------------------------------------------------

    fun health(): Health = Health.parse(request("GET", "/api/app/health"))

    /**
     * Creates (or reuses) an instance.
     *
     * Uses a much longer read timeout than everything else: the server spawns a
     * headless browser to mint a real call link, which is anywhere from ~3 to
     * ~25 seconds, occasionally more under load. With the default 15s the app
     * gave up while the server was still working — the user saw "Cannot reach
     * the service" and the successfully-created instance was orphaned, holding
     * a slot on the fleet until it expired.
     */
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
        return CreateInstanceOut.parse(
            request("POST", "/api/app/instances", body, timeoutMs = CREATE_TIMEOUT_MS))
    }

    /**
     * Takes over a call picked up from the public pool.
     *
     * The pool exists because a whitelist-bound client cannot reach this API at
     * all until it has a tunnel; it bootstraps on a pre-minted call and calls
     * this once traffic flows. Until it does, the server has already dropped
     * that call to the anonymous window, so failing here is not dangerous —
     * it just means the session stays short.
     *
     * [telegramInitData] may be null: the server then answers `adopted=false`
     * with `reason="telegram_required"` instead of an error, which is the cue
     * to run the sign-in and come back.
     */
    fun adoptPoolInstance(
        backendId: String,
        outputLink: String,
        platform: String,
        telegramInitData: String? = null,
    ): AdoptOut {
        val body = JSONObject()
            .put("backend_id", backendId)
            .put("output_link", outputLink)
            .put("platform", platform)
        if (!telegramInitData.isNullOrEmpty()) body.put("telegram_init_data", telegramInitData)
        return AdoptOut.parse(request("POST", "/api/app/pool/adopt", body))
    }

    fun claim(instanceId: Int, claimToken: String, telegramInitData: String): ClaimOut {
        val body = JSONObject()
            .put("telegram_init_data", telegramInitData)
            .put("claim_token", claimToken)
        return ClaimOut.parse(request("POST", "/api/app/instances/$instanceId/claim", body))
    }

    fun getInstance(instanceId: Int): InstanceState =
        InstanceState.parse(request("GET", "/api/app/instances/$instanceId"))

    /**
     * Stops an instance and frees its slot on the fleet.
     *
     * Authorisation differs by lifecycle stage, and BOTH have to be offered:
     * an unclaimed instance is proved by its claim_token, a claimed one by the
     * session token. Sending only the claim_token — which is cleared the moment
     * the instance is claimed — meant every signed-in disconnect was rejected
     * 403 and the call sat there holding a slot until its lease ran out.
     */
    fun stop(instanceId: Int, claimToken: String? = null, sessionToken: String? = null) {
        val body = JSONObject()
        if (claimToken != null) body.put("claim_token", claimToken)
        request("DELETE", "/api/app/instances/$instanceId", body, bearer = sessionToken)
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

    /**
     * Mints a one-time code for the Telegram sign-in handoff.
     *
     * The Mini App posts the signed initData against this code and the app
     * collects it with [loginPoll]. This replaces the old App Link callback,
     * which could not work: Telegram renders the Mini App in a WebView, and
     * Android never consults App Links for in-WebView navigation, while
     * Telegram's in-app browser rejects intent: URLs outright.
     */
    fun loginStart(): LoginStart =
        LoginStart.parse(request("POST", "/api/app/login/start", JSONObject()))

    /** Returns the submitted initData once the Mini App has posted it. */
    fun loginPoll(code: String): LoginPoll =
        LoginPoll.parse(request("GET", "/api/app/login/poll?code=" + URLEncoder.encode(code, "UTF-8")))

    /** Standalone Telegram login — issues a session token for stored initData. */
    fun telegramLogin(initData: String): LoginOut {
        val body = JSONObject().put("initData", initData)
        return LoginOut.parse(request("POST", "/api/auth/telegram", body))
    }

    // ---- plumbing --------------------------------------------------------

    /**
     * Performs the request, transparently falling back to the direct backend
     * when the Cloud Function proxy itself is broken.
     *
     * The proxy has to be the default: on a strict whitelist tariff it is the
     * only host that resolves before a tunnel exists, so pointing the app
     * straight at our own domain would make first-connect impossible for the
     * users this exists for. But when the proxy is down for reasons that have
     * nothing to do with the network — its billing account lapsing, say — the
     * app should not sit there failing while a perfectly reachable backend is
     * one hop away. The switch lasts for the process only, so the proxy is
     * retried on next launch and the app self-heals once it is fixed.
     */
    private fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        bearer: String? = null,
        timeoutMs: Int = TIMEOUT_MS,
    ): JSONObject {
        val base = if (fellBackToDirect) FALLBACK_BASE_URL else baseUrl
        return try {
            execute(base, method, path, body, bearer, timeoutMs)
        } catch (e: CorsException) {
            if (!shouldFallBack(base, e)) throw e
            fellBackToDirect = true
            execute(FALLBACK_BASE_URL, method, path, body, bearer, timeoutMs)
        }
    }

    /** Only leave the proxy for platform-level failures, never for our own errors. */
    private fun shouldFallBack(base: String, e: CorsException): Boolean {
        if (FALLBACK_BASE_URL.isBlank()) return false
        if (!isProxy(base) || base == FALLBACK_BASE_URL) return false
        return e.code == 0 || e.fromPlatform
    }

    private fun execute(
        base: String,
        method: String,
        path: String,
        body: JSONObject? = null,
        bearer: String? = null,
        timeoutMs: Int = TIMEOUT_MS,
        useSocks: Boolean = viaSocks,
    ): JSONObject {
        val proxied = isProxy(base)
        val url = URL(buildRequestUrl(base, path))
        val opened = if (useSocks) url.openConnection(socksProxy()) else url.openConnection()
        val conn = (opened as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = timeoutMs
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
                if (proxied) {
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
                throw CorsException(code, parseDetail(text), fromPlatform = isPlatformError(text))
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } catch (e: CorsException) {
            throw e
        } catch (e: Exception) {
            // Network / protocol failure (unreachable host, timeout, malformed JSON).
            //
            // Retry through the relay before giving up. The app is excluded from
            // its own VpnService, so on a filtered network *every* endpoint is
            // unreachable the ordinary way once a tunnel exists — and fixing
            // that one endpoint at a time has already been got wrong twice, the
            // second time silently swallowing the Telegram sign-in that
            // adoption depends on. Doing it here covers the ones nobody
            // remembered. Costs a failed connect to a closed local port when no
            // tunnel is up, which is immediate.
            if (!useSocks) {
                runCatching {
                    return execute(base, method, path, body, bearer, timeoutMs, useSocks = true)
                }
            }
            throw CorsException(0, e.message ?: "network error", e)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Builds the request URL for [path] (e.g. `/api/app/health`).
     *
     * For a normal server ([baseUrl] like `https://auth.prometheus.info.gf`) the path
     * is appended directly. When [baseUrl] points at a Yandex Cloud Function
     * (`functions.yandexcloud.net`) the path is *not* appended — that domain
     * does not support path routing and treats `/<id>/api/...` as a different
     * function id (failing with `invalid functionID`). Instead the backend path
     * is passed as the `__path` query parameter and the function is invoked by
     * its clean URL; the proxy forwards `__path` to the real backend. The HTTP
     * method, headers and body are sent unchanged in both cases.
     */
    private fun buildRequestUrl(base: String, path: String): String {
        if (!isProxy(base)) return base + path
        val encoded = URLEncoder.encode(path, "UTF-8")
        // base has no query string by construction; append cleanly.
        return base + "?$PROXY_PATH_PARAM=$encoded"
    }

    private fun isProxy(base: String): Boolean =
        runCatching { URL(base).host.equals(YANDEX_FUNCTIONS_HOST, ignoreCase = true) }
            .getOrDefault(false)

    /** Yandex answers with its own error envelope; ours always has "detail". */
    private fun isPlatformError(text: String): Boolean =
        runCatching {
            val o = JSONObject(text)
            !o.has("detail") && (o.has("errorType") || o.has("errorCode"))
        }.getOrDefault(false)

    private fun parseDetail(text: String): String =
        try { JSONObject(text).optString("detail").ifEmpty { text.trim() } }
        catch (_: Exception) { text.trim() }

    companion object {
        /**
         * A client that reaches the backend through the tunnel the relay is
         * already carrying.
         *
         * Two deliberate differences from the default client. It dials through
         * the relay's local SOCKS5, because the app is outside its own VPN and
         * would otherwise never reach the API on a filtered network. And it
         * talks to the backend **directly** instead of through the Yandex Cloud
         * Function: that function exists only to be reachable *before* a tunnel
         * does, and once one is up it adds nothing but its own quirks — no path
         * routing, and a swallowed Authorization header.
         */
        fun viaTunnel(): CorsClient = CorsClient(
            baseUrl = BuildConfig.PC_FALLBACK_BASE_URL.trimEnd('/'),
            viaSocks = true,
        )

        /**
         * Credentials for the relay's SOCKS5 reach java.net through the default
         * Authenticator, which is process-global — so this one answers only for
         * loopback on the relay's own port, and stays silent for anything else
         * rather than offering the password to whatever host happens to ask.
         */
        private val socksAuthInstalled = AtomicBoolean(false)

        private fun socksProxy(): Proxy {
            if (socksAuthInstalled.compareAndSet(false, true)) {
                Authenticator.setDefault(object : Authenticator() {
                    override fun getPasswordAuthentication(): PasswordAuthentication? {
                        val site = requestingSite
                        if (site == null || !site.isLoopbackAddress) return null
                        if (requestingPort != Prefs.socksPort.toInt()) return null
                        return PasswordAuthentication(SocksAuth.user, SocksAuth.pass.toCharArray())
                    }
                })
            }
            return Proxy(Proxy.Type.SOCKS,
                InetSocketAddress("127.0.0.1", Prefs.socksPort.toInt()))
        }

        private const val TIMEOUT_MS = 15_000
        /** Connecting is fast even when the answer is slow; only reads drag. */
        private const val CONNECT_TIMEOUT_MS = 15_000
        /** See [createInstance] — the server legitimately takes tens of seconds. */
        private const val CREATE_TIMEOUT_MS = 90_000
        /** Yandex Cloud Functions direct-invocation host (no path routing). */
        private const val YANDEX_FUNCTIONS_HOST = "functions.yandexcloud.net"
        /** Query param carrying the real backend path when using a function proxy. */
        private const val PROXY_PATH_PARAM = "__path"
        /**
         * Non-reserved header carrying the app session bearer token when routed
         * through the Yandex Function proxy (see [request] for why a literal
         * "Authorization" header can't be used there). Must match the proxy
         * function's own SESSION_HEADER constant.
         */
        const val PROXY_BEARER_HEADER = "X-Prometheus-Session-Token"
        /** Backend reached directly, used when the proxy itself is broken. */
        private val FALLBACK_BASE_URL: String = BuildConfig.PC_FALLBACK_BASE_URL.trimEnd('/')
        /** Process-wide: once the proxy is known bad, stop paying for its timeout. */
        @Volatile private var fellBackToDirect: Boolean = false
    }
}

/** Result of `/api/app/login/start`. */
data class LoginStart(
    val code: String,
    val bot: String,
    val deeplink: String,
    val expiresIn: Int,
) {
    companion object {
        fun parse(o: JSONObject) = LoginStart(
            code = o.optString("code"),
            bot = o.optString("bot"),
            deeplink = o.optString("deeplink"),
            expiresIn = o.optInt("expires_in", 600),
        )
    }
}

/** Result of `/api/app/login/poll`. */
data class LoginPoll(val ready: Boolean, val initData: String, val username: String) {
    companion object {
        fun parse(o: JSONObject) = LoginPoll(
            ready = o.optString("status") == "ready",
            initData = o.optString("telegram_init_data"),
            username = o.optString("username"),
        )
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
