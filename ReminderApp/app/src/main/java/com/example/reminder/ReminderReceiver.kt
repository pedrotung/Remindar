package com.example.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat

/** 闹钟到点 / 通知按钮点击的统一入口 */
class ReminderReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_FIRE = "com.example.reminder.FIRE"
        const val ACTION_SNOOZE_FIRE = "com.example.reminder.SNOOZE_FIRE"
        const val ACTION_SNOOZE = "com.example.reminder.SNOOZE"
        const val ACTION_DONE = "com.example.reminder.DONE"
        const val ACTION_OVERDUE_CHECK = "com.example.reminder.OVERDUE_CHECK"
        const val EXTRA_ID = "id"
        const val SNOOZE_MINUTES = 10L
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_OVERDUE_CHECK) {
            if (AppSettings.overdueEnabled(context)) notifyOverdueTodos(context)
            AlarmScheduler.scheduleOverdueCheck(context) // 安排明天同一时间（已关闭则取消）
            return
        }

        val id = intent.getLongExtra(EXTRA_ID, -1L)
        if (id < 0) return
        val db = ReminderDb.get(context)

        when (intent.action) {
            ACTION_FIRE -> {
                val r = db.getById(id) ?: return
                if (!r.enabled || r.isDone) return
                NotificationHelper.show(context, r)
                // 重复：计算下一次并重新注册；一次性：闹钟结束（待办仍未完成，显示为逾期）
                val next = if (r.repeat == RepeatType.NONE) r.copy(enabled = false) else r.rolledForward()
                db.save(next)
                AlarmScheduler.schedule(context, next)
            }
            ACTION_SNOOZE_FIRE -> {
                val r = db.getById(id) ?: return
                if (r.isDone) return // 稍后提醒期间已在 App 中完成
                NotificationHelper.show(context, r)
            }
            ACTION_SNOOZE -> {
                NotificationManagerCompat.from(context).cancel(id.toInt())
                AlarmScheduler.scheduleSnooze(context, id, SNOOZE_MINUTES * 60_000)
            }
            ACTION_DONE -> {
                // 待办：“完成”即标记完成；普通提醒：“知道了”只关闭通知
                val r = db.getById(id)
                if (r != null && r.isTodo && !r.isDone && r.repeat == RepeatType.NONE) {
                    TodoHelper.complete(context, r)
                } else {
                    NotificationManagerCompat.from(context).cancel(id.toInt())
                }
            }
        }
    }

    /**
     * 每天在设定时间（默认 10:00）：对“今天之前就已到期、仍未完成”的待办各发一条静音通知。
     * 今天才到期的待办在到期时已经响过铃，今天不再重复，从明天起每天提醒。
     */
    private fun notifyOverdueTodos(context: Context) {
        val now = System.currentTimeMillis()
        val todayStart = DateUtil.startOfDay(now)
        ReminderDb.get(context).getAll()
            .filter { it.isTodo && !it.isDone && it.triggerAt < todayStart }
            .forEach { NotificationHelper.showOverdue(context, it, now) }
    }
}
