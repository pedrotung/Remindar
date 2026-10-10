package com.example.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/** 负责向系统 AlarmManager 注册 / 取消提醒 */
object AlarmScheduler {

    fun canScheduleExact(context: Context): Boolean {
        val am = context.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
    }

    /** 按提醒的 triggerAt 注册闹钟；未启用或已过期的一次性提醒不注册 */
    fun schedule(context: Context, r: Reminder) {
        cancel(context, r.id)
        if (!r.enabled || r.triggerAt <= System.currentTimeMillis()) return
        setAlarmClock(context, r.triggerAt, pendingIntent(context, r.id, ReminderReceiver.ACTION_FIRE))
    }

    /** 稍后提醒：使用独立的 action，不会覆盖重复提醒的下一次闹钟 */
    fun scheduleSnooze(context: Context, id: Long, delayMillis: Long) {
        setAlarmClock(
            context,
            System.currentTimeMillis() + delayMillis,
            pendingIntent(context, id, ReminderReceiver.ACTION_SNOOZE_FIRE),
        )
    }

    /**
     * 到点响铃的提醒使用“闹钟”级别的 setAlarmClock：系统最高优先级、不参与对齐批处理、Doze 下也准时。
     * （ColorOS 实测：setExactAndAllowWhileIdle 会被加上 20 秒～1 分多钟的对齐窗口，导致提醒迟到。）
     * 代价：有待触发的提醒时，状态栏会显示闹钟图标，锁屏 / 时钟可能显示“下一个闹钟”。
     */
    private fun setAlarmClock(context: Context, at: Long, pi: PendingIntent) {
        if (!canScheduleExact(context)) {
            setAlarm(context, at, pi) // 无精确闹钟权限时退回非精确闹钟
            return
        }
        val show = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        context.getSystemService(AlarmManager::class.java)
            .setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
    }

    fun cancel(context: Context, id: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        am.cancel(pendingIntent(context, id, ReminderReceiver.ACTION_FIRE))
        am.cancel(pendingIntent(context, id, ReminderReceiver.ACTION_SNOOZE_FIRE))
    }

    /** 开机 / 时区变化后重新注册所有启用的提醒 */
    fun rescheduleAll(context: Context) {
        val db = ReminderDb.get(context)
        val now = System.currentTimeMillis()
        for (r in db.getEnabled()) {
            val updated = when {
                r.repeat != RepeatType.NONE -> r.rolledForward(now)
                r.triggerAt <= now -> r.copy(enabled = false) // 关机期间错过的一次性提醒
                else -> r
            }
            if (updated != r) db.save(updated)
            schedule(context, updated)
        }
        scheduleOverdueCheck(context)
    }

    /**
     * 注册下一次“逾期待办检查”，时间取自设置（默认 10:00）：今天的时间还没到则今天，否则明天。
     * 全局只有一个这样的闹钟，重复调用只会覆盖，不会叠加；设置中关闭时取消。
     */
    fun scheduleOverdueCheck(context: Context) {
        val pi = pendingIntent(context, 0, ReminderReceiver.ACTION_OVERDUE_CHECK)
        if (!AppSettings.overdueEnabled(context)) {
            context.getSystemService(AlarmManager::class.java).cancel(pi)
            return
        }
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, AppSettings.overdueHour(context))
            set(Calendar.MINUTE, AppSettings.overdueMinute(context))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
        setAlarm(context, cal.timeInMillis, pi)
    }

    private fun setAlarm(context: Context, at: Long, pi: PendingIntent) {
        val am = context.getSystemService(AlarmManager::class.java)
        if (canScheduleExact(context)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            // 未获得精确闹钟权限时降级为非精确闹钟（可能有几分钟误差）
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun pendingIntent(context: Context, id: Long, action: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(action)
            .putExtra(ReminderReceiver.EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            context,
            id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
