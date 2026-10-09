package com.example.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.reminder.findphone.FindPhoneConfig
import com.example.reminder.findphone.FindPhoneService

/** 系统重启、应用更新、修改时间/时区后，AlarmManager 中的闹钟会失效或偏移，需全部重新注册 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                AlarmScheduler.rescheduleAll(context)
                startFindPhone(context)
            }
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> AlarmScheduler.rescheduleAll(context)
        }
    }

    /** 开机 / 更新后自动恢复查找手机服务（这两个广播允许从后台启动前台服务） */
    private fun startFindPhone(context: Context) {
        if (!FindPhoneConfig.isEnabled(context)) return
        try {
            FindPhoneService.start(context)
        } catch (e: Exception) {
            Log.w("BootReceiver", "无法启动查找手机服务", e)
        }
    }
}
