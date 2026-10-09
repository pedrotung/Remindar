package com.example.reminder

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 重复类型 */
enum class RepeatType(val label: String) {
    NONE("不重复"),
    DAILY("每天"),
    WEEKLY("每周"),
    MONTHLY("每月");

    /** 计算 time 之后的下一个周期时间；NONE 返回 null */
    fun next(time: Long): Long? {
        val field = when (this) {
            NONE -> return null
            DAILY -> Calendar.DAY_OF_YEAR
            WEEKLY -> Calendar.WEEK_OF_YEAR
            MONTHLY -> Calendar.MONTH
        }
        return Calendar.getInstance().apply {
            timeInMillis = time
            add(field, 1)
        }.timeInMillis
    }
}

/**
 * 提醒实体。
 *
 * 两种类型：
 * - 普通提醒（isTodo = false）：日程、预约等，到点提醒后即结束；
 * - 待办（isTodo = true）：要去做的事，到点提醒后仍保留，直到用户勾选完成（doneAt > 0）。
 *
 * enabled 表示“是否有闹钟在等待触发”；待办触发后 enabled 变为 false 但仍未完成，即“已逾期”。
 */
data class Reminder(
    val id: Long = 0,
    val title: String,
    val note: String = "",
    val triggerAt: Long,
    val repeat: RepeatType = RepeatType.NONE,
    val enabled: Boolean = true,
    val isTodo: Boolean = false,
    /** 完成时间；0 表示未完成（仅对待办有意义） */
    val doneAt: Long = 0,
) {
    val isDone: Boolean get() = doneAt > 0

    /** 未完成且提醒时间已过的待办 */
    fun isOverdue(now: Long = System.currentTimeMillis()) = isTodo && !isDone && triggerAt <= now

    /** 对重复提醒：把已过期的触发时间推进到 now 之后 */
    fun rolledForward(now: Long = System.currentTimeMillis()): Reminder {
        if (repeat == RepeatType.NONE) return this
        var t = triggerAt
        while (t <= now) t = repeat.next(t)!!
        return copy(triggerAt = t)
    }

    fun formattedTime(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(triggerAt))

    /** 列表用的友好时间：09:30 / 明天 09:30 / 10月12日 09:30 */
    fun friendlyTime(now: Long = System.currentTimeMillis()): String = DateUtil.friendly(triggerAt, now)
}

object DateUtil {

    fun startOfDay(t: Long): Long = Calendar.getInstance().apply {
        timeInMillis = t
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun isSameDay(a: Long, b: Long) = startOfDay(a) == startOfDay(b)

    /** 相差的自然日数（四舍五入以兼容夏令时） */
    fun daysBetween(from: Long, to: Long): Int =
        Math.round((startOfDay(to) - startOfDay(from)) / 86_400_000.0).toInt()

    fun friendly(t: Long, now: Long): String {
        val hm = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))
        val sameYear = Calendar.getInstance().apply { timeInMillis = t }.get(Calendar.YEAR) ==
            Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.YEAR)
        return when (daysBetween(now, t)) {
            0 -> hm
            1 -> "明天 $hm"
            2 -> "后天 $hm"
            -1 -> "昨天 $hm"
            else -> SimpleDateFormat(if (sameYear) "M月d日 HH:mm" else "yyyy年M月d日 HH:mm", Locale.getDefault())
                .format(Date(t))
        }
    }

    /** 带星期的完整时间，如“10月12日 周一 10:00” */
    fun withWeekday(t: Long): String =
        SimpleDateFormat("M月d日 EEE HH:mm", Locale.CHINA).format(Date(t))
}
