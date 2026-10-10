package com.example.reminder

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import com.example.reminder.databinding.SheetDateTimeBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 底部弹出的滚轮时间选择器（取代系统表盘式 TimePickerDialog）：
 * 日期（今天 / 明天 / 后天 / 10月14日 周三）、时（00–23）、分（00–59）三列滚轮，
 * 上方实时显示“2 小时 5 分钟后”。
 */
object DateTimePickerSheet {

    private const val DAYS = 366

    /**
     * @param withDate false 时只选时分（如设置每日提醒时间），返回值为“今天”的该时刻
     * @param onPicked 返回选定时刻的毫秒时间戳（秒、毫秒为 0）
     */
    fun show(context: Context, title: String, initial: Long, withDate: Boolean, onPicked: (Long) -> Unit) {
        val dialog = BottomSheetDialog(context)
        val b = SheetDateTimeBinding.inflate(LayoutInflater.from(context))
        val today = DateUtil.startOfDay(System.currentTimeMillis())
        val init = Calendar.getInstance().apply { timeInMillis = initial }

        b.sheetTitle.text = title

        with(b.datePicker) {
            minValue = 0
            maxValue = DAYS - 1
            displayedValues = Array(DAYS) { dayLabel(today, it) }
            wrapSelectorWheel = false
            value = DateUtil.daysBetween(today, initial).coerceIn(0, DAYS - 1)
            visibility = if (withDate) View.VISIBLE else View.GONE
        }
        with(b.hourPicker) {
            minValue = 0
            maxValue = 23
            displayedValues = Array(24) { "%02d".format(it) }
            value = init.get(Calendar.HOUR_OF_DAY)
        }
        with(b.minutePicker) {
            minValue = 0
            maxValue = 59
            displayedValues = Array(60) { "%02d".format(it) }
            value = init.get(Calendar.MINUTE)
        }

        fun selected(): Long = Calendar.getInstance().apply {
            timeInMillis = today
            add(Calendar.DAY_OF_YEAR, if (withDate) b.datePicker.value else 0)
            set(Calendar.HOUR_OF_DAY, b.hourPicker.value)
            set(Calendar.MINUTE, b.minutePicker.value)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        fun updatePreview() {
            b.sheetPreview.text = if (withDate) {
                relativeText(context, selected() - System.currentTimeMillis())
            } else {
                context.getString(R.string.picker_every_day, "%02d:%02d".format(b.hourPicker.value, b.minutePicker.value))
            }
        }
        b.datePicker.setOnValueChangedListener { _, _, _ -> updatePreview() }
        b.hourPicker.setOnValueChangedListener { _, _, _ -> updatePreview() }
        b.minutePicker.setOnValueChangedListener { _, _, _ -> updatePreview() }
        updatePreview()

        b.sheetConfirm.setOnClickListener {
            onPicked(selected())
            dialog.dismiss()
        }
        dialog.setContentView(b.root)
        dialog.show()
    }

    /** 今天 周六 / 明天 周日 / 后天 周一 / 10月14日 周三 */
    private fun dayLabel(today: Long, offset: Int): String {
        val t = Calendar.getInstance().apply {
            timeInMillis = today
            add(Calendar.DAY_OF_YEAR, offset)
        }.time
        val weekday = SimpleDateFormat("EEE", Locale.CHINA).format(t)
        return when (offset) {
            0 -> "今天 $weekday"
            1 -> "明天 $weekday"
            2 -> "后天 $weekday"
            else -> SimpleDateFormat("M月d日", Locale.CHINA).format(t) + " " + weekday
        }
    }

    /** “2 小时 5 分钟后” / “3 天 2 小时后” / “时间已过” */
    fun relativeText(context: Context, deltaMs: Long): String {
        if (deltaMs <= 0) return context.getString(R.string.picker_passed)
        val totalMin = (deltaMs + 59_999) / 60_000 // 不足 1 分钟按 1 分钟算
        val days = totalMin / (24 * 60)
        val hours = totalMin % (24 * 60) / 60
        val minutes = totalMin % 60
        val text = when {
            days > 0 -> if (hours > 0) "$days 天 $hours 小时" else "$days 天"
            hours > 0 -> if (minutes > 0) "$hours 小时 $minutes 分钟" else "$hours 小时"
            else -> "$minutes 分钟"
        }
        return context.getString(R.string.picker_relative, text)
    }
}
