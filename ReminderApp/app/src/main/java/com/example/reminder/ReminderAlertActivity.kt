package com.example.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.reminder.databinding.ActivityReminderAlertBinding

/**
 * 到点提醒的全屏卡片（像闹钟一样）：锁屏时由全屏通知唤起并点亮屏幕。
 * 按钮与通知上的按钮效果相同，均交给 ReminderReceiver 处理。
 */
class ReminderAlertActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "id"
    }

    private lateinit var binding: ActivityReminderAlertBinding
    private var reminderId = -1L

    private val stoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // 响铃已结束（超时 / 在通知上处理了），且不是又换了一条新提醒在响
            if (ReminderRingService.ringingId != reminderId) finish()
        }
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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityReminderAlertBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ContextCompat.registerReceiver(
            this, stoppedReceiver,
            IntentFilter(ReminderRingService.ACTION_RING_STOPPED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        bind(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        bind(intent)
    }

    override fun onResume() {
        super.onResume()
        if (ReminderRingService.ringingId != reminderId) finish()
    }

    override fun onDestroy() {
        unregisterReceiver(stoppedReceiver)
        super.onDestroy()
    }

    private fun bind(intent: Intent) {
        reminderId = intent.getLongExtra(EXTRA_ID, -1L)
        val r = ReminderDb.get(this).getById(reminderId)
        if (r == null) {
            finish()
            return
        }
        binding.alertTime.text = DateUtil.withWeekday(System.currentTimeMillis())
        binding.alertTitle.text = r.title
        binding.alertNote.text = r.note
        binding.alertDone.setText(if (r.isTodo) R.string.todo_complete else R.string.done)
        binding.alertDone.setOnClickListener { act(ReminderReceiver.ACTION_DONE) }
        binding.alertSnooze.setOnClickListener { act(ReminderReceiver.ACTION_SNOOZE) }
    }

    private fun act(action: String) {
        sendBroadcast(
            Intent(this, ReminderReceiver::class.java)
                .setAction(action)
                .putExtra(ReminderReceiver.EXTRA_ID, reminderId)
        )
        finish()
    }
}
