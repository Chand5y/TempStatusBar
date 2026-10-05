package com.personal.tempstatusbar

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class TempRecord(val id: Int, val timestamp: Long, val temp: Int, val isCharging: Boolean, val chargeType: String, val appDetails: String, val isRoot: Boolean, val screenOn: Boolean)
data class FpsSession(val id: Int, val timestamp: Long, val appName: String, val durationSec: Int, val minFps: Int, val maxFps: Int, val avgFps: Int, val avgTemp: Int, val fpsSamples: String)

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, "ThermalMonitor.db", null, 6) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS temp_logs (id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp INTEGER, temp INTEGER, isCharging INTEGER, chargeType TEXT, appDetails TEXT, isRoot INTEGER, screenOn INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS fps_sessions (id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp INTEGER, appName TEXT, durationSec INTEGER, minFps INTEGER, maxFps INTEGER, avgFps INTEGER, avgTemp INTEGER, fpsSamples TEXT)")
        
        val now = System.currentTimeMillis()
        db.execSQL("INSERT INTO temp_logs (timestamp, temp, isCharging, chargeType, appDetails, isRoot, screenOn) VALUES ($now, 32, 0, 'Discharging', 'System Baseline', 1, 1)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        try {
            db.execSQL("ALTER TABLE fps_sessions ADD COLUMN fpsSamples TEXT")
        } catch (e: Exception) {
            db.execSQL("DROP TABLE IF EXISTS fps_sessions")
            onCreate(db)
        }
    }

    fun insertRecord(temp: Int, isCharging: Boolean, chargeType: String, appDetails: String, isRoot: Boolean, screenOn: Boolean) {
        try {
            val db = writableDatabase
            val v = ContentValues().apply {
                put("timestamp", System.currentTimeMillis()); put("temp", temp); put("isCharging", if (isCharging) 1 else 0)
                put("chargeType", chargeType); put("appDetails", appDetails); put("isRoot", if (isRoot) 1 else 0); put("screenOn", if (screenOn) 1 else 0)
            }
            db.insert("temp_logs", null, v)
            db.execSQL("DELETE FROM temp_logs WHERE id NOT IN (SELECT id FROM temp_logs ORDER BY id DESC LIMIT 500)")
        } catch (e: Exception) {}
    }

    fun saveFpsSession(appName: String, durationSec: Int, minFps: Int, maxFps: Int, avgFps: Int, avgTemp: Int, samples: String) {
        try {
            val db = writableDatabase
            val v = ContentValues().apply {
                put("timestamp", System.currentTimeMillis()); put("appName", appName)
                put("durationSec", durationSec); put("minFps", minFps); put("maxFps", maxFps); put("avgFps", avgFps)
                put("avgTemp", avgTemp); put("fpsSamples", samples)
            }
            db.insert("fps_sessions", null, v)
        } catch (e: Exception) {}
    }

    fun getAllRecords(): List<TempRecord> {
        val list = mutableListOf<TempRecord>()
        try {
            val c = readableDatabase.rawQuery("SELECT * FROM temp_logs ORDER BY timestamp ASC", null)
            while (c.moveToNext()) {
                list.add(TempRecord(c.getInt(0), c.getLong(1), c.getInt(2), c.getInt(3) == 1, c.getString(4) ?: "Unknown", c.getString(5) ?: "Unknown", c.getInt(6) == 1, c.getInt(7) == 1))
            }
            c.close()
        } catch (e: Exception) {}
        return list
    }

    fun getFpsSessions(): List<FpsSession> {
        val list = mutableListOf<FpsSession>()
        try {
            writableDatabase.execSQL("DELETE FROM fps_sessions WHERE maxFps > 500 OR avgFps > 500")
            val c = readableDatabase.rawQuery("SELECT * FROM fps_sessions ORDER BY timestamp DESC", null)
            while (c.moveToNext()) {
                list.add(FpsSession(
                    c.getInt(0), c.getLong(1), c.getString(2) ?: "Active Session",
                    c.getInt(3), c.getInt(4), c.getInt(5), c.getInt(6), c.getInt(7),
                    if (c.columnCount > 8) c.getString(8) ?: "" else ""
                ))
            }
            c.close()
        } catch (e: Exception) {}
        return list
    }
}
