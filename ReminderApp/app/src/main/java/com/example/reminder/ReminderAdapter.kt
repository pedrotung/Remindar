package com.example.reminder

import android.content.res.ColorStateList
import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.reminder.databinding.ItemReminderBinding

class ReminderAdapter(
    private val onClick: (Reminder) -> Unit,
    private val onLongClick: (Reminder) -> Unit,
    private val onToggle: (Reminder, Boolean) -> Unit,
    private val onToggleDone: (Reminder, Boolean) -> Unit,
) : ListAdapter<Reminder, ReminderAdapter.VH>(Diff) {

    class VH(val b: ItemReminderBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemReminderBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = getItem(position)
        if (r.isTodo) bindTodo(holder.b, r) else bindReminder(holder.b, r)
        holder.b.root.setOnClickListener { onClick(r) }
        holder.b.root.setOnLongClickListener { onLongClick(r); true }
    }

    /** 普通提醒：首字图标 + “09:30  标题” + 启用开关 */
    private fun bindReminder(b: ItemReminderBinding, r: Reminder) = with(b) {
        val ctx = root.context
        avatar.visibility = View.VISIBLE
        todoCheck.visibility = View.GONE
        avatar.text = r.title.trim().firstOrNull()?.toString() ?: "?"
        avatar.backgroundTintList =
            ColorStateList.valueOf(AVATAR_COLORS[Math.floorMod(r.title.hashCode(), AVATAR_COLORS.size)])

        headline.text = "${r.friendlyTime()}  ${r.title}"
        headline.paintFlags = headline.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        supporting.text = joinParts(
            if (!r.enabled) "已停用" else null,
            if (r.repeat != RepeatType.NONE) r.repeat.label else null,
            firstLine(r.note),
        ).ifEmpty { "单次提醒" }
        supporting.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
        root.alpha = if (r.enabled) 1f else 0.5f

        enabledSwitch.visibility = View.VISIBLE
        enabledSwitch.setOnCheckedChangeListener(null)
        enabledSwitch.isChecked = r.enabled
        enabledSwitch.setOnCheckedChangeListener { _, checked -> onToggle(r, checked) }
    }

    /** 待办：完成圆圈 + 标题（完成后加删除线）+ 提醒时间（逾期标红） */
    private fun bindTodo(b: ItemReminderBinding, r: Reminder) = with(b) {
        val ctx = root.context
        val now = System.currentTimeMillis()
        avatar.visibility = View.GONE
        todoCheck.visibility = View.VISIBLE
        todoCheck.setImageResource(if (r.isDone) R.drawable.ic_check_circle else R.drawable.ic_circle_outline)
        todoCheck.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(ctx, if (r.isDone) R.color.success else R.color.text_secondary)
        )
        todoCheck.contentDescription = ctx.getString(if (r.isDone) R.string.todo_reopen else R.string.todo_complete)
        todoCheck.setOnClickListener { onToggleDone(r, !r.isDone) }

        headline.text = r.title
        headline.paintFlags = if (r.isDone) {
            headline.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        } else {
            headline.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        }

        val overdue = r.isOverdue(now)
        val timeLabel = when {
            r.isDone -> "完成于 ${DateUtil.friendly(r.doneAt, now)}"
            overdue -> "已逾期 · ${r.friendlyTime(now)}"
            else -> r.friendlyTime(now)
        }
        supporting.text = joinParts(
            timeLabel,
            if (r.repeat != RepeatType.NONE) r.repeat.label else null,
            firstLine(r.note),
        )
        supporting.setTextColor(ContextCompat.getColor(ctx, if (overdue) R.color.error else R.color.text_secondary))
        root.alpha = if (r.isDone) 0.5f else 1f

        // 待办以勾选完成为主，不显示闹钟开关
        enabledSwitch.setOnCheckedChangeListener(null)
        enabledSwitch.visibility = View.GONE
    }

    private fun joinParts(vararg parts: String?) = parts.filterNotNull().filter { it.isNotEmpty() }.joinToString(" · ")

    private fun firstLine(note: String) = note.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }

    private object Diff : DiffUtil.ItemCallback<Reminder>() {
        override fun areItemsTheSame(a: Reminder, b: Reminder) = a.id == b.id
        override fun areContentsTheSame(a: Reminder, b: Reminder) = a == b
    }

    private companion object {
        val AVATAR_COLORS = intArrayOf(
            0xFF2F6BFF.toInt(), 0xFF22A06B.toInt(), 0xFFF08C2E.toInt(),
            0xFF7B61FF.toInt(), 0xFFE5487B.toInt(), 0xFF14A3B8.toInt(),
        )
    }
}
