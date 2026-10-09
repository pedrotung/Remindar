package com.example.reminder

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object NotificationHelper {

    private const val CHANNEL_ID = "reminders"
    /** 逾期待办的每日提醒：静音、不振动，只在通知栏和锁屏显示 */
    private const val CHANNEL_OVERDUE = "overdue_todos"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.channel_desc)
                enableVibration(true)
            }
        )
        // IMPORTANCE_DEFAULT：会出现在状态栏和锁屏上（LOW 级别在不少系统上不显示在锁屏），
        // 再去掉声音和振动，就是“只显示、不打扰”
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_OVERDUE,
                context.getString(R.string.channel_overdue_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.channel_overdue_desc)
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    private fun canNotify(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // 已在 canNotify 中检查
    fun show(context: Context, r: Reminder) {
        if (!canNotify(context)) return

        createChannel(context)
        val notifId = r.id.toInt()

        val openApp = PendingIntent.getActivity(
            context, notifId,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(r.title)
            .setContentText(r.note.ifBlank { r.formattedTime() })
            .setStyle(NotificationCompat.BigTextStyle().bigText(r.note.ifBlank { r.formattedTime() }))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .addAction(0, context.getString(R.string.snooze), actionIntent(context, r.id, ReminderReceiver.ACTION_SNOOZE))
            .addAction(
                0,
                context.getString(if (r.isTodo) R.string.todo_complete else R.string.done),
                actionIntent(context, r.id, ReminderReceiver.ACTION_DONE),
            )
            .build()

        NotificationManagerCompat.from(context).notify(notifId, notification)
    }

    /**
     * 逾期待办的静音通知。与到期提醒使用同一个通知 ID，因此会替换掉未清除的旧通知，不会重复堆积。
     * 锁屏隐藏敏感内容时，只显示“有待办已逾期”，不显示客户名、金额等。
     */
    @SuppressLint("MissingPermission") // 已在 canNotify 中检查
    fun showOverdue(context: Context, r: Reminder, now: Long) {
        if (!canNotify(context)) return

        createChannel(context)
        val notifId = r.id.toInt()
        val days = DateUtil.daysBetween(r.triggerAt, now).coerceAtLeast(1)
        val summary = context.getString(R.string.overdue_text, days, DateUtil.friendly(r.triggerAt, now))
        val body = if (r.note.isBlank()) summary else "$summary\n${r.note}"

        val openApp = PendingIntent.getActivity(
            context, notifId,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val publicVersion = NotificationCompat.Builder(context, CHANNEL_OVERDUE)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(context.getString(R.string.overdue_public_title))
            .setContentText(context.getString(R.string.overdue_public_text))
            .build()

        val notification = NotificationCompat.Builder(context, CHANNEL_OVERDUE)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(context.getString(R.string.overdue_title, r.title))
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setSilent(true) // Android 8.0 以下没有渠道，靠这里保证不响铃不振动
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .addAction(
                0,
                context.getString(R.string.todo_complete),
                actionIntent(context, r.id, ReminderReceiver.ACTION_DONE),
            )
            .build()

        NotificationManagerCompat.from(context).notify(notifId, notification)
    }

    private fun actionIntent(context: Context, id: Long, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, id.toInt(),
            Intent(context, ReminderReceiver::class.java)
                .setAction(action)
                .putExtra(ReminderReceiver.EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
