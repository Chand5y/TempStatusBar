package com.personal.tempstatusbar

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class TempRecord(val id: Int, val timestamp: Long, val temp: Int, val isCharging: Boolean, val chargeType: String, val appDetails: String, val isRoot: Boolean, val screenOn: Boolean)
data class FpsSession(val id: Int, val timestamp: Long, val appName: String, val durationSec: Int, val minFps: Int, val maxFps: Int, val avgFps: Int)

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, "ThermalMonitor.db", null, 2) {
    
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE temp_logs (id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp INTEGER, temp INTEGER, isCharging INTEGER, chargeType TEXT, appDetails TEXT, isRoot INTEGER, screenOn INTEGER)")
        db.execSQL("CREATE TABLE fps_sessions (id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp INTEGER, appName TEXT, durationSec INTEGER, minFps INTEGER, maxFps INTEGER, avgFps INTEGER)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("CREATE TABLE fps_sessions (id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp INTEGER, appName TEXT, durationSec INTEGER, minFps INTEGER, maxFps INTEGER, avgFps INTEGER)")
        }
    }

    fun insertRecord(temp: Int, isCharging: Boolean, chargeType: String, appDetails: String, isRoot: Boolean, screenOn: Boolean) {
        val db = writableDatabase
        val v = ContentValues().apply {
            put("timestamp", System.currentTimeMillis()); put("temp", temp); put("isCharging", if (isCharging) 1 else 0)
            put("chargeType", chargeType); put("appDetails", appDetails); put("isRoot", if (isRoot) 1 else 0); put("screenOn", if (screenOn) 1 else 0)
        }
        db.insert("temp_logs", null, v)
        db.execSQL("DELETE FROM temp_logs WHERE id NOT IN (SELECT id FROM temp_logs ORDER BY id DESC LIMIT 500)")
    }

    fun saveFpsSession(appName: String, durationSec: Int, minFps: Int, maxFps: Int, avgFps: Int) {
        val db = writableDatabase
        val v = ContentValues().apply {
            put("timestamp", System.currentTimeMillis()); put("appName", appName)
            put("durationSec", durationSec); put("minFps", minFps); put("maxFps", maxFps); put("avgFps", avgFps)
        }
        db.insert("fps_sessions", null, v)
    }

    fun getAllRecords(): List<TempRecord> {
        val list = mutableListOf<TempRecord>()
        val c = readableDatabase.rawQuery("SELECT * FROM temp_logs ORDER BY timestamp ASC", null)
        while (c.moveToNext()) {
            list.add(TempRecord(c.getInt(0), c.getLong(1), c.getInt(2), c.getInt(3) == 1, c.getString(4), c.getString(5), c.getInt(6) == 1, c.getInt(7) == 1))
        }
        c.close()
        return list
    }

    fun getFpsSessions(): List<FpsSession> {
        val list = mutableListOf<FpsSession>()
        val c = readableDatabase.rawQuery("SELECT * FROM fps_sessions ORDER BY timestamp DESC", null)
        while (c.moveToNext()) {
            list.add(FpsSession(c.getInt(0), c.getLong(1), c.getString(2), c.getInt(3), c.getInt(4), c.getInt(5), c.getInt(6)))
        }
        c.close()
        return list
    }
}
