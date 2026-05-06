package com.lyz.webview_monitor.crash

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class CrashLimiter(context: Context) {
    private val sp = context.getSharedPreferences("crash_limiter", Context.MODE_PRIVATE)

    // ConcurrentHashMap：多线程同时崩溃时安全
    private val memoryCounts = ConcurrentHashMap<String, Int>()
    @Volatile private var lastDay = ""

    fun shouldAccept(dedupeKey: String): Boolean {
        rotateIfDayChanged()
        val count = memoryCounts[dedupeKey] ?: sp.getInt("cnt_$dedupeKey", 0)
        return count < 10
    }

    fun onAccepted(dedupeKey: String) {
        val next = (memoryCounts[dedupeKey] ?: sp.getInt("cnt_$dedupeKey", 0)) + 1
        memoryCounts[dedupeKey] = next
        sp.edit().putInt("cnt_$dedupeKey", next).apply()
    }

    fun currentDay(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    private fun rotateIfDayChanged() {
        val today = currentDay()
        if (lastDay == today) return
        val savedDay = sp.getString("last_day", "")
        if (savedDay != today) {
            memoryCounts.clear()
            sp.edit().clear().putString("last_day", today).apply()
        }
        lastDay = today
    }
}

