package com.gemma.api

import android.content.Context
import android.os.Process
import timber.log.Timber
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Global Crash Handler to log unhandled exceptions to disk
 * before the app process dies.
 */
class CrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {

    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            logCrash(thread, throwable)
        } catch (e: Exception) {
            Timber.e(e, "Failed to write crash log")
        } finally {
            // Pass control to default handler (which usually kills the app)
            defaultHandler?.uncaughtException(thread, throwable) ?: Process.killProcess(Process.myPid())
        }
    }

    private fun logCrash(thread: Thread, throwable: Throwable) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val filename = "crash_$timestamp.txt"
        
        val logDir = File(context.filesDir, "logs/crashes")
        if (!logDir.exists()) logDir.mkdirs()

        val file = File(logDir, filename)
        
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        val stackTrace = sw.toString()

        val report = """
            TIMESTAMP: $timestamp
            THREAD: ${thread.name}
            EXCEPTION: ${throwable.javaClass.simpleName}
            MESSAGE: ${throwable.message}
            
            STACKTRACE:
            $stackTrace
        """.trimIndent()

        file.writeText(report)
        Timber.e("CRASH INTERCEPTED! Log saved to: ${file.absolutePath}")
    }
    
    companion object {
        fun install(context: Context) {
            val handler = CrashHandler(context)
            Thread.setDefaultUncaughtExceptionHandler(handler)
        }
    }
}
