package com.dongzhongcen.listenstat.backup

import android.content.Context
import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import androidx.room.withTransaction
import com.dongzhongcen.listenstat.BuildConfig
import com.dongzhongcen.listenstat.data.AppDatabase
import com.dongzhongcen.listenstat.data.PlaySession
import com.dongzhongcen.listenstat.data.UnlockedAchievement
import com.dongzhongcen.listenstat.settings.AppSettings
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ExportResult(val sessions: Int, val achievements: Int)
data class ImportResult(val added: Int, val skipped: Int, val achievements: Int)

/**
 * Backup as one JSON file written/read through the Storage Access Framework (no storage permission).
 *
 * Format:
 * { "app":"ListenStat", "format":1, "versionName":"0.2.0", "exportedAtMs":...,
 *   "sessions":[{packageName, appLabel, title, artist, dayKey, startedAtMs, endedAtMs, durationMs}],
 *   "achievements":[{id, unlockedAtMs}],
 *   "settings":{autoUpdateCheck, wallpaperBlur, wallpaperDim} }
 * Import merges: a session is skipped when (startedAtMs, packageName, title) already exists.
 */
class BackupManager(private val context: Context) {
    private val db = AppDatabase.get(context)
    private val settings = AppSettings(context)

    suspend fun export(uri: Uri): ExportResult {
        // "wt" truncates an existing file; some providers only accept "w".
        val out = runCatching { context.contentResolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: context.contentResolver.openOutputStream(uri, "w")
            ?: throw IOException("无法写入所选文件")
        var sessions = 0
        val achievements = db.achievementDao().all()
        JsonWriter(out.bufferedWriter()).use { w ->
            w.setIndent(" ")
            w.beginObject()
            w.name("app").value("ListenStat")
            w.name("format").value(FORMAT.toLong())
            w.name("versionName").value(BuildConfig.VERSION_NAME)
            w.name("exportedAtMs").value(System.currentTimeMillis())
            w.name("sessions").beginArray()
            var after = 0L
            while (true) {
                val page = db.playDao().page(after, PAGE)
                if (page.isEmpty()) break
                page.forEach { s ->
                    w.beginObject()
                    w.name("packageName").value(s.packageName)
                    w.name("appLabel").value(s.appLabel)
                    w.name("title").value(s.title)
                    w.name("artist").value(s.artist)
                    w.name("dayKey").value(s.dayKey)
                    w.name("startedAtMs").value(s.startedAtMs)
                    w.name("endedAtMs").value(s.endedAtMs)
                    w.name("durationMs").value(s.durationMs)
                    w.endObject()
                }
                sessions += page.size
                after = page.last().id
            }
            w.endArray()
            w.name("achievements").beginArray()
            achievements.forEach {
                w.beginObject().name("id").value(it.id).name("unlockedAtMs").value(it.unlockedAtMs).endObject()
            }
            w.endArray()
            w.name("settings").beginObject()
            w.name("autoUpdateCheck").value(settings.autoUpdateCheck)
            w.name("wallpaperBlur").value(settings.wallpaperBlur.toLong())
            w.name("wallpaperDim").value(settings.wallpaperDim.toLong())
            w.endObject()
            w.endObject()
        }
        return ExportResult(sessions, achievements.size)
    }

    suspend fun import(uri: Uri): ImportResult {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("无法读取所选文件")
        val sessions = ArrayList<PlaySession>()
        val achievements = ArrayList<UnlockedAchievement>()
        var sawApp = false
        var settingsAuto: Boolean? = null
        var settingsBlur: Int? = null
        var settingsDim: Int? = null
        JsonReader(input.bufferedReader()).use { r ->
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "app" -> sawApp = r.nextString() == "ListenStat"
                    "sessions" -> {
                        r.beginArray()
                        while (r.hasNext()) readSession(r)?.let { sessions += it }
                        r.endArray()
                    }
                    "achievements" -> {
                        r.beginArray()
                        while (r.hasNext()) {
                            var id: String? = null
                            var at = 0L
                            r.beginObject()
                            while (r.hasNext()) when (r.nextName()) {
                                "id" -> id = r.nextString()
                                "unlockedAtMs" -> at = r.nextLong()
                                else -> r.skipValue()
                            }
                            r.endObject()
                            if (!id.isNullOrBlank() && at > 0) achievements += UnlockedAchievement(id, at)
                        }
                        r.endArray()
                    }
                    "settings" -> {
                        r.beginObject()
                        while (r.hasNext()) when (r.nextName()) {
                            "autoUpdateCheck" -> settingsAuto = r.nextBoolean()
                            "wallpaperBlur" -> settingsBlur = r.nextInt()
                            "wallpaperDim" -> settingsDim = r.nextInt()
                            else -> r.skipValue()
                        }
                        r.endObject()
                    }
                    else -> r.skipValue()
                }
            }
            r.endObject()
        }
        if (!sawApp) throw IOException("不是 ListenStat 备份文件")

        val zoneFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val existing = HashSet<String>()
        db.playDao().allKeys().forEach { existing += key(it.startedAtMs, it.packageName, it.title) }
        val toInsert = ArrayList<PlaySession>()
        var skipped = 0
        for (s in sessions) {
            val k = key(s.startedAtMs, s.packageName, s.title)
            if (!existing.add(k)) { skipped++; continue } // also de-dups within the file
            val dayKey = s.dayKey.ifBlank {
                Instant.ofEpochMilli(s.startedAtMs).atZone(ZoneId.systemDefault()).toLocalDate().format(zoneFmt)
            }
            toInsert += s.copy(id = 0, dayKey = dayKey)
        }
        db.withTransaction {
            toInsert.chunked(PAGE).forEach { db.playDao().insertAll(it) }
            val dao = db.achievementDao()
            dao.insertAll(achievements)
            achievements.forEach { dao.keepEarliest(it.id, it.unlockedAtMs) }
        }
        settingsAuto?.let { settings.autoUpdateCheck = it }
        settingsBlur?.let { settings.wallpaperBlur = it }
        settingsDim?.let { settings.wallpaperDim = it }
        return ImportResult(toInsert.size, skipped, achievements.size)
    }

    private fun readSession(r: JsonReader): PlaySession? {
        var pkg = ""
        var label = ""
        var title = ""
        var artist = ""
        var day = ""
        var start = -1L
        var end = -1L
        r.beginObject()
        while (r.hasNext()) {
            val name = r.nextName()
            if (r.peek() == JsonToken.NULL) { r.nextNull(); continue }
            when (name) {
                "packageName" -> pkg = r.nextString()
                "appLabel" -> label = r.nextString()
                "title" -> title = r.nextString()
                "artist" -> artist = r.nextString()
                "dayKey" -> day = r.nextString()
                "startedAtMs" -> start = r.nextLong()
                "endedAtMs" -> end = r.nextLong()
                else -> r.skipValue() // durationMs is recomputed
            }
        }
        r.endObject()
        if (pkg.isBlank() || start <= 0 || end < start) return null
        return PlaySession(
            packageName = pkg, appLabel = label.ifBlank { pkg }, title = title, artist = artist,
            dayKey = day, startedAtMs = start, endedAtMs = end, durationMs = end - start
        )
    }

    companion object {
        const val FORMAT = 1
        private const val PAGE = 500
        fun key(start: Long, pkg: String, title: String) = "$start\u0001$pkg\u0001$title"
    }
}
