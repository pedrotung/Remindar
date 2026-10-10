package com.example.reminder

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * 到点提醒的响铃服务（前台服务）：最多响 [RING_MS]，期间显示可在锁屏上全屏弹出的通知。
 *
 * - 用户点“知道了 / 完成 / 10 分钟后”：停止响铃并移除通知；
 * - 超时无人处理：停止响铃，提醒转为一条静默通知留在通知栏；
 * - 响铃中又有新提醒到点：前一条转为静默通知，改响新的一条。
 */
class ReminderRingService : Service() {

    companion object {
        private const val TAG = "ReminderRingService"
        private const val ACTION_START = "com.example.reminder.RING_START"
        private const val ACTION_STOP = "com.example.reminder.RING_STOP"
        const val EXTRA_ID = "id"
        /** 响铃结束（任何原因）时的应用内广播，用于关闭全屏提醒界面 */
        const val ACTION_RING_STOPPED = "com.example.reminder.RING_STOPPED"
        private const val RING_MS = 60_000L

        /** 正在响铃的提醒 ID；-1 表示没有 */
        @Volatile var ringingId: Long = -1L
            private set

        /**
         * 开始响铃。闹钟广播触发时系统允许启动前台服务；
         * 若被系统拒绝（如未获得精确闹钟权限时），退回为普通通知，保证提醒不丢。
         */
        fun start(context: Context, r: Reminder) {
            val intent = Intent(context, ReminderRingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ID, r.id)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.w(TAG, "无法启动响铃服务，改为普通通知", e)
                NotificationHelper.show(context, r)
            }
        }

        /** 若该提醒正在响铃则停止 */
        fun stop(context: Context, id: Long) {
            if (ringingId != id) return
            try {
                context.startService(Intent(context, ReminderRingService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_ID, id))
            } catch (e: Exception) {
                Log.w(TAG, "无法停止响铃服务", e)
            }
        }

        fun stopIntent(context: Context, id: Long): Intent =
            Intent(context, ReminderRingService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_ID, id)
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var player: ReminderTonePlayer
    private var current: Reminder? = null
    private val timeout = Runnable {
        finishRinging(timedOut = true)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        player = ReminderTonePlayer(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getLongExtra(EXTRA_ID, -1L) ?: -1L
        when (intent?.action) {
            ACTION_START -> {
                val r = ReminderDb.get(this).getById(id)
                if (r == null) {
                    // 前台服务启动后必须调用 startForeground，否则会崩溃；数据已不存在时先满足要求再退出
                    goForeground(id.toInt().coerceAtLeast(1), NotificationHelper.buildPlaceholder(this))
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (current != null && current?.id != r.id) finishRinging(timedOut = true)
                current = r
                ringingId = r.id
                goForeground(r.id.toInt(), NotificationHelper.buildRinging(this, r))
                player.start()
                wakeScreen()
                showAlertDirectly(r)
                handler.removeCallbacks(timeout)
                handler.postDelayed(timeout, RING_MS)
            }
            ACTION_STOP -> {
                if (current?.id == id) finishRinging(timedOut = false)
                if (current == null) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (current != null) finishRinging(timedOut = true)
        super.onDestroy()
    }

    private fun goForeground(notifId: Int, notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(notifId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(notifId, notification)
        }
    }

    /**
     * 点亮屏幕，让锁屏上能看到提醒。
     * 全屏通知本应负责亮屏，但部分系统（如 ColorOS）会把应用的通知级别压到“默认”，全屏通知因此不生效，
     * 所以这里自己点亮。SCREEN_BRIGHT_WAKE_LOCK 虽已标记弃用，但仍是普通应用唯一能主动亮屏的方式。
     */
    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isInteractive) return
        pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "reminder:ring-wake",
        ).acquire(10_000)
    }

    /**
     * 已获得“悬浮窗（显示在其他应用上层）”权限时，直接打开全屏提醒卡片，不依赖通知级别。
     * 没有该权限时，后台无法启动界面，只能依靠全屏通知（可能被系统降级）。
     */
    private fun showAlertDirectly(r: Reminder) {
        if (!Settings.canDrawOverlays(this)) return
        try {
            startActivity(
                Intent(this, ReminderAlertActivity::class.java)
                    .putExtra(ReminderAlertActivity.EXTRA_ID, r.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            )
        } catch (e: Exception) {
            Log.w(TAG, "无法打开全屏提醒", e)
        }
    }

    private fun finishRinging(timedOut: Boolean) {
        handler.removeCallbacks(timeout)
        player.stop()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        val r = current
        // 超时无人处理：留一条静默通知，避免提醒“消失”
        if (timedOut && r != null) NotificationHelper.show(this, r, quiet = true)
        current = null
        ringingId = -1L
        sendBroadcast(Intent(ACTION_RING_STOPPED).setPackage(packageName))
    }
}
