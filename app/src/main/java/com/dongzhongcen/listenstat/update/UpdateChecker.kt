package com.dongzhongcen.listenstat.update

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

data class ReleaseInfo(
    val version: String,
    val name: String,
    val notes: String,
    val pageUrl: String,
    val apkUrl: String?
)

sealed class UpdateResult {
    data class Available(val release: ReleaseInfo) : UpdateResult()
    data class UpToDate(val latest: String?) : UpdateResult()
    data class Failed(val message: String) : UpdateResult()
}

/**
 * Optional network call (independent of cloud sync): GET the latest (non-prerelease) GitHub Release.
 * Nothing is uploaded; no identifiers or statistics are sent.
 */
object UpdateChecker {
    const val LATEST_URL = "https://api.github.com/repos/dongzhongcen/ListenStat/releases/latest"
    const val RELEASES_PAGE = "https://github.com/dongzhongcen/ListenStat/releases"

    /** Blocking; call from Dispatchers.IO. */
    fun check(currentVersion: String): UpdateResult {
        val conn = (URL(LATEST_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "ListenStat")
        }
        return try {
            when (val code = conn.responseCode) {
                200 -> {
                    val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val release = parse(json) ?: return UpdateResult.UpToDate(null)
                    if (Semver.compare(release.version, currentVersion) > 0) {
                        UpdateResult.Available(release)
                    } else {
                        UpdateResult.UpToDate(release.version)
                    }
                }
                404 -> UpdateResult.UpToDate(null) // no stable release published yet
                else -> UpdateResult.Failed("HTTP $code")
            }
        } catch (e: Exception) {
            UpdateResult.Failed(e.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(json: JSONObject): ReleaseInfo? {
        if (json.optBoolean("prerelease") || json.optBoolean("draft")) return null
        val tag = json.optString("tag_name").ifBlank { return null }
        val assets = json.optJSONArray("assets")
        var apk: String? = null
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                    apk = a.optString("browser_download_url").ifBlank { null }
                    break
                }
            }
        }
        return ReleaseInfo(
            version = tag.removePrefix("v").removePrefix("V"),
            name = json.optString("name").ifBlank { tag },
            notes = json.optString("body"),
            pageUrl = json.optString("html_url").ifBlank { RELEASES_PAGE },
            apkUrl = apk
        )
    }
}

/** Minimal semantic-version comparison: 1.2.3 > 1.2.3-test; missing parts count as 0. */
object Semver {
    fun compare(a: String, b: String): Int {
        val (ca, pa) = split(a)
        val (cb, pb) = split(b)
        for (i in 0 until maxOf(ca.size, cb.size)) {
            val x = ca.getOrElse(i) { 0 }
            val y = cb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return when {
            pa == null && pb == null -> 0
            pa == null -> 1
            pb == null -> -1
            else -> pa.compareTo(pb)
        }
    }

    private fun split(v: String): Pair<List<Int>, String?> {
        val clean = v.trim().removePrefix("v").removePrefix("V").substringBefore('+')
        val core = clean.substringBefore('-')
        val pre = clean.substringAfter('-', "").ifBlank { null }
        return core.split('.').map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 } to pre
    }
}
