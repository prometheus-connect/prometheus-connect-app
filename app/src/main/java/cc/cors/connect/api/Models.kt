package cc.cors.connect.api

import org.json.JSONObject

/** Mirrors the OpenAPI schema in android_api.yaml. */

/** Returns the string at [key], or null if absent/explicitly null/blank. */
private fun JSONObject.optNullableString(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null

data class Health(
    val ok: Boolean,
    val telegramEnabled: Boolean,
    val serviceAvailable: Boolean,
) {
    companion object {
        fun parse(o: JSONObject) = Health(
            ok = o.optBoolean("ok"),
            telegramEnabled = o.optBoolean("telegram_enabled"),
            serviceAvailable = o.optBoolean("service_available"),
        )
    }
}

data class CreateInstanceOut(
    val instanceId: Int,
    val claimToken: String?,
    val status: String,
    val outputLink: String?,
    val serviceName: String?,
    val tempExpiresAt: String?,
    val tempTtlSeconds: Int?,
    /** True when the server returned an existing live instance instead of creating a new one. */
    val reused: Boolean = false,
    /** Session token present when the server pre-claimed (or reused a claimed) instance. */
    val token: String? = null,
    val username: String? = null,
) {
    companion object {
        fun parse(o: JSONObject) = CreateInstanceOut(
            instanceId = o.getInt("instance_id"),
            // claim_token is null when the server created+claimed (or reused) on our behalf.
            claimToken = o.optNullableString("claim_token"),
            status = o.getString("status"),
            outputLink = o.optNullableString("output_link"),
            serviceName = o.optNullableString("service_name"),
            tempExpiresAt = o.optNullableString("temp_expires_at"),
            tempTtlSeconds = if (o.has("temp_ttl_seconds") && !o.isNull("temp_ttl_seconds")) o.getInt("temp_ttl_seconds") else null,
            reused = o.optBoolean("reused", false),
            token = o.optNullableString("token"),
            username = o.optNullableString("username"),
        )
    }
}

data class ClaimOut(
    val instanceId: Int,
    val userId: Int,
    val token: String,
    val role: String,
    val username: String,
    val status: String?,
    val outputLink: String?,
    val expiresAt: String?,
) {
    companion object {
        fun parse(o: JSONObject) = ClaimOut(
            instanceId = o.getInt("instance_id"),
            userId = o.getInt("user_id"),
            token = o.getString("token"),
            role = o.optString("role"),
            username = o.optString("username"),
            status = o.optNullableString("status"),
            outputLink = o.optNullableString("output_link"),
            expiresAt = o.optNullableString("expires_at"),
        )
    }
}

/** Result of `/api/app/instances/{id}/heartbeat`. */
data class HeartbeatOut(
    val ok: Boolean,
    val instanceId: Int,
    val expiresAt: String?,
) {
    companion object {
        fun parse(o: JSONObject) = HeartbeatOut(
            ok = o.optBoolean("ok"),
            instanceId = o.getInt("instance_id"),
            expiresAt = o.optNullableString("expires_at"),
        )
    }
}

data class InstanceState(
    val id: Int,
    val userId: Int?,
    val status: String,
    val outputLink: String?,
    val error: String?,
    val timeoutAt: String?,
    val isQuick: Boolean?,
    val serviceName: String?,
) {
    val isTerminal: Boolean
        get() = status in setOf("stopped", "exited", "crashed", "timeout")
    val isLive: Boolean
        get() = status in setOf("pending", "running", "stopping")

    companion object {
        fun parse(o: JSONObject) = InstanceState(
            id = o.getInt("id"),
            userId = if (o.has("user_id") && !o.isNull("user_id")) o.getInt("user_id") else null,
            status = o.optString("status"),
            outputLink = o.optNullableString("output_link"),
            error = o.optNullableString("error"),
            timeoutAt = o.optNullableString("timeout_at"),
            isQuick = if (o.has("is_quick") && !o.isNull("is_quick")) o.getBoolean("is_quick") else null,
            serviceName = o.optNullableString("service_name"),
        )
    }
}

/** HTTP-level failure from the Prometheus Connect service. */
class CorsException(
    val code: Int,
    val detail: String,
    cause: Throwable? = null,
    /**
     * True when the error came from the Cloud Function platform rather than
     * from our backend — the function being unreachable, or its billing
     * account lapsing. Our backend always answers `{"detail": ...}`; Yandex
     * answers `{"errorCode": ..., "errorType": ...}`. Only these justify
     * falling back to the direct backend URL.
     */
    val fromPlatform: Boolean = false,
) : RuntimeException("Prometheus Connect API error $code: $detail", cause)

/**
 * Result of `/api/app/pool/adopt`.
 *
 * [adopted] false is a normal outcome, not a failure: it means the server needs
 * a Telegram identity before it can attach the call to anyone. [reason] says
 * which; everything else is only present once adoption succeeded.
 */
data class AdoptOut(
    val adopted: Boolean,
    val reason: String,
    val instanceId: Int,
    val token: String?,
    val username: String,
    val tempTtlSeconds: Int,
) {
    companion object {
        fun parse(o: JSONObject) = AdoptOut(
            adopted = o.optBoolean("adopted"),
            reason = o.optString("reason"),
            instanceId = o.optInt("instance_id"),
            token = o.optString("token").ifEmpty { null },
            username = o.optString("username"),
            tempTtlSeconds = o.optInt("temp_ttl_seconds"),
        )
    }
}
