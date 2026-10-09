package com.example.reminder.findphone

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.CompoundButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.reminder.R
import com.example.reminder.databinding.ActivityFindPhoneBinding

/** 查找手机：开关服务、显示访问地址与 PIN、权限引导、测试响铃 */
class FindPhoneActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFindPhoneBinding

    private val onServiceToggle = CompoundButton.OnCheckedChangeListener { _, on ->
        FindPhoneConfig.setEnabled(this, on)
        if (on) FindPhoneService.start(this)
        else if (FindPhoneService.isRunning) FindPhoneService.send(this, FindPhoneService.ACTION_STOP)
        refreshLater()
    }

    private val onKeepAwakeToggle = CompoundButton.OnCheckedChangeListener { _, on ->
        FindPhoneConfig.setKeepAwake(this, on)
        // 重启服务使设置生效
        if (FindPhoneService.isRunning) {
            FindPhoneService.send(this, FindPhoneService.ACTION_STOP)
            binding.root.postDelayed({ FindPhoneService.start(this) }, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFindPhoneBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.fp_title)

        binding.resetPin.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.fp_reset_pin_confirm)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    FindPhoneConfig.resetPin(this)
                    refresh()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        binding.testRing.setOnClickListener {
            if (!FindPhoneService.isRunning) {
                Toast.makeText(this, R.string.fp_enable_first, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            FindPhoneService.send(
                this,
                if (FindPhoneService.isRinging) FindPhoneService.ACTION_SILENCE else FindPhoneService.ACTION_RING,
            )
            refreshLater()
        }

        binding.dndButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
        binding.batteryButton.setOnClickListener { requestIgnoreBatteryOptimizations() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun refreshLater() = binding.root.postDelayed({ refresh() }, 400)

    private fun refresh() {
        val enabled = FindPhoneConfig.isEnabled(this)
        // 先移除监听再设置状态，避免程序设置状态时触发回调
        binding.serviceSwitch.setOnCheckedChangeListener(null)
        binding.serviceSwitch.isChecked = enabled
        binding.serviceSwitch.setOnCheckedChangeListener(onServiceToggle)
        binding.keepAwakeSwitch.setOnCheckedChangeListener(null)
        binding.keepAwakeSwitch.isChecked = FindPhoneConfig.keepAwake(this)
        binding.keepAwakeSwitch.setOnCheckedChangeListener(onKeepAwakeToggle)

        val urls = FindPhoneConfig.urls()
        binding.address.text = when {
            !enabled -> getString(R.string.fp_disabled_hint)
            urls.isEmpty() -> getString(R.string.fp_no_network)
            else -> urls.joinToString("\n")
        }
        binding.pin.text = FindPhoneConfig.pin(this)
        binding.testRing.setText(if (FindPhoneService.isRinging) R.string.fp_stop_ring else R.string.fp_test_ring)

        val dndGranted = getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
        binding.dndStatus.setText(if (dndGranted) R.string.fp_granted else R.string.fp_not_granted)
        binding.dndButton.visibility = if (dndGranted) View.GONE else View.VISIBLE

        val batteryOk = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        binding.batteryStatus.setText(if (batteryOk) R.string.fp_granted else R.string.fp_not_granted)
        binding.batteryButton.visibility = if (batteryOk) View.GONE else View.VISIBLE
    }

    @SuppressLint("BatteryLife") // 查找手机需要常驻后台，属于合理用途
    private fun requestIgnoreBatteryOptimizations() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
