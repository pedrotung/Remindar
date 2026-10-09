package com.example.reminder.ai

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.example.reminder.DateUtil
import com.example.reminder.R
import com.example.reminder.databinding.ActivityAiSettingsBinding
import kotlinx.coroutines.launch

/** 大模型接口设置：选择服务商、填写 API Key、测试识别 */
class AiSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAiSettingsBinding

    /** 是否正在由代码填充输入框（此时不应把预置标签切换为“自定义”） */
    private var filling = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAiSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.ai_settings_title)

        // 载入已保存的配置
        filling = true
        binding.baseUrlInput.setText(AiConfig.baseUrl(this))
        binding.modelInput.setText(AiConfig.model(this))
        binding.apiKeyInput.setText(AiConfig.apiKey(this).orEmpty())
        filling = false
        binding.presetGroup.check(presetChipFor(AiConfig.baseUrl(this)))

        binding.presetGroup.setOnCheckedStateChangeListener { _, ids ->
            val preset = when (ids.firstOrNull()) {
                R.id.chipDeepseek -> AiConfig.DEEPSEEK
                R.id.chipZhipu -> AiConfig.ZHIPU
                R.id.chipQwen -> AiConfig.QWEN
                else -> null
            } ?: return@setOnCheckedStateChangeListener
            filling = true
            binding.baseUrlInput.setText(preset.baseUrl)
            binding.modelInput.setText(preset.model)
            filling = false
        }
        // 手动修改接口地址时，标签自动切到对应服务商或“自定义”
        binding.baseUrlInput.doAfterTextChanged {
            if (!filling) binding.presetGroup.check(presetChipFor(it.toString().trim()))
        }

        binding.saveButton.setOnClickListener {
            if (saveConfig()) {
                Toast.makeText(this, R.string.ai_saved, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        binding.testButton.setOnClickListener { if (saveConfig()) runTest() }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun presetChipFor(baseUrl: String) = when (baseUrl.trimEnd('/')) {
        AiConfig.DEEPSEEK.baseUrl -> R.id.chipDeepseek
        AiConfig.ZHIPU.baseUrl -> R.id.chipZhipu
        AiConfig.QWEN.baseUrl -> R.id.chipQwen
        else -> R.id.chipCustom
    }

    private fun saveConfig(): Boolean {
        val url = binding.baseUrlInput.text.toString().trim()
        val model = binding.modelInput.text.toString().trim()
        val key = binding.apiKeyInput.text.toString().trim()
        if (!url.startsWith("https://")) {
            showResult(getString(R.string.ai_url_invalid), ok = false)
            return false
        }
        if (model.isEmpty()) {
            showResult(getString(R.string.ai_model_required), ok = false)
            return false
        }
        if (key.isEmpty()) {
            binding.apiKeyLayout.error = getString(R.string.ai_key_required)
            return false
        }
        binding.apiKeyLayout.error = null
        AiConfig.save(this, url, model, key)
        return true
    }

    /** 用一条示例短信测试整个识别流程 */
    private fun runTest() {
        binding.testProgress.visibility = View.VISIBLE
        binding.testButton.isEnabled = false
        showResult(getString(R.string.ai_testing), ok = null)
        lifecycleScope.launch {
            try {
                val r = AiParser.parse(this@AiSettingsActivity, getString(R.string.ai_test_sample))
                val time = r.time?.let { DateUtil.withWeekday(it) } ?: getString(R.string.ai_no_time)
                showResult(getString(R.string.ai_test_ok, r.title, time), ok = true)
            } catch (e: AiException) {
                showResult(e.message.orEmpty(), ok = false)
            } finally {
                binding.testProgress.visibility = View.GONE
                binding.testButton.isEnabled = true
            }
        }
    }

    private fun showResult(text: String, ok: Boolean?) {
        binding.testResult.text = text
        val color = when (ok) {
            true -> R.color.success
            false -> R.color.error
            null -> R.color.text_secondary
        }
        binding.testResult.setTextColor(ContextCompat.getColor(this, color))
    }
}
