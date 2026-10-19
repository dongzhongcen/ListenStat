package com.dongzhongcen.listenstat.ui

import android.content.ComponentName
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.dongzhongcen.listenstat.BuildConfig
import com.dongzhongcen.listenstat.R
import com.dongzhongcen.listenstat.achievements.AchievementRepository
import com.dongzhongcen.listenstat.backup.BackupManager
import com.dongzhongcen.listenstat.databinding.ActivityMainBinding
import com.dongzhongcen.listenstat.service.MediaTrackService
import com.dongzhongcen.listenstat.settings.AppSettings
import com.dongzhongcen.listenstat.sync.SyncManager
import com.dongzhongcen.listenstat.update.UpdateChecker
import com.dongzhongcen.listenstat.update.UpdateResult
import com.google.android.material.snackbar.Snackbar
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private enum class Page { STATS, ACHIEVEMENTS, SETTINGS }

    private lateinit var binding: ActivityMainBinding
    private lateinit var statsPage: StatsPage
    private lateinit var achievementsPage: AchievementsPage
    private lateinit var settingsPage: SettingsPage
    private lateinit var appDetailPage: AppDetailPage
    private val settings by lazy { AppSettings(this) }
    private val syncManager by lazy { SyncManager(this) }
    private val wallpaperStore by lazy { WallpaperStore(this) }
    private var wallpaperBitmap: Bitmap? = null
    private var page = Page.STATS
    private var updateInFlight = false

    // System Photo Picker: no READ_MEDIA_* / storage permission.
    private val photoPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) setWallpaper(uri)
    }
    // Storage Access Framework: the user chooses the file; no storage permission.
    private val createBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runBackup(export = true, uri = uri)
    }
    private val openBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runBackup(export = false, uri = uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        statsPage = StatsPage(this, binding.pageStats) { showPage(Page.SETTINGS) }
        achievementsPage = AchievementsPage(this, binding.pageAchievements)
        settingsPage = SettingsPage(this, binding.pageSettings, settings)
        statsPage.init()
        settingsPage.init()
        appDetailPage = AppDetailPage(this, binding.pageAppDetail) {
            binding.navPill.isVisible = true
            restoreMainPageVisibility()
        }
        appDetailPage.init()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (appDetailPage.isOpen) {
                    appDetailPage.close()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        binding.navStats.setOnClickListener { showPage(Page.STATS) }
        binding.navAchievements.setOnClickListener { showPage(Page.ACHIEVEMENTS) }
        binding.navSettings.setOnClickListener { showPage(Page.SETTINGS) }
        page = savedInstanceState?.getString(KEY_PAGE)?.let { runCatching { Page.valueOf(it) }.getOrNull() }
            ?: Page.STATS
        showPage(page, animate = false)
        applyInsets()
        applyWallpaper()

        if (savedInstanceState == null) {
            maybeAutoCheckUpdate()
            maybeDailyCloudPush()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PAGE, page.name)
    }

    override fun onStart() {
        super.onStart()
        statsPage.onStart()
    }

    override fun onResume() {
        super.onResume()
        statsPage.setPermissionMissing(!isNotificationAccessEnabled())
        settingsPage.refresh()
        refreshAchievements(announce = true)
    }

    override fun onStop() {
        statsPage.onStop()
        maybePushOnLeave()
        super.onStop()
    }

    // ---- navigation ----

    private fun showPage(p: Page, animate: Boolean = true) {
        if (::appDetailPage.isInitialized && appDetailPage.isOpen) {
            // Close without restoring the previous tab — we are switching tabs.
            appDetailPage.close()
        }
        // Simple sync trigger: leaving stats/settings while sync is on.
        if (page != p && (page == Page.STATS || page == Page.SETTINGS)) {
            maybePushOnLeave()
        }
        page = p
        val views = mapOf(
            Page.STATS to binding.pageStats.root,
            Page.ACHIEVEMENTS to binding.pageAchievements.root,
            Page.SETTINGS to binding.pageSettings.root
        )
        views.forEach { (k, v) ->
            if (k == p) {
                if (!v.isVisible) {
                    v.alpha = if (animate) 0f else 1f
                    v.isVisible = true
                    if (animate) v.animate().alpha(1f).setDuration(180L).start()
                }
            } else {
                v.animate().cancel()
                v.isVisible = false
            }
        }
        listOf(
            binding.navStats to Page.STATS,
            binding.navAchievements to Page.ACHIEVEMENTS,
            binding.navSettings to Page.SETTINGS
        ).forEach { (btn, which) ->
            val on = which == p
            btn.setBackgroundResource(if (on) R.drawable.bg_circle_mint else R.drawable.bg_circle_ink)
            btn.imageTintList = ContextCompat.getColorStateList(this, if (on) R.color.ls_ink else R.color.ls_white)
            btn.isSelected = on
        }
        if (p == Page.SETTINGS) settingsPage.refresh()
    }

    private fun applyInsets() {
        val pages = listOf(
            binding.pageStats.content, binding.pageAchievements.content,
            binding.pageSettings.content, binding.pageAppDetail.content
        )
        val base = pages.map { Triple(it.paddingTop, it.paddingBottom, it.paddingLeft) }
        val navBottom = (binding.navPill.layoutParams as android.view.ViewGroup.MarginLayoutParams).bottomMargin
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            pages.forEachIndexed { i, v ->
                v.updatePadding(
                    left = base[i].third + bars.left,
                    top = base[i].first + bars.top,
                    right = base[i].third + bars.right,
                    bottom = base[i].second + bars.bottom
                )
            }
            binding.navPill.updateLayoutParams<android.view.ViewGroup.MarginLayoutParams> {
                bottomMargin = navBottom + bars.bottom
            }
            insets
        }
    }

    // ---- achievements (computed on open/resume only) ----

    fun refreshAchievements(announce: Boolean) {
        lifecycleScope.launch {
            val (all, fresh) = withContext(Dispatchers.IO) { AchievementRepository(this@MainActivity).refresh() }
            achievementsPage.bind(all)
            if (announce && fresh.isNotEmpty()) {
                val names = fresh.joinToString("、") { it.name }
                val text = if (fresh.size == 1) getString(R.string.ach_snack_one, names)
                else getString(R.string.ach_snack_many, fresh.size, names)
                snack(text, actionText = getString(R.string.ach_view)) { showPage(Page.ACHIEVEMENTS) }
            }
        }
    }

    private fun snack(text: String, actionText: String? = null, action: (() -> Unit)? = null) {
        Snackbar.make(binding.root, text, Snackbar.LENGTH_LONG).apply {
            anchorView = binding.navPill
            if (actionText != null && action != null) setAction(actionText) { action() }
            setActionTextColor(ContextCompat.getColor(this@MainActivity, R.color.ls_mint))
            show()
        }
    }

    // ---- update check (separate from optional cloud sync) ----

    private fun maybeAutoCheckUpdate() {
        if (!settings.autoUpdateCheck) return
        val now = System.currentTimeMillis()
        if (now - settings.lastUpdateCheckMs < AppSettings.UPDATE_INTERVAL_MS) return
        checkUpdate(manual = false)
    }

    fun checkUpdate(manual: Boolean) {
        if (updateInFlight) return
        updateInFlight = true
        settings.lastUpdateCheckMs = System.currentTimeMillis()
        if (manual) snack(getString(R.string.update_checking))
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { UpdateChecker.check(BuildConfig.VERSION_NAME) }
            updateInFlight = false
            settingsPage.refresh()
            when (result) {
                is UpdateResult.Available -> Sheets.update(this@MainActivity, result.release, BuildConfig.VERSION_NAME)
                is UpdateResult.UpToDate -> if (manual) snack(
                    result.latest?.let { getString(R.string.update_latest_fmt, BuildConfig.VERSION_NAME) }
                        ?: getString(R.string.update_no_release)
                )
                is UpdateResult.Failed -> if (manual) snack(getString(R.string.update_failed_fmt, result.message))
            }
        }
    }

    // ---- backup ----

    fun exportBackup() {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
        createBackup.launch("ListenStat-backup-$stamp.json")
    }

    fun importBackup() {
        openBackup.launch(arrayOf("application/json", "text/*", "application/octet-stream"))
    }

    private fun runBackup(export: Boolean, uri: Uri) {
        snack(getString(R.string.backup_working))
        lifecycleScope.launch {
            val mgr = BackupManager(this@MainActivity)
            val msg = try {
                if (export) {
                    val r = withContext(Dispatchers.IO) { mgr.export(uri) }
                    getString(R.string.backup_exported_fmt, r.sessions, r.achievements)
                } else {
                    val r = withContext(Dispatchers.IO) { mgr.import(uri) }
                    statsPage.reload()
                    settingsPage.refresh()
                    applyWallpaper()
                    refreshAchievements(announce = false)
                    getString(R.string.backup_imported_fmt, r.added, r.skipped)
                }
            } catch (e: Exception) {
                getString(R.string.backup_failed_fmt, e.message ?: e.javaClass.simpleName)
            }
            snack(msg)
        }
    }


    // ---- optional cloud sync (default off; no traffic when disabled) ----

    fun onCloudSyncEnabled() {
        settingsPage.setSyncStatus(getString(R.string.sync_enabling))
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) { syncManager.enableAndSync() }
            when (outcome) {
                is SyncManager.Outcome.Ok -> {
                    settingsPage.setSyncStatus(getString(R.string.sync_ok_fmt, outcome.detail))
                    snack(getString(R.string.sync_ok_fmt, outcome.detail))
                    statsPage.reload()
                }
                is SyncManager.Outcome.Err -> {
                    settings.cloudSyncEnabled = false
                    settingsPage.refresh()
                    settingsPage.setSyncStatus(getString(R.string.sync_failed_fmt, outcome.message))
                    snack(getString(R.string.sync_failed_fmt, outcome.message))
                }
                SyncManager.Outcome.Skipped -> settingsPage.refresh()
            }
        }
    }

    fun onDisplayNameChanged(name: String) {
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) { syncManager.patchDisplayName(name) }
            when (outcome) {
                is SyncManager.Outcome.Ok -> snack(getString(R.string.sync_name_saved))
                is SyncManager.Outcome.Err -> snack(getString(R.string.sync_failed_fmt, outcome.message))
                SyncManager.Outcome.Skipped -> Unit
            }
        }
    }

    private fun maybePushOnLeave() {
        if (!settings.cloudSyncEnabled) return
        lifecycleScope.launch(Dispatchers.IO) { syncManager.maybePush() }
    }

    private fun maybeDailyCloudPush() {
        if (!settings.cloudSyncEnabled) return
        lifecycleScope.launch(Dispatchers.IO) { syncManager.maybeDailyPush() }
    }

    // ---- wallpaper ----

    fun hasWallpaper() = wallpaperStore.exists()

    fun pickWallpaper() {
        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun setWallpaper(uri: Uri) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { wallpaperStore.import(uri) }.isSuccess
            }
            wallpaperBitmap = null
            applyWallpaper()
            settingsPage.refresh()
            snack(getString(if (ok) R.string.wallpaper_set else R.string.wallpaper_failed))
        }
    }

    fun removeWallpaper() {
        wallpaperStore.remove()
        wallpaperBitmap = null
        applyWallpaper()
        settingsPage.refresh()
        snack(getString(R.string.wallpaper_removed))
    }

    fun applyWallpaper() {
        val has = wallpaperStore.exists()
        Ui.glass = has
        binding.wallpaper.isVisible = has
        binding.wallpaperVeil.isVisible = has
        if (!has) {
            binding.wallpaper.setImageDrawable(null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) binding.wallpaper.setRenderEffect(null)
        } else {
            lifecycleScope.launch {
                val bmp = wallpaperBitmap ?: withContext(Dispatchers.IO) { wallpaperStore.load() }
                wallpaperBitmap = bmp
                if (bmp == null) return@launch
                val blur = settings.wallpaperBlur
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    WallpaperStore.show(binding.wallpaper, bmp, blur)
                } else {
                    val blurred = withContext(Dispatchers.Default) {
                        if (blur > 0) WallpaperStore.fallbackBlur(bmp, blur) else bmp
                    }
                    binding.wallpaper.setImageBitmap(blurred)
                }
            }
        }
        applyVeil()
        listOf<View>(
            binding.pageStats.root, binding.pageAchievements.root,
            binding.pageSettings.root, binding.pageAppDetail.root
        ).forEach { Ui.applyGlass(it) }
    }

    fun applyVeil() {
        binding.wallpaperVeil.alpha = settings.wallpaperDim / 100f
    }

    // ---- app ranking drill-down (local only) ----

    private fun restoreMainPageVisibility() {
        binding.pageStats.root.isVisible = page == Page.STATS
        binding.pageAchievements.root.isVisible = page == Page.ACHIEVEMENTS
        binding.pageSettings.root.isVisible = page == Page.SETTINGS
        binding.pageAppDetail.root.isVisible = false
    }

    fun openAppDetail(packageName: String, appLabel: String, period: Period) {
        binding.navPill.isVisible = false
        binding.pageStats.root.isVisible = false
        binding.pageAchievements.root.isVisible = false
        binding.pageSettings.root.isVisible = false
        appDetailPage.open(packageName, appLabel, period)
    }

    // ---- misc ----

    fun isNotificationAccessEnabled(): Boolean {
        val cn = ComponentName(this, MediaTrackService::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(cn.flattenToString()) == true
    }

    private companion object {
        const val KEY_PAGE = "page"
    }
}
