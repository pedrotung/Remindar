package com.example.reminder.findphone

import android.content.Context
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

/** 查找手机功能的配置（SharedPreferences）与本机地址查询 */
object FindPhoneConfig {

    const val PORT = 8080

    private const val PREFS = "find_phone"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PIN = "pin"
    private const val KEY_KEEP_AWAKE = "keep_awake"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()

    /** 保持 CPU 唤醒：熄屏很久后也能立即响应，但更耗电 */
    fun keepAwake(context: Context) = prefs(context).getBoolean(KEY_KEEP_AWAKE, false)

    fun setKeepAwake(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean(KEY_KEEP_AWAKE, on).apply()

    /** 6 位数字 PIN，首次使用时随机生成；防止同一 Wi‑Fi 下的其他人随意触发 */
    fun pin(context: Context): String {
        val p = prefs(context)
        return p.getString(KEY_PIN, null) ?: newPin().also { p.edit().putString(KEY_PIN, it).apply() }
    }

    fun resetPin(context: Context): String =
        newPin().also { prefs(context).edit().putString(KEY_PIN, it).apply() }

    private fun newPin() = "%06d".format(SecureRandom().nextInt(1_000_000))

    /** 本机在局域网（Wi‑Fi / 热点）中的 IPv4 地址 */
    fun localAddresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
    } catch (e: Exception) {
        emptyList()
    }

    fun urls(): List<String> = localAddresses().map { "http://$it:$PORT" }

    /**
     * 给家人用的链接：地址 + PIN（放在 # 后面，不会作为请求发送；网页读取后自动填入并保存）。
     * 未连接局域网时返回 null。
     */
    fun familyUrl(context: Context): String? = urls().firstOrNull()?.let { "$it/#pin=${pin(context)}" }
}
