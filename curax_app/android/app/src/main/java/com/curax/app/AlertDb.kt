package com.curax.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class AlertItem(
    val id: Long,
    val type: String,
    val message: String,
    val receivedAt: Long,
    val userName: String = ""
)

class AlertDb(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_ALERTS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TYPE TEXT NOT NULL,
                $COL_MESSAGE TEXT NOT NULL,
                $COL_RECEIVED_AT INTEGER NOT NULL
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_ALERTS")
        onCreate(db)
    }

    fun insertAlert(type: String, message: String, receivedAt: Long = System.currentTimeMillis()): Long {
        val cv = ContentValues().apply {
            put(COL_TYPE, type)
            put(COL_MESSAGE, message)
            put(COL_RECEIVED_AT, receivedAt)
        }
        return writableDatabase.insert(TABLE_ALERTS, null, cv)
    }

    fun getAllAlerts(): List<AlertItem> {
        val c = readableDatabase.query(
            TABLE_ALERTS,
            null,
            null,
            null,
            null,
            null,
            "$COL_RECEIVED_AT DESC",
            "500"
        )
        val list = mutableListOf<AlertItem>()
        while (c.moveToNext()) {
            list.add(
                AlertItem(
                    id = c.getLong(c.getColumnIndexOrThrow(COL_ID)),
                    type = c.getString(c.getColumnIndexOrThrow(COL_TYPE)),
                    message = c.getString(c.getColumnIndexOrThrow(COL_MESSAGE)),
                    receivedAt = c.getLong(c.getColumnIndexOrThrow(COL_RECEIVED_AT))
                )
            )
        }
        c.close()
        return list
    }

    fun getAlertById(id: Long): AlertItem? {
        val c = readableDatabase.query(
            TABLE_ALERTS,
            null,
            "$COL_ID=?",
            arrayOf(id.toString()),
            null,
            null,
            null,
            "1"
        )
        val item = if (c.moveToFirst()) {
            AlertItem(
                id = c.getLong(c.getColumnIndexOrThrow(COL_ID)),
                type = c.getString(c.getColumnIndexOrThrow(COL_TYPE)),
                message = c.getString(c.getColumnIndexOrThrow(COL_MESSAGE)),
                receivedAt = c.getLong(c.getColumnIndexOrThrow(COL_RECEIVED_AT))
            )
        } else {
            null
        }
        c.close()
        return item
    }

    fun deleteAlert(id: Long): Int {
        return writableDatabase.delete(TABLE_ALERTS, "$COL_ID=?", arrayOf(id.toString()))
    }

    fun clearAllAlerts(): Int {
        return writableDatabase.delete(TABLE_ALERTS, null, null)
    }

    companion object {
        private const val DB_NAME = "curax_alerts.db"
        private const val DB_VERSION = 1
        private const val TABLE_ALERTS = "alerts"
        private const val COL_ID = "_id"
        private const val COL_TYPE = "type"
        private const val COL_MESSAGE = "message"
        private const val COL_RECEIVED_AT = "received_at"
    }
}

