package com.example.reminder.ai

import android.content.Context

/** 大模型接口配置（OpenAI 兼容格式：{baseUrl}/chat/completions） */
object AiConfig {

    /** 预置服务商：名称 → (接口地址, 默认模型) */
    data class Preset(val baseUrl: String, val model: String)

    /** 默认：通义千问（阿里云百炼，国内可直连） */
    val QWEN = Preset("https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus")
    val DEEPSEEK = Preset("https://api.deepseek.com", "deepseek-chat")
    val ZHIPU = Preset("https://open.bigmodel.cn/api/paas/v4", "glm-4-flash")
    private val DEFAULT = QWEN

    private const val PREFS = "ai"
    private const val KEY_BASE = "base_url"
    private const val KEY_MODEL = "model"
    private const val KEY_API_KEY = "api_key_enc"
    private const val KEY_LAST_CLIP = "last_clip_hash"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun baseUrl(context: Context): String = prefs(context).getString(KEY_BASE, null) ?: DEFAULT.baseUrl

    fun model(context: Context): String = prefs(context).getString(KEY_MODEL, null) ?: DEFAULT.model

    fun apiKey(context: Context): String? =
        prefs(context).getString(KEY_API_KEY, null)?.let { SecretStore.decrypt(it) }

    fun isConfigured(context: Context) = !apiKey(context).isNullOrBlank()

    fun save(context: Context, baseUrl: String, model: String, apiKey: String) {
        prefs(context).edit().apply {
            putString(KEY_BASE, baseUrl.trim().trimEnd('/'))
            putString(KEY_MODEL, model.trim())
            if (apiKey.isBlank()) remove(KEY_API_KEY) else putString(KEY_API_KEY, SecretStore.encrypt(apiKey.trim()))
        }.apply()
    }

    /**
     * 最近一次“识别并保存成功”的剪贴板文本的哈希。
     * 新建提醒时若剪贴板内容与之相同则不再自动粘贴，避免同一条短信重复建提醒。
     */
    fun lastClipHash(context: Context): Int = prefs(context).getInt(KEY_LAST_CLIP, 0)

    fun setLastClipHash(context: Context, hash: Int) = prefs(context).edit().putInt(KEY_LAST_CLIP, hash).apply()
}
