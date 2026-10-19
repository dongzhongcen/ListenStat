package com.dongzhongcen.listenstat.alarm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persists local alarms as JSON in SharedPreferences (no Room table for v0.3). */
class AlarmStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun list(): List<UserAlarm> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        UserAlarm(
                            id = o.getLong("id"),
                            hour = o.getInt("hour"),
                            minute = o.getInt("minute"),
                            label = o.optString("label", ""),
                            enabled = o.optBoolean("enabled", true),
                            weekdays = o.optInt("weekdays", 0)
                        )
                    )
                }
            }.sortedWith(compareBy({ it.hour }, { it.minute }, { it.id }))
        }.getOrDefault(emptyList())
    }

    fun get(id: Long): UserAlarm? = list().find { it.id == id }

    fun upsert(alarm: UserAlarm): UserAlarm {
        val items = list().toMutableList()
        val idx = items.indexOfFirst { it.id == alarm.id }
        if (idx >= 0) items[idx] = alarm else items.add(alarm)
        save(items)
        return alarm
    }

    fun delete(id: Long) {
        save(list().filterNot { it.id == id })
    }

    fun nextId(): Long = (list().maxOfOrNull { it.id } ?: 0L) + 1L

    private fun save(items: List<UserAlarm>) {
        val arr = JSONArray()
        items.forEach { a ->
            arr.put(
                JSONObject()
                    .put("id", a.id)
                    .put("hour", a.hour)
                    .put("minute", a.minute)
                    .put("label", a.label)
                    .put("enabled", a.enabled)
                    .put("weekdays", a.weekdays)
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val NAME = "listenstat_alarms"
        private const val KEY = "alarms_json"
    }
}
