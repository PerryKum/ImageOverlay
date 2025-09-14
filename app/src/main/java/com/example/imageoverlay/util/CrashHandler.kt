package com.example.imageoverlay.util

import android.content.Context
import java.io.PrintWriter
import java.io.StringWriter

object CrashHandler : Thread.UncaughtExceptionHandler {
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private lateinit var appContext: Context
    private const val PREF = "crash_pref"
    private const val KEY_CRASH_LOG = "crash_log"

    fun init(context: Context) {
        appContext = context.applicationContext
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(t: Thread, e: Throwable) {
        try {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            e.printStackTrace(pw)
            val stack = sw.toString()
            saveCrash(stack)
        } catch (_: Exception) {}
        // 交给系统默认处理（保留一致行为）
        defaultHandler?.uncaughtException(t, e)
    }

    private fun saveCrash(text: String) {
        try {
            val sp = appContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            sp.edit().putString(KEY_CRASH_LOG, text).apply()
        } catch (_: Exception) {}
    }

    fun consumeCrashLog(context: Context): String? {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val log = sp.getString(KEY_CRASH_LOG, null)
        if (!log.isNullOrBlank()) {
            sp.edit().remove(KEY_CRASH_LOG).apply()
        }
        return log
    }
}


