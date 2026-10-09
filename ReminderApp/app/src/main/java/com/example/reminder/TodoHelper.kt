package com.example.reminder

import android.content.Context
import androidx.core.app.NotificationManagerCompat

/** 待办的完成 / 撤销完成。列表勾选和通知上的“完成”按钮共用这里的逻辑 */
object TodoHelper {

    /**
     * - 一次性待办：记录完成时间，取消闹钟；
     * - 重复待办：本次算完成，直接跳到下一个周期（如每周报告提前写完了，下周照常提醒）。
     */
    fun complete(context: Context, r: Reminder): Reminder {
        val db = ReminderDb.get(context)
        val now = System.currentTimeMillis()
        val updated = if (r.repeat == RepeatType.NONE) {
            r.copy(doneAt = now, enabled = false)
        } else {
            r.copy(triggerAt = r.repeat.next(r.triggerAt)!!, enabled = true).rolledForward(now)
        }
        db.save(updated)
        if (updated.enabled) AlarmScheduler.schedule(context, updated) else AlarmScheduler.cancel(context, r.id)
        NotificationManagerCompat.from(context).cancel(r.id.toInt())
        return updated
    }

    /** 撤销完成：时间未到则重新设置闹钟，已过则显示为逾期 */
    fun reopen(context: Context, r: Reminder): Reminder {
        val now = System.currentTimeMillis()
        val updated = r.copy(doneAt = 0, enabled = r.triggerAt > now)
        ReminderDb.get(context).save(updated)
        if (updated.enabled) AlarmScheduler.schedule(context, updated)
        return updated
    }
}
