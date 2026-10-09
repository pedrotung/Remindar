package com.example.reminder.findphone

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import kotlin.math.PI
import kotlin.math.sin

/**
 * 报警声播放器。
 *
 * 静音状态下也能响的关键：
 * 1. 以 USAGE_ALARM（闹钟音频流）播放 —— 铃声静音 / 振动模式只影响铃声流，不影响闹钟流；
 * 2. 响铃前把闹钟音量临时调到最大，结束后恢复；
 * 3. 若用户授予了“勿扰权限”，响铃期间临时关闭勿扰（“完全静音”模式会拦截闹钟），结束后恢复。
 *
 * 声音由程序实时合成（双音警笛），不依赖系统铃声文件。
 */
class AlarmPlayer(context: Context) {

    private val ctx = context.applicationContext
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private val nm = ctx.getSystemService(NotificationManager::class.java)
    @Suppress("DEPRECATION")
    private val vibrator = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    private val alarmAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private var track: AudioTrack? = null
    private var savedVolume = -1
    private var savedFilter = NotificationManager.INTERRUPTION_FILTER_UNKNOWN

    fun start() {
        if (track != null) return
        overrideDnd()
        maxAlarmVolume()

        val pcm = buildSiren()
        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        track = AudioTrack(
            alarmAttrs, format, pcm.size * 2, AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE,
        ).apply {
            write(pcm, 0, pcm.size)
            setLoopPoints(0, pcm.size, -1) // 无限循环
            play()
        }
        vibrate()
    }

    fun stop() {
        track?.run {
            try { stop() } catch (_: IllegalStateException) { }
            release()
        }
        track = null
        vibrator.cancel()

        if (savedVolume >= 0) {
            try { audio.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0) } catch (_: SecurityException) { }
            savedVolume = -1
        }
        if (savedFilter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN) {
            if (nm.isNotificationPolicyAccessGranted) nm.setInterruptionFilter(savedFilter)
            savedFilter = NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        }
    }

    private fun overrideDnd() {
        if (!nm.isNotificationPolicyAccessGranted) return
        val current = nm.currentInterruptionFilter
        if (current != NotificationManager.INTERRUPTION_FILTER_ALL) {
            savedFilter = current
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        }
    }

    private fun maxAlarmVolume() {
        savedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        try {
            audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        } catch (_: SecurityException) {
            savedVolume = -1
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        if (!vibrator.hasVibrator()) return
        val pattern = longArrayOf(0, 800, 400)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0), alarmAttrs)
        } else {
            vibrator.vibrate(pattern, 0, alarmAttrs)
        }
    }

    /** 1 秒循环片段：前半 880Hz、后半 1320Hz；各段都是整数个周期，循环衔接处没有爆音 */
    private fun buildSiren(): ShortArray {
        val half = SAMPLE_RATE / 2
        return ShortArray(SAMPLE_RATE) { i ->
            val freq = if (i < half) 880.0 else 1320.0
            val t = (if (i < half) i else i - half).toDouble() / SAMPLE_RATE
            // 轻度削波让声音更“刺耳”、更容易被听到
            val v = (sin(2 * PI * freq * t) * 1.6).coerceIn(-1.0, 1.0)
            (v * Short.MAX_VALUE * 0.95).toInt().toShort()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
    }
}
