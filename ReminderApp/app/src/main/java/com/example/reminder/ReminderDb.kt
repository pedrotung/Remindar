package com.example.reminder

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** 基于 SQLite 的提醒数据访问层 */
class ReminderDb private constructor(context: Context) :
    SQLiteOpenHelper(context, "reminders.db", null, 2) {

    companion object {
        private const val T = "reminder"

        @Volatile private var instance: ReminderDb? = null

        fun get(context: Context): ReminderDb =
            instance ?: synchronized(this) {
                instance ?: ReminderDb(context.applicationContext).also { instance = it }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $T (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                title      TEXT    NOT NULL,
                note       TEXT    NOT NULL DEFAULT '',
                trigger_at INTEGER NOT NULL,
                repeat     TEXT    NOT NULL DEFAULT 'NONE',
                enabled    INTEGER NOT NULL DEFAULT 1,
                is_todo    INTEGER NOT NULL DEFAULT 0,
                done_at    INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 → v2：增加待办类型与完成时间，原有数据都是普通提醒
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $T ADD COLUMN is_todo INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE $T ADD COLUMN done_at INTEGER NOT NULL DEFAULT 0")
        }
    }

    fun getAll(): List<Reminder> =
        readableDatabase.query(T, null, null, null, null, null, "enabled DESC, trigger_at ASC")
            .use { c -> generateSequence { if (c.moveToNext()) c.toReminder() else null }.toList() }

    fun getEnabled(): List<Reminder> = getAll().filter { it.enabled }

    fun getById(id: Long): Reminder? =
        readableDatabase.query(T, null, "id = ?", arrayOf(id.toString()), null, null, null)
            .use { c -> if (c.moveToFirst()) c.toReminder() else null }

    /** 新增返回带 id 的对象；更新返回原对象 */
    fun save(r: Reminder): Reminder {
        val values = ContentValues().apply {
            put("title", r.title)
            put("note", r.note)
            put("trigger_at", r.triggerAt)
            put("repeat", r.repeat.name)
            put("enabled", if (r.enabled) 1 else 0)
            put("is_todo", if (r.isTodo) 1 else 0)
            put("done_at", r.doneAt)
        }
        return if (r.id == 0L) {
            r.copy(id = writableDatabase.insertOrThrow(T, null, values))
        } else {
            writableDatabase.update(T, values, "id = ?", arrayOf(r.id.toString()))
            r
        }
    }

    fun delete(id: Long) {
        writableDatabase.delete(T, "id = ?", arrayOf(id.toString()))
    }

    private fun Cursor.toReminder() = Reminder(
        id = getLong(getColumnIndexOrThrow("id")),
        title = getString(getColumnIndexOrThrow("title")),
        note = getString(getColumnIndexOrThrow("note")),
        triggerAt = getLong(getColumnIndexOrThrow("trigger_at")),
        repeat = RepeatType.valueOf(getString(getColumnIndexOrThrow("repeat"))),
        enabled = getInt(getColumnIndexOrThrow("enabled")) == 1,
        isTodo = getInt(getColumnIndexOrThrow("is_todo")) == 1,
        doneAt = getLong(getColumnIndexOrThrow("done_at")),
    )
}
