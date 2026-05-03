package com.ghost.api

import android.app.Application
import timber.log.Timber
import java.io.File

class GemmaApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Crash handler to write to file for debugging
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val crashLog = File(getExternalFilesDir(null), "crash.txt")
                crashLog.writeText("""
                    CRASH @ ${java.time.LocalDateTime.now()}
                    Thread: ${thread.name}

                    ${throwable.stackTraceToString()}
                """.trimIndent())
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }

        Timber.plant(Timber.DebugTree())
        Timber.i("Gemma API initialized")
    }
}
