package com.dongzhongcen.listenstat.ui

import android.content.Intent
import android.provider.Settings
import android.text.format.DateUtils
import androidx.core.view.isVisible
import com.dongzhongcen.listenstat.BuildConfig
import com.dongzhongcen.listenstat.R
import com.dongzhongcen.listenstat.databinding.PageSettingsBinding
import com.dongzhongcen.listenstat.settings.AppSettings
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider

class SettingsPage(
    private val activity: MainActivity,
    private val b: PageSettingsBinding,
    private val settings: AppSettings
) {
    fun init() {
        b.txtVersion.text = activity.getString(R.string.about_version_fmt, BuildConfig.VERSION_NAME)

        b.btnPickWallpaper.setOnClickListener { activity.pickWallpaper() }
        b.btnRemoveWallpaper.setOnClickListener { activity.removeWallpaper() }
        b.sliderBlur.value = settings.wallpaperBlur.toFloat()
        b.sliderDim.value = (settings.wallpaperDim / 5 * 5).toFloat()
        b.sliderDim.addOnChangeListener { _, v, fromUser ->
            if (fromUser) { settings.wallpaperDim = v.toInt(); activity.applyVeil() }
        }
        b.sliderBlur.addOnChangeListener { _, v, fromUser ->
            if (fromUser) {
                settings.wallpaperBlur = v.toInt()
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) activity.applyWallpaper()
            }
        }
        b.sliderBlur.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) = activity.applyWallpaper()
        })

        b.switchAutoUpdate.isChecked = settings.autoUpdateCheck
        b.switchAutoUpdate.setOnCheckedChangeListener { _, on -> settings.autoUpdateCheck = on }
        b.btnCheckUpdate.setOnClickListener { activity.checkUpdate(manual = true) }

        b.btnBackup.setOnClickListener {
            Sheets.backup(activity, onExport = { activity.exportBackup() }, onImport = { activity.importBackup() })
        }
        b.btnAccess.setOnClickListener {
            activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        b.btnGithub.setOnClickListener { Sheets.openUrl(activity, REPO_URL) }
        b.btnLicenses.setOnClickListener { showLicenses() }
        refresh()
    }

    fun refresh() {
        b.groupWallpaperAdjust.isVisible = activity.hasWallpaper()
        b.btnRemoveWallpaper.isVisible = activity.hasWallpaper()
        b.switchAutoUpdate.isChecked = settings.autoUpdateCheck
        b.sliderBlur.value = settings.wallpaperBlur.toFloat()
        b.sliderDim.value = (settings.wallpaperDim / 5 * 5).toFloat()
        val last = settings.lastUpdateCheckMs
        b.txtLastCheck.text = if (last <= 0L) activity.getString(R.string.update_never)
        else activity.getString(
            R.string.update_last_fmt,
            DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        )
        val on = activity.isNotificationAccessEnabled()
        b.txtAccess.setText(if (on) R.string.access_on else R.string.access_off)
        Ui.applyGlass(b.root)
    }

    private fun showLicenses() {
        val text = listOf("Poppins-OFL.txt", "ShadowsIntoLight-OFL.txt").joinToString("\n\n————————\n\n") { f ->
            val body = runCatching {
                activity.assets.open("licenses/$f").bufferedReader().use { it.readText() }
            }.getOrDefault("")
            "${f.substringBefore("-OFL")}\n\n$body"
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.about_licenses)
            .setMessage(text)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    companion object {
        const val REPO_URL = "https://github.com/dongzhongcen/ListenStat"
    }
}
