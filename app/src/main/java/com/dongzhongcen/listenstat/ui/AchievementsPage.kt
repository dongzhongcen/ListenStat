package com.dongzhongcen.listenstat.ui

import com.dongzhongcen.listenstat.R
import com.dongzhongcen.listenstat.achievements.AchievementView
import com.dongzhongcen.listenstat.achievements.Kind
import com.dongzhongcen.listenstat.databinding.ItemAchievementBinding
import com.dongzhongcen.listenstat.databinding.PageAchievementsBinding
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Locked / unlocked list. Data comes from [MainActivity.refreshAchievements]. */
class AchievementsPage(
    private val activity: MainActivity,
    private val b: PageAchievementsBinding
) {
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy.MM.dd")

    fun bind(items: List<AchievementView>) {
        val unlocked = items.count { it.unlockedAtMs != null }
        b.numUnlocked.text = unlocked.toString()
        b.numTotal.text = items.size.toString()
        b.listAchievements.removeAllViews()
        // unlocked first (newest first), then locked in definition order
        val sorted = items.filter { it.unlockedAtMs != null }.sortedByDescending { it.unlockedAtMs } +
            items.filter { it.unlockedAtMs == null }
        sorted.forEach { item ->
            val row = ItemAchievementBinding.inflate(activity.layoutInflater, b.listAchievements, false)
            val def = item.state.def
            row.name.text = def.name
            row.desc.text = def.desc
            val at = item.unlockedAtMs
            if (at != null) {
                row.check.setBackgroundResource(R.drawable.bg_circle_mint)
                row.check.setImageResource(R.drawable.ic_check)
                row.meta.text = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate().format(dateFmt)
                row.root.alpha = 1f
                row.root.contentDescription = "${def.name}，${activity.getString(R.string.ach_unlocked)}"
            } else {
                row.check.setBackgroundResource(R.drawable.bg_circle_outline)
                row.check.setImageDrawable(null)
                row.meta.text = when (def.kind) {
                    Kind.TOTAL_HOURS, Kind.STREAK_DAYS, Kind.TITLES, Kind.DAY_HOURS -> activity.getString(
                        R.string.ach_progress_fmt,
                        item.state.progress.coerceAtMost(def.target).toInt(), def.target.toInt()
                    )
                    else -> "soon…"
                }
                row.root.alpha = 0.78f
            }
            b.listAchievements.addView(row.root)
        }
        Ui.applyGlass(b.root)
    }
}
