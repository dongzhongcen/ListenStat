package com.dongzhongcen.listenstat.ui

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.dongzhongcen.listenstat.data.AppDatabase
import com.dongzhongcen.listenstat.databinding.ActivityMainBinding
import com.dongzhongcen.listenstat.service.MediaTrackService
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val dayFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val monthFmt = DateTimeFormatter.ofPattern("yyyy-MM")
    private val yearFmt = DateTimeFormatter.ofPattern("yyyy")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            Toast.makeText(this, R.string.enable_listenstat, Toast.LENGTH_LONG).show()
        }
        binding.btnRefresh.setOnClickListener { refresh() }

        val ranges = listOf("今天", "本月", "本年")
        binding.spinnerRange.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, ranges)

        binding.spinnerRange.setSelection(0)
        binding.spinnerRange.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: android.view.View?,
                    position: Int,
                    id: Long
                ) = refresh()

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
    }

    override fun onResume() {
        super.onResume()
        binding.txtPermission.text = if (isNotificationAccessEnabled()) {
            getString(R.string.permission_on)
        } else {
            getString(R.string.permission_off)
        }
        refresh()
    }

    private fun prefix(): String {
        val today = LocalDate.now()
        return when (binding.spinnerRange.selectedItemPosition) {
            1 -> today.format(monthFmt)
            2 -> today.format(yearFmt)
            else -> today.format(dayFmt)
        }
    }

    private fun refresh() {
        val p = prefix()
        lifecycleScope.launch {
            val dao = AppDatabase.get(this@MainActivity).playDao()
            val total = dao.totalMs(p) ?: 0L
            val apps = dao.topApps(p, 20)
            val titles = dao.topTitles(p, 20)
            binding.txtTotal.text = getString(R.string.total_fmt, formatDuration(total))
            binding.txtApps.text = apps.joinToString("\n") {
                "• ${labelForApp(it.name)} — ${formatDuration(it.totalMs)}"
            }.ifBlank { getString(R.string.empty) }
            binding.txtTitles.text = titles.joinToString("\n") {
                "• ${it.name} — ${formatDuration(it.totalMs)}"
            }.ifBlank { getString(R.string.empty) }
        }
    }

    private fun labelForApp(packageName: String): String {
        return try {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (_: Exception) {
            packageName
        }
    }

    private fun formatDuration(ms: Long): String {
        val h = TimeUnit.MILLISECONDS.toHours(ms)
        val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return when {
            h > 0 -> "${h}小时${m}分"
            m > 0 -> "${m}分${s}秒"
            else -> "${s}秒"
        }
    }

    private fun isNotificationAccessEnabled(): Boolean {
        val cn = ComponentName(this, MediaTrackService::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(cn.flattenToString()) == true
    }
}
