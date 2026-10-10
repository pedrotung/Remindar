package com.example.reminder

import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.reminder.databinding.ActivitySettingsBinding

/** 设置：到点提醒的全屏显示权限、逾期待办每日提醒的开关与时间 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.settings_title)

        binding.overdueSwitch.isChecked = AppSettings.overdueEnabled(this)
        binding.overdueSwitch.setOnCheckedChangeListener { _, on ->
            AppSettings.setOverdueEnabled(this, on)
            AlarmScheduler.scheduleOverdueCheck(this) // 关闭时会取消闹钟
            val msg = if (on) getString(R.string.settings_overdue_saved, AppSettings.overdueTimeText(this))
            else getString(R.string.settings_overdue_off)
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            refresh()
        }
        binding.overdueTimeRow.setOnClickListener { pickTime() }
        binding.overlayRow.setOnClickListener { openOverlaySettings() }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh() // 从系统设置页返回后更新权限状态
    }

    /** 打开系统的“悬浮窗 / 显示在其他应用上层”授权页 */
    private fun openOverlaySettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun refresh() {
        val on = AppSettings.overdueEnabled(this)
        binding.overdueTimeValue.text = AppSettings.overdueTimeText(this)
        binding.overdueTimeRow.isEnabled = on
        binding.overdueTimeRow.alpha = if (on) 1f else 0.4f

        val overlay = Settings.canDrawOverlays(this)
        binding.overlayStatus.setText(if (overlay) R.string.fp_granted else R.string.fp_grant)
        binding.overlayStatus.setTextColor(ContextCompat.getColor(this, if (overlay) R.color.success else R.color.accent))
    }

    private fun pickTime() {
        TimePickerDialog(
            this,
            { _, hour, minute ->
                AppSettings.setOverdueTime(this, hour, minute)
                AlarmScheduler.scheduleOverdueCheck(this)
                Toast.makeText(
                    this, getString(R.string.settings_overdue_saved, AppSettings.overdueTimeText(this)),
                    Toast.LENGTH_SHORT,
                ).show()
                refresh()
            },
            AppSettings.overdueHour(this),
            AppSettings.overdueMinute(this),
            DateFormat.is24HourFormat(this), // 跟随系统的 12 / 24 小时制
        ).show()
    }
}
