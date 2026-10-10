package com.example.reminder

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log

/**
 * 到点提醒的铃声播放：用户设置的闹钟铃声，循环播放 + 振动。
 *
 * 以 USAGE_ALARM（闹钟音频流）播放，因此手机静音 / 振动模式下也会响；
 * 音量使用用户当前的闹钟音量（不强制调大），也不干预勿扰模式——这两点与“查找手机”不同。
 */
class ReminderTonePlayer(context: Context) {

    private val ctx = context.applicationContext
    private val audio = ctx.getSystemService(AudioManager::class.java)
    @Suppress("DEPRECATION")
    private val vibrator = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null

    fun start() {
        stop()
        requestFocus()
        // 依次尝试：用户的闹钟铃声 → 系统默认闹钟 → 来电铃声 → 通知音
        for (uri in candidateUris()) {
            try {
                player = MediaPlayer().apply {
                    setAudioAttributes(attrs)
                    setWakeMode(ctx, PowerManager.PARTIAL_WAKE_LOCK) // 熄屏时也能完整播放
                    setDataSource(ctx, uri)
                    isLooping = true
                    prepare()
                    start()
                }
                break
            } catch (e: Exception) {
                Log.w(TAG, "无法播放 $uri", e)
                player?.release()
                player = null
            }
        }
        vibrate()
    }

    fun stop() {
        player?.run {
            try { stop() } catch (_: IllegalStateException) { }
            release()
        }
        player = null
        vibrator.cancel()
        abandonFocus()
    }

    private fun candidateUris(): List<Uri> = listOfNotNull(
        RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_ALARM),
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
    ).distinct()

    /** 暂时让音乐等其它声音暂停 */
    private fun requestFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .build()
                .also { audio.requestAudioFocus(it) }
        } else {
            @Suppress("DEPRECATION")
            audio.requestAudioFocus(null, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audio.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audio.abandonAudioFocus(null)
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        if (!vibrator.hasVibrator()) return
        val pattern = longArrayOf(0, 800, 600)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0), attrs)
        } else {
            vibrator.vibrate(pattern, 0, attrs)
        }
    }

    private companion object {
        const val TAG = "ReminderTonePlayer"
    }
}
