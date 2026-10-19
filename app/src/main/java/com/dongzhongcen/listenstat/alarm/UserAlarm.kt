package com.dongzhongcen.listenstat.alarm

/**
 * Local listening reminder. Not synced to system calendar accounts.
 *
 * [weekdays] bit0=周一 … bit6=周日. Zero means every day.
 */
data class UserAlarm(
    val id: Long,
    val hour: Int,
    val minute: Int,
    val label: String = "",
    val enabled: Boolean = true,
    val weekdays: Int = 0
) {
    fun matchesWeekday(javaDayOfWeek: Int): Boolean {
        val mask = if (weekdays == 0) 0x7F else weekdays
        // java.time DayOfWeek: MONDAY=1 … SUNDAY=7 → bit 0..6
        val bit = javaDayOfWeek - 1
        return (mask and (1 shl bit)) != 0
    }

    fun timeLabel(): String = "%02d:%02d".format(hour, minute)
}
