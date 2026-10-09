package com.example.reminder.findphone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.reminder.databinding.ActivityRingingBinding

/** 响铃时的全屏界面（可在锁屏上显示），一个大按钮“找到了” */
class RingingActivity : AppCompatActivity() {

    private val stoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        val binding = ActivityRingingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.found.setOnClickListener {
            FindPhoneService.send(this, FindPhoneService.ACTION_SILENCE)
            finish()
        }

        ContextCompat.registerReceiver(
            this, stoppedReceiver,
            IntentFilter(FindPhoneService.ACTION_RINGING_STOPPED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onResume() {
        super.onResume()
        // 网页端或超时已停止响铃
        if (!FindPhoneService.isRinging) finish()
    }

    override fun onDestroy() {
        unregisterReceiver(stoppedReceiver)
        super.onDestroy()
    }
}
