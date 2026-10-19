package com.dongzhongcen.listenstat.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.format.DateUtils
import android.view.inputmethod.EditorInfo
import androidx.core.view.isVisible
import com.dongzhongcen.listenstat.BuildConfig
import com.dongzhongcen.listenstat.R
import com.dongzhongcen.listenstat.databinding.PageSettingsBinding
import com.dongzhongcen.listenstat.settings.AppSettings
import com.dongzhongcen.listenstat.sync.SyncApi
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class SettingsPage(
    private val activity: MainActivity,
    private val b: PageSettingsBinding,
    private val settings: AppSettings
) {
    private var syncUiReady = false
    private var lastPairCode: String? = null

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

        // Cloud sync (default off)
        b.switchCloudSync.isChecked = settings.cloudSyncEnabled
        b.editDisplayName.setText(settings.displayName)
        b.editSyncBase.setText(settings.syncBaseUrl)
        b.switchCloudSync.setOnCheckedChangeListener { _, on ->
            if (!syncUiReady) return@setOnCheckedChangeListener
            if (on) {
                persistSyncFields()
                settings.cloudSyncEnabled = true
                updatePairEnabled(true)
                activity.onCloudSyncEnabled()
            } else {
                settings.cloudSyncEnabled = false
                b.txtSyncStatus.setText(R.string.sync_status_off)
                updatePairEnabled(false)
            }
        }
        b.editDisplayName.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) commitDisplayName()
        }
        b.editDisplayName.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commitDisplayName()
                true
            } else false
        }
        b.editSyncBase.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) persistSyncFields()
        }
        syncUiReady = true
        b.btnGeneratePairCode.setOnClickListener { activity.onPairCreate() }
        b.btnClaimPair.setOnClickListener {
            activity.onPairClaim(b.editClaimCode.text?.toString().orEmpty())
        }
        b.btnCopyPairCode.setOnClickListener { copyPairCode() }
        updatePairEnabled(settings.cloudSyncEnabled)

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

        // Sync UI — avoid feedback loops while programmatically setting the switch
        syncUiReady = false
        b.switchCloudSync.isChecked = settings.cloudSyncEnabled
        if (b.editDisplayName.text.isNullOrEmpty() || !b.editDisplayName.hasFocus()) {
            b.editDisplayName.setText(settings.displayName)
        }
        if (!b.editSyncBase.hasFocus()) {
            b.editSyncBase.setText(settings.syncBaseUrl)
        }
        b.txtSyncStatus.setText(
            if (settings.cloudSyncEnabled) R.string.sync_status_on else R.string.sync_status_off
        )
        b.txtPairState.setText(
            if (settings.paired) R.string.pair_state_yes else R.string.pair_state_no
        )
        updatePairEnabled(settings.cloudSyncEnabled)
        syncUiReady = true

        Ui.applyGlass(b.root)
    }

    fun setSyncStatus(text: String) {
        b.txtSyncStatus.text = text
    }

    fun setPairBusy(message: String) {
        b.txtPairMsg.text = message
    }

    fun showPairCode(code: String, expiresAtIso: String) {
        lastPairCode = code
        b.txtPairCode.text = activity.getString(R.string.pair_code_label_fmt, code)
        b.txtPairCode.isVisible = true
        b.btnCopyPairCode.isVisible = true
        val formatted = formatExpires(expiresAtIso)
        if (formatted != null) {
            b.txtPairExpires.text = activity.getString(R.string.pair_expires_fmt, formatted)
            b.txtPairExpires.isVisible = true
        } else {
            b.txtPairExpires.isVisible = false
        }
        b.txtPairMsg.text = activity.getString(R.string.pair_help)
    }

    fun showPairMessage(text: String) {
        b.txtPairMsg.text = text
    }

    fun onPairClaimSuccess() {
        b.editClaimCode.setText("")
        lastPairCode = null
        b.txtPairCode.isVisible = false
        b.txtPairExpires.isVisible = false
        b.btnCopyPairCode.isVisible = false
        b.txtPairState.setText(R.string.pair_state_yes)
    }

    private fun updatePairEnabled(enabled: Boolean) {
        b.btnGeneratePairCode.isEnabled = enabled
        b.btnClaimPair.isEnabled = enabled
        b.editClaimCode.isEnabled = enabled
        b.btnCopyPairCode.isEnabled = enabled && !lastPairCode.isNullOrBlank()
        if (!enabled && b.txtPairMsg.text.isNullOrBlank()) {
            b.txtPairMsg.setText(R.string.pair_need_sync)
        } else if (enabled && b.txtPairMsg.text?.toString() == activity.getString(R.string.pair_need_sync)) {
            b.txtPairMsg.text = ""
        }
    }

    private fun copyPairCode() {
        val code = lastPairCode ?: return
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("pair_code", code))
        b.txtPairMsg.setText(R.string.pair_code_copied)
    }

    private fun formatExpires(iso: String): String? {
        if (iso.isBlank()) return null
        return runCatching {
            val instant = Instant.parse(iso)
            EXPIRY_FMT.format(instant.atZone(SHANGHAI))
        }.getOrNull()
    }

    private fun persistSyncFields() {
        val name = b.editDisplayName.text?.toString().orEmpty()
        settings.displayName = name
        val base = b.editSyncBase.text?.toString().orEmpty().ifBlank { SyncApi.DEFAULT_BASE_URL }
        settings.syncBaseUrl = base
    }

    private fun commitDisplayName() {
        val previous = settings.displayName
        persistSyncFields()
        if (settings.cloudSyncEnabled && settings.displayName != previous && settings.displayName.isNotBlank()) {
            activity.onDisplayNameChanged(settings.displayName)
        }
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
        private val SHANGHAI: ZoneId = ZoneId.of("Asia/Shanghai")
        private val EXPIRY_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(SHANGHAI)
    }
}
