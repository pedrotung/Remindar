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

    /** 退回方案：响铃服务无法启动时使用的普通提醒通知（系统提示音） */
    private const val CHANNEL_ID = "reminders"
    /** 到点响铃时的通知：本身不发声（铃声由 ReminderRingService 播放），高优先级、可全屏弹出 */
    private const val CHANNEL_ALARM = "reminder_alarm"
    /**
     * 静默提醒：逾期待办的每日提醒、超时无人处理的提醒。静音、不振动，只在通知栏和锁屏显示。
     * （渠道 ID 沿用最初的 overdue_todos，已安装用户无需重建渠道）
     */
    private const val CHANNEL_QUIET = "overdue_todos"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALARM,
                context.getString(R.string.channel_alarm_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.channel_alarm_desc)
                setSound(null, null)
                enableVibration(false)
            }
        )
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
                CHANNEL_QUIET,
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

    /**
     * 普通提醒通知。
     * quiet = false：系统提示音（响铃服务无法启动时的退回方案）；
     * quiet = true：静默（响铃超时无人处理后留在通知栏）。
     */
    @SuppressLint("MissingPermission") // 已在 canNotify 中检查
    fun show(context: Context, r: Reminder, quiet: Boolean = false) {
        if (!canNotify(context)) return

        createChannel(context)
        val builder = NotificationCompat.Builder(context, if (quiet) CHANNEL_QUIET else CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(r.title)
            .setContentText(bodyText(r))
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText(r)))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, r.id))
            .addReminderActions(context, r)
        if (quiet) {
            builder.setSilent(true).setPriority(NotificationCompat.PRIORITY_DEFAULT)
        } else {
            builder.setDefaults(NotificationCompat.DEFAULT_ALL).setPriority(NotificationCompat.PRIORITY_HIGH)
        }
        NotificationManagerCompat.from(context).notify(r.id.toInt(), builder.build())
    }

    /** 响铃中的通知（作为 ReminderRingService 的前台通知）：锁屏时全屏弹出提醒卡片 */
    fun buildRinging(context: Context, r: Reminder): android.app.Notification {
        createChannel(context)
        val alert = PendingIntent.getActivity(
            context, r.id.toInt(),
            Intent(context, ReminderAlertActivity::class.java)
                .putExtra(ReminderAlertActivity.EXTRA_ID, r.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            context, r.id.toInt(),
            ReminderRingService.stopIntent(context, r.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(r.title)
            .setContentText(bodyText(r))
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText(r)))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setSilent(true) // 声音由响铃服务播放
            .setOngoing(true)
            .setFullScreenIntent(alert, true)
            .setContentIntent(alert)
            .setDeleteIntent(stop) // Android 14 起前台通知可被划掉，划掉即停止响铃
            .addReminderActions(context, r)
            .build()
    }

    /** 提醒已被删除时，前台服务仍需一条通知才能合法启动，随后立即停止 */
    fun buildPlaceholder(context: Context): android.app.Notification {
        createChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_QUIET)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setSilent(true)
            .build()
    }

    private fun bodyText(r: Reminder) = r.note.ifBlank { r.formattedTime() }

    private fun openAppIntent(context: Context, id: Long) = PendingIntent.getActivity(
        context, id.toInt(),
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** “10 分钟后” + “知道了 / 完成” */
    private fun NotificationCompat.Builder.addReminderActions(context: Context, r: Reminder) = this
        .addAction(0, context.getString(R.string.snooze), actionIntent(context, r.id, ReminderReceiver.ACTION_SNOOZE))
        .addAction(
            0,
            context.getString(if (r.isTodo) R.string.todo_complete else R.string.done),
            actionIntent(context, r.id, ReminderReceiver.ACTION_DONE),
        )

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

        val publicVersion = NotificationCompat.Builder(context, CHANNEL_QUIET)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.overdue_public_title))
            .setContentText(context.getString(R.string.overdue_public_text))
            .build()

        val notification = NotificationCompat.Builder(context, CHANNEL_QUIET)
            .setSmallIcon(R.drawable.ic_notification)
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
