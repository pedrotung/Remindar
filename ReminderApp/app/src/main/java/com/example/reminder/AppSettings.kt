package com.example.reminder

import android.content.Context

/** 应用偏好设置（SharedPreferences） */
object AppSettings {

    private const val PREFS = "settings"
    private const val KEY_OVERDUE_ENABLED = "overdue_enabled"
    private const val KEY_OVERDUE_HOUR = "overdue_hour"
    private const val KEY_OVERDUE_MINUTE = "overdue_minute"

    /** 默认值：开启，每天 10:00 */
    private const val DEFAULT_OVERDUE_HOUR = 10
    private const val DEFAULT_OVERDUE_MINUTE = 0

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun overdueEnabled(context: Context) = prefs(context).getBoolean(KEY_OVERDUE_ENABLED, true)

    fun overdueHour(context: Context) = prefs(context).getInt(KEY_OVERDUE_HOUR, DEFAULT_OVERDUE_HOUR)

    fun overdueMinute(context: Context) = prefs(context).getInt(KEY_OVERDUE_MINUTE, DEFAULT_OVERDUE_MINUTE)

    fun setOverdueEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_OVERDUE_ENABLED, enabled).apply()

    fun setOverdueTime(context: Context, hour: Int, minute: Int) =
        prefs(context).edit().putInt(KEY_OVERDUE_HOUR, hour).putInt(KEY_OVERDUE_MINUTE, minute).apply()

    fun overdueTimeText(context: Context) = "%02d:%02d".format(overdueHour(context), overdueMinute(context))
}
