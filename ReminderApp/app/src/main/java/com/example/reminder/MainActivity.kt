package com.example.reminder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.reminder.ai.AiSettingsActivity
import com.example.reminder.databinding.ActivityMainBinding
import com.example.reminder.findphone.FindPhoneActivity
import com.example.reminder.findphone.FindPhoneConfig
import com.example.reminder.findphone.FindPhoneService
import com.example.reminder.findphone.RingingActivity

class MainActivity : AppCompatActivity() {

    /** 筛选标签：对应列表标题与空列表提示 */
    private enum class Filter(val sectionRes: Int, val emptyRes: Int) {
        ALL(R.string.section_all, R.string.empty_hint),
        TODAY(R.string.section_today, R.string.empty_today),
        TODO(R.string.section_todo, R.string.empty_todo),
        REPEAT(R.string.section_repeat, R.string.empty_repeat),
        DONE(R.string.section_done, R.string.empty_done),
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ReminderAdapter
    private val db by lazy { ReminderDb.get(this) }
    private var filter = Filter.ALL

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) Toast.makeText(this, R.string.notification_denied, Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        NotificationHelper.createChannel(this)
        // 确保“逾期待办每日提醒”已按设置注册（重复调用只会覆盖）
        AlarmScheduler.scheduleOverdueCheck(this)

        adapter = ReminderAdapter(
            onClick = { openEditor(it.id) },
            onLongClick = { confirmDelete(it) },
            onToggle = { r, checked -> toggle(r, checked) },
            onToggleDone = { r, done -> toggleDone(r, done) },
        )
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        binding.fab.setOnClickListener { openEditor(0) }
        binding.menuButton.setOnClickListener { showMenu(it) }
        binding.findPhoneButton.setOnClickListener { openFindPhone() }
        binding.cardPhone.setOnClickListener { openFindPhone() }
        binding.cardTodo.setOnClickListener { binding.filterGroup.check(R.id.chipTodo) }
        binding.filterGroup.setOnCheckedStateChangeListener { _, ids ->
            filter = when (ids.firstOrNull()) {
                R.id.chipToday -> Filter.TODAY
                R.id.chipTodo -> Filter.TODO
                R.id.chipRepeat -> Filter.REPEAT
                R.id.chipDone -> Filter.DONE
                else -> Filter.ALL
            }
            refresh()
        }

        requestPermissionsIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        // 手机正在被“查找”时打开 App，直接显示“找到了”界面
        if (FindPhoneService.isRinging) startActivity(Intent(this, RingingActivity::class.java))
    }

    private fun refresh() {
        val now = System.currentTimeMillis()
        val all = db.getAll()
        // 排序：逾期待办在最前，其次是等待中的提醒，再次是已停用的；同组内按时间
        val active = all.filter { !it.isDone }.sortedWith(
            compareBy<Reminder>({
                when {
                    it.isOverdue(now) -> 0
                    it.enabled -> 1
                    else -> 2
                }
            }, { it.triggerAt })
        )
        val items = when (filter) {
            Filter.ALL -> active
            Filter.TODAY -> active.filter { isForToday(it, now) }
            Filter.TODO -> active.filter { it.isTodo }
            Filter.REPEAT -> active.filter { it.repeat != RepeatType.NONE }
            // 已完成的待办 + 已结束 / 已停用的普通提醒，最近的在前
            Filter.DONE -> all.filter { it.isDone || (!it.isTodo && !it.enabled) }
                .sortedByDescending { if (it.isDone) it.doneAt else it.triggerAt }
        }
        adapter.submitList(items)
        binding.sectionTitle.setText(filter.sectionRes)
        binding.empty.setText(filter.emptyRes)
        binding.empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        bindStats(all, now)
    }

    /** 今天要处理的：今天会响的提醒，以及今天到期或已逾期、尚未完成的待办 */
    private fun isForToday(r: Reminder, now: Long): Boolean {
        if (r.isDone) return false
        if (r.isOverdue(now)) return true
        return (r.enabled || r.isTodo) && DateUtil.isSameDay(r.triggerAt, now)
    }

    private fun bindStats(all: List<Reminder>, now: Long) {
        // 今日待提醒
        binding.statTodayValue.text = bigCount(all.count { isForToday(it, now) })
        val next = all.filter { it.enabled && it.triggerAt > now }.minByOrNull { it.triggerAt }
        binding.statTodaySub.text = next?.let { getString(R.string.stat_next, it.friendlyTime(now), it.title) }
            ?: getString(R.string.stat_no_next)

        // 待办事项
        val todos = all.filter { it.isTodo && !it.isDone }
        val overdue = todos.count { it.isOverdue(now) }
        val dueToday = todos.count { !it.isOverdue(now) && DateUtil.isSameDay(it.triggerAt, now) }
        binding.statTodoValue.text = bigCount(todos.size)
        binding.statTodoSub.text = when {
            overdue > 0 -> getString(R.string.stat_todo_overdue, overdue)
            dueToday > 0 -> getString(R.string.stat_todo_today, dueToday)
            todos.isEmpty() -> getString(R.string.stat_todo_none)
            else -> getString(R.string.stat_todo_ok)
        }
        binding.statTodoSub.setTextColor(
            ContextCompat.getColor(this, if (overdue > 0) R.color.error else R.color.text_secondary)
        )

        // 查找手机
        val fpOn = FindPhoneConfig.isEnabled(this)
        binding.statPhoneValue.setText(if (fpOn) R.string.fp_on else R.string.fp_off)
        binding.statPhoneSub.text = when {
            !fpOn -> getString(R.string.fp_tap_to_enable)
            else -> FindPhoneConfig.localAddresses().firstOrNull() ?: getString(R.string.fp_no_wifi_short)
        }
        binding.findPhoneDot.visibility = if (fpOn && FindPhoneService.isRunning) View.VISIBLE else View.GONE
    }

    /** “12 项”：数字大、单位小 */
    private fun bigCount(n: Int): CharSequence {
        val unit = getString(R.string.unit_items)
        return SpannableString("$n $unit").apply {
            setSpan(RelativeSizeSpan(0.55f), length - unit.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menuInflater.inflate(R.menu.main_menu, menu)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.find_phone -> openFindPhone()
                    R.id.ai_settings -> startActivity(Intent(this@MainActivity, AiSettingsActivity::class.java))
                    R.id.settings -> startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                    R.id.notification_settings -> openNotificationSettings()
                }
                true
            }
            show()
        }
    }

    private fun openFindPhone() = startActivity(Intent(this, FindPhoneActivity::class.java))

    private fun openNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        }
        startActivity(intent)
    }

    private fun openEditor(id: Long) {
        startActivity(Intent(this, EditReminderActivity::class.java).putExtra(EditReminderActivity.EXTRA_ID, id))
    }

    private fun toggle(r: Reminder, enabled: Boolean) {
        var updated = r.copy(enabled = enabled)
        if (enabled) {
            updated = updated.rolledForward()
            if (updated.triggerAt <= System.currentTimeMillis()) {
                Toast.makeText(this, R.string.time_passed, Toast.LENGTH_SHORT).show()
                openEditor(r.id)
                refresh()
                return
            }
        }
        db.save(updated)
        if (enabled) AlarmScheduler.schedule(this, updated) else AlarmScheduler.cancel(this, r.id)
        binding.list.post { refresh() }
    }

    private fun toggleDone(r: Reminder, done: Boolean) {
        if (done) {
            val updated = TodoHelper.complete(this, r)
            // 重复待办完成后会跳到下一次，提示一下
            if (r.repeat != RepeatType.NONE) {
                Toast.makeText(this, getString(R.string.todo_next, updated.friendlyTime()), Toast.LENGTH_SHORT).show()
            }
        } else {
            TodoHelper.reopen(this, r)
        }
        binding.list.post { refresh() }
    }

    private fun confirmDelete(r: Reminder) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete)
            .setMessage(getString(R.string.delete_confirm, r.title))
            .setPositiveButton(R.string.delete) { _, _ ->
                AlarmScheduler.cancel(this, r.id)
                db.delete(r.id)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Android 12/12L 需要用户在设置中手动允许精确闹钟
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !AlarmScheduler.canScheduleExact(this)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.exact_alarm_title)
                .setMessage(R.string.exact_alarm_msg)
                .setPositiveButton(R.string.go_settings) { _, _ ->
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))
                    )
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }
}
