package com.dongzhongcen.listenstat.sync

import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID

data class SyncDayRecord(
    val recordId: String,
    val packageName: String,
    val title: String,
    val day: String,
    val durationMs: Long,
    val updatedAt: String? = null
)

data class RegisterResult(
    val userId: String,
    val deviceToken: String,
    val displayName: String
)

data class MeResult(
    val userId: String,
    val displayName: String,
    val deviceId: String,
    val paired: Boolean = false,
    val accountUserId: String? = null
)

data class PairCreateResult(
    val code: String,
    val expiresAt: String
)

data class PairClaimResult(
    val userId: String,
    val displayName: String,
    val mergedRecords: Int
)

data class PushResult(val upserted: Int, val serverTime: String)
data class PullResult(val records: List<SyncDayRecord>, val serverTime: String)

/**
 * Minimal HttpURLConnection client for the ListenStat Sync API (/v1).
 * Call from Dispatchers.IO. Never logs the bearer token.
 */
class SyncApi(private val baseUrlProvider: () -> String) {

    fun health(): Boolean {
        val conn = open("GET", "/v1/health", token = null)
        return try {
            conn.responseCode == 200 &&
                JSONObject(readBody(conn)).optBoolean("ok", false)
        } finally {
            conn.disconnect()
        }
    }

    fun register(deviceId: String, displayName: String?): RegisterResult {
        val body = JSONObject().put("device_id", deviceId)
        if (!displayName.isNullOrBlank()) body.put("display_name", displayName.trim())
        val conn = open("POST", "/v1/register", token = null, jsonBody = body)
        return try {
            val code = conn.responseCode
            val json = JSONObject(readBody(conn))
            if (code !in 200..299) throw ApiException(code, json.optString("detail", "register failed"))
            RegisterResult(
                userId = json.getString("user_id"),
                deviceToken = json.getString("device_token"),
                displayName = json.optString("display_name", "Listener")
            )
        } finally {
            conn.disconnect()
        }
    }

    fun getMe(token: String): MeResult {
        val conn = open("GET", "/v1/me", token = token)
        return try {
            val code = conn.responseCode
            val json = JSONObject(readBody(conn))
            if (code !in 200..299) throw ApiException(code, json.optString("detail", "me failed"))
            MeResult(
                userId = json.getString("user_id"),
                displayName = json.optString("display_name", ""),
                deviceId = json.optString("device_id", ""),
                paired = json.optBoolean("paired", false),
                accountUserId = json.optString("account_user_id").ifBlank { null }
            )
        } finally {
            conn.disconnect()
        }
    }

    fun patchMe(token: String, displayName: String): MeResult {
        val body = JSONObject().put("display_name", displayName.trim())
        val conn = open("PATCH", "/v1/me", token = token, jsonBody = body)
        return try {
            val code = conn.responseCode
            val json = JSONObject(readBody(conn))
            if (code !in 200..299) throw ApiException(code, json.optString("detail", "patch me failed"))
            MeResult(
                userId = json.getString("user_id"),
                displayName = json.optString("display_name", ""),
                deviceId = json.optString("device_id", ""),
                paired = json.optBoolean("paired", false),
                accountUserId = json.optString("account_user_id").ifBlank { null }
            )
        } finally {
            conn.disconnect()
        }
    }

    fun push(token: String, records: List<SyncDayRecord>): PushResult {
        val arr = JSONArray()
        records.forEach { r ->
            arr.put(
                JSONObject()
                    .put("record_id", r.recordId)
                    .put("package", r.packageName)
                    .put("title", r.title)
                    .put("day", r.day)
                    .put("duration_ms", r.durationMs)
            )
        }
        val body = JSONObject().put("records", arr)
        val conn = open("POST", "/v1/sync/push", token = token, jsonBody = body)
        return try {
            val code = conn.responseCode
            val json = JSONObject(readBody(conn))
            if (code !in 200..299) throw ApiException(code, json.optString("detail", "push failed"))
            PushResult(
                upserted = json.optInt("upserted", 0),
                serverTime = json.optString("server_time", "")
            )
        } finally {
            conn.disconnect()
        }
    }

    fun pull(token: String, since: String?): PullResult {
        val path = if (since.isNullOrBlank()) "/v1/sync/pull"
        else "/v1/sync/pull?since=${java.net.URLEncoder.encode(since, "UTF-8")}"
        val conn = open("GET", path, token = token)
        return try {
            val code = conn.responseCode
            val json = JSONObject(readBody(conn))
            if (code !in 200..299) throw ApiException(code, json.optString("detail", "pull failed"))
            val arr = json.optJSONArray("records") ?: JSONArray()
            val list = ArrayList<SyncDayRecord>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list += SyncDayRecord(
                    recordId = o.getString("record_id"),
                    packageName = o.getString("package"),
                    title = o.optString("title", ""),
                    day = o.getString("day"),
                    durationMs = o.getLong("duration_ms"),
                    updatedAt = o.optString("updated_at").ifBlank { null }
                )
            }
            PullResult(records = list, serverTime = json.optString("server_time", ""))
        } finally {
            conn.disconnect()
        }
    }


    fun pairCreate(token: String): PairCreateResult {
        val conn = open("POST", "/v1/pair/create", token = token, jsonBody = JSONObject())
        return try {
            val code = conn.responseCode
            val json = JSONObject(readBody(conn))
            if (code !in 200..299) throw ApiException(code, json.optString("detail", "pair create failed"))
            PairCreateResult(
                code = json.getString("code"),
                expiresAt = json.optString("expires_at", "")
            )
        } finally {
            conn.disconnect()
        }
    }

    fun pairClaim(token: String, pairCode: String): PairClaimResult {
        val body = JSONObject().put("code", pairCode.trim())
        val conn = open("POST", "/v1/pair/claim", token = token, jsonBody = body)
        return try {
            val code = conn.responseCode
            val json = JSONObject(readBody(conn))
            if (code !in 200..299) throw ApiException(code, json.optString("detail", "pair claim failed"))
            PairClaimResult(
                userId = json.getString("user_id"),
                displayName = json.optString("display_name", ""),
                mergedRecords = json.optInt("merged_records", 0)
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun open(
        method: String,
        path: String,
        token: String?,
        jsonBody: JSONObject? = null
    ): HttpURLConnection {
        val base = baseUrlProvider().trim().trimEnd('/')
        if (base.isBlank()) throw ApiException(0, "empty base URL")
        val url = URL(base + path)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "ListenStat")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
            if (jsonBody != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                OutputStreamWriter(outputStream, StandardCharsets.UTF_8).use { it.write(jsonBody.toString()) }
            }
        }
        return conn
    }

    private fun readBody(conn: HttpURLConnection): String {
        val stream = try {
            if (conn.responseCode >= 400) conn.errorStream ?: conn.inputStream
            else conn.inputStream
        } catch (_: Exception) {
            conn.errorStream
        } ?: return "{}"
        return stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }

    class ApiException(val code: Int, message: String) : Exception("HTTP $code: $message")

    companion object {
        /** Deterministic record id for (package, title, day) day-aggregates. */
        fun recordId(packageName: String, title: String, day: String): String =
            UUID.nameUUIDFromBytes("$packageName\u0000$title\u0000$day".toByteArray(StandardCharsets.UTF_8))
                .toString()

        const val DEFAULT_BASE_URL = "https://jiketianxiawang.top"
        const val PUSH_BATCH = 500
    }
}
