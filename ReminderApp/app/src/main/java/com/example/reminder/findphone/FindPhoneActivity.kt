package com.example.reminder.findphone

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.PersistableBundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.CompoundButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.reminder.R
import com.example.reminder.databinding.ActivityFindPhoneBinding
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

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
        bindQrCode(if (enabled) FindPhoneConfig.familyUrl(this) else null)
        binding.testRing.setText(if (FindPhoneService.isRinging) R.string.fp_stop_ring else R.string.fp_test_ring)

        val dndGranted = getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
        binding.dndStatus.setText(if (dndGranted) R.string.fp_granted else R.string.fp_not_granted)
        binding.dndButton.visibility = if (dndGranted) View.GONE else View.VISIBLE

        val batteryOk = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        binding.batteryStatus.setText(if (batteryOk) R.string.fp_granted else R.string.fp_not_granted)
        binding.batteryButton.visibility = if (batteryOk) View.GONE else View.VISIBLE
    }

    /** 显示家人扫码用的二维码；服务未开启或未连接局域网时隐藏 */
    private fun bindQrCode(url: String?) {
        if (url == null) {
            binding.qrSection.visibility = View.GONE
            return
        }
        binding.qrSection.visibility = View.VISIBLE
        binding.qrImage.setImageBitmap(qrBitmap(url, 600))
        binding.copyLink.setOnClickListener {
            val clip = ClipData.newPlainText(getString(R.string.fp_qr_title), url)
            // 链接里含 PIN，标记为敏感内容（Android 13+ 剪贴板预览会隐藏）
            clip.description.extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
            getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
            Toast.makeText(this, R.string.fp_link_copied, Toast.LENGTH_SHORT).show()
        }
        binding.shareLink.setOnClickListener {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, getString(R.string.fp_share_text, url)),
                    getString(R.string.fp_share_link),
                )
            )
        }
    }

    /** 白底黑码的二维码图片 */
    private fun qrBitmap(text: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, size, size,
            mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.CHARACTER_SET to "UTF-8"),
        )
        val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).apply {
            setPixels(pixels, 0, size, 0, 0, size, size)
        }
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
