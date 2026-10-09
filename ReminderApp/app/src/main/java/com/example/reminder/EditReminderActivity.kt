package com.example.reminder

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.reminder.ai.AiConfig
import com.example.reminder.ai.AiException
import com.example.reminder.ai.AiParser
import com.example.reminder.ai.AiSettingsActivity
import com.example.reminder.ai.ParsedReminder
import com.example.reminder.databinding.ActivityEditReminderBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 新建 / 编辑提醒。
 *
 * 新建时顶部有“智能识别”卡片：
 * - 打开页面时自动读取剪贴板（或从其它 App“分享”进来的文字）填入文本框；
 * - 已配置大模型且文本像一条带时间的通知时，自动识别；
 * - 识别结果填入下方表单，由用户确认后保存。
 */
class EditReminderActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "id"
        private const val MIN_CLIP_LENGTH = 8
        private const val CLIP_MAX_AGE_MS = 15 * 60_000L
        /** 没说时间的事项默认多久后提醒 */
        private const val DEFAULT_DELAY_MS = 60 * 60_000L
    }

    private enum class StatusKind { INFO, OK, ERROR, LINK }

    private lateinit var binding: ActivityEditReminderBinding
    private val db by lazy { ReminderDb.get(this) }
    private var editing: Reminder? = null
    private val cal: Calendar = Calendar.getInstance()

    /** 识别出的事件时间（如就诊时间）；提醒时间 = 事件时间 - 提前量 */
    private var eventTime: Long? = null
    /** 识别所用文本的哈希，保存成功后记下，避免下次自动粘贴同一条 */
    private var recognizedTextHash = 0
    private var clipboardChecked = false
    private var recognizeJob: Job? = null
    private var statusKind: StatusKind? = null

    private val repeatChips by lazy {
        mapOf(
            RepeatType.NONE to R.id.chipRepeatNone,
            RepeatType.DAILY to R.id.chipRepeatDaily,
            RepeatType.WEEKLY to R.id.chipRepeatWeekly,
            RepeatType.MONTHLY to R.id.chipRepeatMonthly,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditReminderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val id = intent.getLongExtra(EXTRA_ID, 0L)
        editing = if (id > 0) db.getById(id) else null

        editing?.let { r ->
            title = getString(R.string.edit_reminder)
            binding.smartCard.visibility = View.GONE
            binding.titleInput.setText(r.title)
            binding.noteInput.setText(r.note)
            binding.repeatGroup.check(repeatChips.getValue(r.repeat))
            binding.typeGroup.check(if (r.isTodo) R.id.chipTypeTodo else R.id.chipTypeEvent)
            cal.timeInMillis = r.triggerAt
            binding.delete.visibility = View.VISIBLE
        } ?: run {
            title = getString(R.string.new_reminder)
            // 默认：一小时后的整点
            cal.add(Calendar.HOUR_OF_DAY, 1)
            cal.set(Calendar.MINUTE, 0)
        }
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        updateDateTimeLabels()

        binding.dateButton.setOnClickListener { pickDate() }
        binding.timeButton.setOnClickListener { pickTime() }
        binding.save.setOnClickListener { save() }
        binding.delete.setOnClickListener { delete() }

        binding.pasteButton.setOnClickListener { pasteManually() }
        binding.recognizeButton.setOnClickListener { recognize() }
        binding.leadGroup.setOnCheckedStateChangeListener { _, _ -> applyLead() }

        // 从短信 / 邮件 App “分享”进来的文字：直接使用，无需读剪贴板
        if (editing == null && intent.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let {
                clipboardChecked = true
                fillSmartText(it.trim(), getString(R.string.ai_from_share))
                autoRecognizeIfSuitable()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 首次进入，或从设置页配置完返回后，刷新提示
        if (editing == null && (statusKind == null || statusKind == StatusKind.LINK)) showIdleHint()
    }

    /**
     * Android 10+ 只有窗口获得焦点后才能读取剪贴板，因此在这里而不是 onCreate 中读取。
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || clipboardChecked || editing != null) return
        clipboardChecked = true

        val text = readClipboard(recentOnly = true)
        if (text == null || text.length < MIN_CLIP_LENGTH || text.hashCode() == AiConfig.lastClipHash(this)) {
            // 没有可用的剪贴板内容：聚焦识别框并弹出键盘，方便直接用输入法的语音输入
            focusSmartInput()
            return
        }
        fillSmartText(text, getString(R.string.ai_auto_pasted))
        autoRecognizeIfSuitable()
    }

    private fun focusSmartInput() {
        binding.smartInput.requestFocus()
        binding.smartInput.post {
            getSystemService(InputMethodManager::class.java)
                .showSoftInput(binding.smartInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // ---------------- 智能识别 ----------------

    /**
     * 读取剪贴板文本；系统标记为敏感（如密码管理器复制的密码）时不读取。
     * recentOnly：只接受最近 [CLIP_MAX_AGE_MS] 内复制的内容（Android 8.0+ 才能判断），
     * 避免把很久以前复制的东西自动粘贴进来。
     */
    private fun readClipboard(recentOnly: Boolean = false): String? {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip ?: return null
        if (clip.itemCount == 0) return null
        if (clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true) return null
        if (recentOnly && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 兼容两种时间基准：数值很大说明是系统时间（currentTimeMillis），否则是开机以来的时间
            val ts = clip.description.timestamp
            val now = if (ts > 1_000_000_000_000L) System.currentTimeMillis() else SystemClock.elapsedRealtime()
            if (ts <= 0 || now - ts > CLIP_MAX_AGE_MS) return null
        }
        return clip.getItemAt(0).coerceToText(this)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun pasteManually() {
        val text = readClipboard()
        if (text == null) {
            setStatus(getString(R.string.ai_clipboard_empty), StatusKind.ERROR)
            return
        }
        fillSmartText(text, getString(R.string.ai_pasted))
    }

    private fun fillSmartText(text: String, status: String) {
        binding.smartInput.setText(text)
        setStatus(status, StatusKind.INFO)
    }

    /**
     * 自动识别前的检查：看起来像一条带时间的通知才发给大模型
     * （含数字或中文数字，且含中文或空格——排除密码、链接等单个词）。
     */
    private fun autoRecognizeIfSuitable() {
        val text = binding.smartInput.text.toString()
        val hasNumber = Regex("[0-9０-９一二三四五六七八九十]").containsMatchIn(text)
        val looksLikeSentence = Regex("[\\u4e00-\\u9fa5]|\\s").containsMatchIn(text)
        when {
            !AiConfig.isConfigured(this) -> showIdleHint()
            hasNumber && looksLikeSentence -> recognize()
            else -> setStatus(getString(R.string.ai_not_auto), StatusKind.INFO)
        }
    }

    private fun showIdleHint() {
        if (AiConfig.isConfigured(this)) {
            setStatus(getString(R.string.ai_idle_hint), StatusKind.INFO)
        } else {
            setStatus(getString(R.string.ai_not_configured), StatusKind.LINK)
        }
    }

    private fun recognize() {
        val text = binding.smartInput.text.toString().trim()
        if (text.isEmpty()) {
            setStatus(getString(R.string.ai_input_empty), StatusKind.ERROR)
            return
        }
        if (!AiConfig.isConfigured(this)) {
            setStatus(getString(R.string.ai_not_configured), StatusKind.LINK)
            return
        }
        recognizeJob?.cancel()
        setLoading(true)
        setStatus(getString(R.string.ai_recognizing), StatusKind.INFO)
        recognizeJob = lifecycleScope.launch {
            try {
                applyParsed(AiParser.parse(this@EditReminderActivity, text))
                recognizedTextHash = text.hashCode()
            } catch (e: AiException) {
                setStatus(e.message.orEmpty(), StatusKind.ERROR)
            } finally {
                setLoading(false)
            }
        }
    }

    private fun applyParsed(p: ParsedReminder) {
        val now = System.currentTimeMillis()
        binding.titleInput.setText(p.title)
        binding.repeatGroup.check(repeatChips.getValue(p.repeat))
        binding.typeGroup.check(if (p.isTodo) R.id.chipTypeTodo else R.id.chipTypeEvent)

        val time = p.time
        if (time == null) {
            // 没说时间：默认 1 小时后（取整到 5 分钟），请用户自行调整
            hideLead()
            binding.noteInput.setText(p.note)
            setReminderTime(roundUpTo5Min(now + DEFAULT_DELAY_MS))
            setStatus(getString(R.string.ai_done_no_time), StatusKind.OK)
            return
        }

        if (p.isTodo) {
            // 待办：识别到的就是希望被提醒的时间，直接使用
            hideLead()
            binding.noteInput.setText(p.note)
            setReminderTime(time)
        } else {
            // 日程：识别到的是事件时间，提醒时间 = 事件时间 - 提前量（默认 1 小时）
            val timeLine = getString(R.string.ai_note_time, DateUtil.withWeekday(time))
            binding.noteInput.setText(if (p.note.isBlank()) timeLine else "$timeLine\n${p.note}")
            eventTime = time
            binding.leadSection.visibility = View.VISIBLE
            binding.eventTimeText.text = getString(R.string.ai_event_time, DateUtil.withWeekday(time))
            // check() 对已选中的标签不会回调，所以再手动调用一次
            binding.leadGroup.check(R.id.chipLead1h)
            applyLead()
        }

        if (time <= now && p.repeat == RepeatType.NONE) {
            setStatus(getString(R.string.ai_event_passed), StatusKind.ERROR)
        } else {
            setStatus(getString(R.string.ai_done), StatusKind.OK)
        }
    }

    private fun hideLead() {
        eventTime = null
        binding.leadSection.visibility = View.GONE
    }

    private fun setReminderTime(t: Long) {
        cal.timeInMillis = t
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        updateDateTimeLabels()
    }

    private fun roundUpTo5Min(t: Long): Long {
        val step = 5 * 60_000L
        return (t + step - 1) / step * step
    }

    /** 根据选中的提前量计算提醒时间；提前后已过去但事件还没到时，自动改为“准时” */
    private fun applyLead() {
        val ev = eventTime ?: return
        val leadMinutes = when (binding.leadGroup.checkedChipId) {
            R.id.chipLead0 -> 0
            R.id.chipLead30 -> 30
            R.id.chipLead1d -> 24 * 60
            else -> 60
        }
        val now = System.currentTimeMillis()
        val t = ev - leadMinutes * 60_000L
        if (leadMinutes > 0 && t <= now && ev > now) {
            binding.leadGroup.check(R.id.chipLead0) // 会再次回调 applyLead
            return
        }
        setReminderTime(t)
    }

    private fun setLoading(loading: Boolean) {
        binding.aiProgress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.recognizeButton.isEnabled = !loading
    }

    private fun setStatus(text: String, kind: StatusKind) {
        statusKind = kind
        binding.aiStatus.text = text
        val color = when (kind) {
            StatusKind.INFO -> R.color.text_secondary
            StatusKind.OK -> R.color.success
            StatusKind.ERROR -> R.color.error
            StatusKind.LINK -> R.color.accent
        }
        binding.aiStatus.setTextColor(ContextCompat.getColor(this, color))
        if (kind == StatusKind.LINK) {
            binding.aiStatus.setOnClickListener { startActivity(Intent(this, AiSettingsActivity::class.java)) }
        } else {
            binding.aiStatus.setOnClickListener(null)
            binding.aiStatus.isClickable = false
        }
    }

    // ---------------- 表单 ----------------

    private fun pickDate() {
        DatePickerDialog(this, { _, y, m, d ->
            cal.set(y, m, d)
            updateDateTimeLabels()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun pickTime() {
        TimePickerDialog(this, { _, h, min ->
            cal.set(Calendar.HOUR_OF_DAY, h)
            cal.set(Calendar.MINUTE, min)
            updateDateTimeLabels()
        }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
    }

    private fun updateDateTimeLabels() {
        binding.dateButton.text = SimpleDateFormat("yyyy-MM-dd EEE", Locale.getDefault()).format(cal.time)
        binding.timeButton.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(cal.time)
    }

    private fun selectedRepeat(): RepeatType =
        repeatChips.entries.firstOrNull { it.value == binding.repeatGroup.checkedChipId }?.key ?: RepeatType.NONE

    private fun save() {
        val titleText = binding.titleInput.text.toString().trim()
        if (titleText.isEmpty()) {
            binding.titleLayout.error = getString(R.string.title_required)
            return
        }
        binding.titleLayout.error = null

        var r = Reminder(
            id = editing?.id ?: 0,
            title = titleText,
            note = binding.noteInput.text.toString().trim(),
            triggerAt = cal.timeInMillis,
            repeat = selectedRepeat(),
            enabled = true,
            isTodo = binding.typeGroup.checkedChipId == R.id.chipTypeTodo,
            doneAt = 0, // 保存即重新激活（编辑已完成的待办并保存，视为重新打开）
        ).rolledForward()

        if (r.triggerAt <= System.currentTimeMillis()) {
            Toast.makeText(this, R.string.time_passed, Toast.LENGTH_SHORT).show()
            return
        }

        r = db.save(r)
        AlarmScheduler.schedule(this, r)
        if (recognizedTextHash != 0) AiConfig.setLastClipHash(this, recognizedTextHash)
        Toast.makeText(this, getString(R.string.saved, r.formattedTime()), Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun delete() {
        val r = editing ?: return
        AlarmScheduler.cancel(this, r.id)
        db.delete(r.id)
        finish()
    }
}
