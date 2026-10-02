package com.personal.tempstatusbar

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class TempRecord(
    val id: Long,
    val timestamp: Long,
    val temp: Int,
    val isCharging: Boolean,
    val chargeType: String,
    val appDetails: String,
    val isRoot: Boolean,
    val screenOn: Boolean
)

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, "TempTracker.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE records (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER,
                temp INTEGER,
                is_charging INTEGER,
                charge_type TEXT,
                app_details TEXT,
                is_root INTEGER,
                screen_on INTEGER DEFAULT 1
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS records")
        onCreate(db)
    }

    fun insertRecord(temp: Int, isCharging: Boolean, chargeType: String, details: String, isRoot: Boolean, screenOn: Boolean) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("timestamp", System.currentTimeMillis())
            put("temp", temp)
            put("is_charging", if (isCharging) 1 else 0)
            put("charge_type", chargeType)
            put("app_details", details)
            put("is_root", if (isRoot) 1 else 0)
            put("screen_on", if (screenOn) 1 else 0)
        }
        db.insert("records", null, cv)

        val cutoff = System.currentTimeMillis() - (72 * 60 * 60 * 1000L)
        db.delete("records", "timestamp < ?", arrayOf(cutoff.toString()))
    }

    fun getAllRecords(): List<TempRecord> {
        val list = mutableListOf<TempRecord>()
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM records ORDER BY timestamp ASC", null)
        if (cursor.moveToFirst()) {
            do {
                list.add(
                    TempRecord(
                        id = cursor.getLong(0),
                        timestamp = cursor.getLong(1),
                        temp = cursor.getInt(2),
                        isCharging = cursor.getInt(3) == 1,
                        chargeType = cursor.getString(4),
                        appDetails = cursor.getString(5),
                        isRoot = cursor.getInt(6) == 1,
                        screenOn = cursor.getInt(7) == 1
                    )
                )
            } while (cursor.moveToNext())
        }
        cursor.close()
        return list
    }
}
