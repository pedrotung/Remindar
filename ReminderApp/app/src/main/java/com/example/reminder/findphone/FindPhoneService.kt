package com.example.reminder.findphone

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.reminder.R
import java.io.IOException

/**
 * 前台服务：常驻运行局域网 HTTP 服务器，收到指令后播放报警声。
 * 使用前台服务是为了不被系统回收，并在 Doze 省电模式下仍保有网络访问。
 */
class FindPhoneService : Service(), FindPhoneServer.Callback {

    companion object {
        const val ACTION_RING = "com.example.reminder.findphone.RING"
        const val ACTION_SILENCE = "com.example.reminder.findphone.SILENCE"
        const val ACTION_STOP = "com.example.reminder.findphone.STOP"
        /** 响铃结束时发出的应用内广播，用于关闭响铃界面 */
        const val ACTION_RINGING_STOPPED = "com.example.reminder.findphone.RINGING_STOPPED"

        private const val TAG = "FindPhoneService"
        private const val CHANNEL_SERVICE = "find_phone_service"
        private const val CHANNEL_RINGING = "find_phone_ringing"
        private const val NOTIF_SERVICE = 9001
        private const val NOTIF_RINGING = 9002
        /** 无人处理时自动停止，避免一直响到没电 */
        private const val AUTO_STOP_MS = 3 * 60_000L

        @Volatile var isRunning = false
            private set
        @Volatile var isRinging = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, FindPhoneService::class.java))
        }

        /** 向已运行的服务发送指令 */
        fun send(context: Context, action: String) {
            context.startService(Intent(context, FindPhoneService::class.java).setAction(action))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var player: AlarmPlayer
    private var server: FindPhoneServer? = null
    private var serverError: String? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var idleWakeLock: PowerManager.WakeLock? = null
    private var ringWakeLock: PowerManager.WakeLock? = null
    private val autoStop = Runnable { stopRinging() }

    /** 网络变化（换 Wi‑Fi、开关热点）时刷新通知里显示的地址 */
    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = refreshServiceNotification()
        override fun onLost(network: Network) = refreshServiceNotification()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        val n = buildServiceNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_SERVICE, n)
        }
        isRunning = true
        player = AlarmPlayer(this)

        val page = assets.open("findphone.html").bufferedReader().use { it.readText() }
        server = FindPhoneServer(FindPhoneConfig.PORT, { FindPhoneConfig.pin(this) }, this, page).also {
            try {
                it.start()
            } catch (e: IOException) {
                Log.e(TAG, "无法监听端口 ${FindPhoneConfig.PORT}", e)
                serverError = getString(R.string.fp_port_error, FindPhoneConfig.PORT)
            }
        }

        acquireLocks()
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(netCallback)
        refreshServiceNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RING -> startRinging()
            ACTION_SILENCE -> stopRinging()
            ACTION_STOP -> {
                stopRinging()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopRinging()
        server?.stop()
        try {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(netCallback)
        } catch (_: IllegalArgumentException) { }
        wifiLock?.release()
        idleWakeLock?.release()
        isRunning = false
        super.onDestroy()
    }

    /**
     * 熄屏时 CPU 是被网络数据包短暂唤醒的，随时可能重新休眠。
     * 收到响铃指令后先在服务器线程立即持锁，保证切到主线程、开始播放之前 CPU 不会睡回去。
     */
    private val handoffWakeLock by lazy {
        getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "reminder:findphone-handoff")
            .apply { setReferenceCounted(false) }
    }

    // ---- FindPhoneServer.Callback：在服务器线程调用，切回主线程执行 ----
    override fun onRing() {
        handoffWakeLock.acquire(10_000)
        main.post {
            startRinging()
            if (handoffWakeLock.isHeld) handoffWakeLock.release() // 此时 ringWakeLock 已接手
        }
    }
    override fun onSilence() { main.post { stopRinging() } }
    override fun isRingingNow() = isRinging

    private fun startRinging() {
        if (isRinging) return
        isRinging = true
        ringWakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "reminder:findphone-ring")
            .apply { acquire(AUTO_STOP_MS + 5_000) }
        player.start()
        main.postDelayed(autoStop, AUTO_STOP_MS)
        showRingingNotification()
    }

    private fun stopRinging() {
        if (!isRinging) return
        isRinging = false
        main.removeCallbacks(autoStop)
        player.stop()
        getSystemService(NotificationManager::class.java).cancel(NOTIF_RINGING)
        ringWakeLock?.let { if (it.isHeld) it.release() }
        ringWakeLock = null
        sendBroadcast(Intent(ACTION_RINGING_STOPPED).setPackage(packageName))
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        // 尽量让 Wi‑Fi 保持高性能模式。注意：较新的 Android 上该锁在熄屏时基本不起作用，
        // 熄屏时能收到请求主要依靠 Wi‑Fi 芯片收到单播数据包后唤醒 CPU（与收到即时消息推送同理）
        wifiLock = applicationContext.getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "reminder:findphone")
            .apply { setReferenceCounted(false); acquire() }
        if (FindPhoneConfig.keepAwake(this)) {
            idleWakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "reminder:findphone-idle")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, getString(R.string.fp_channel_service), NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RINGING, getString(R.string.fp_channel_ringing), NotificationManager.IMPORTANCE_HIGH)
                .apply { setSound(null, null); enableVibration(false) } // 声音和振动由 AlarmPlayer 负责
        )
    }

    private fun buildServiceNotification(): Notification {
        val text = serverError
            ?: FindPhoneConfig.urls().firstOrNull()?.let { getString(R.string.fp_notif_url, it) }
            ?: getString(R.string.fp_no_network)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, FindPhoneActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.fp_notif_title))
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .build()
    }

    @SuppressLint("MissingPermission") // 未授予通知权限时 notify 不会显示，但不影响响铃
    private fun refreshServiceNotification() {
        if (!isRunning) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_SERVICE, buildServiceNotification())
    }

    @SuppressLint("MissingPermission")
    private fun showRingingNotification() {
        val full = PendingIntent.getActivity(
            this, 1,
            Intent(this, RingingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val silence = PendingIntent.getService(
            this, 2,
            Intent(this, FindPhoneService::class.java).setAction(ACTION_SILENCE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, CHANNEL_RINGING)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(getString(R.string.fp_ringing_title))
            .setContentText(getString(R.string.fp_ringing_text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setFullScreenIntent(full, true) // 锁屏时直接弹出全屏“找到了”界面
            .setContentIntent(full)
            .addAction(0, getString(R.string.fp_found), silence)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_RINGING, n)
    }
}
