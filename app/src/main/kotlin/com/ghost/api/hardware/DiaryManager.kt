package com.ghost.api.hardware

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import timber.log.Timber
import java.util.*

/**
 * DiaryManager - Uses the Android Calendar as a persistent, searchable memory store.
 * "Mnemosyne" system for agentic continuity.
 */
class DiaryManager(private val context: Context) {

    private val calendarUri: Uri = CalendarContract.Events.CONTENT_URI

    private fun hasPermission(): Boolean {
        return context.checkSelfPermission(android.Manifest.permission.WRITE_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /**
     * Stores a semantic distillation (memory) into the calendar.
     */
    fun storeMemory(title: String, content: String) {
        if (!hasPermission()) {
            Timber.w("No Calendar permission to store memory")
            return
        }
        try {
            // Find or create the GHOST calendar (optional, can use default for now)
            val calId = 1 // Default calendar for now, can be improved to find specific one

            val values = ContentValues().apply {
                put(CalendarContract.Events.DTSTART, System.currentTimeMillis())
                put(CalendarContract.Events.DTEND, System.currentTimeMillis() + 60 * 1000) // 1 min duration
                put(CalendarContract.Events.TITLE, "\u2727 GHOST: $title")
                put(CalendarContract.Events.DESCRIPTION, content)
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }

            val uri = context.contentResolver.insert(calendarUri, values)
            if (uri != null) {
                Timber.i("\u2705 Memory stored in Diary: $title")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to store memory in Diary")
        }
    }

    /**
     * Searches for past memories based on a query.
     */
    fun searchMemories(query: String): List<String> {
        val memories = mutableListOf<String>()
        try {
            val selection = "${CalendarContract.Events.TITLE} LIKE ? OR ${CalendarContract.Events.DESCRIPTION} LIKE ?"
            val selectionArgs = arrayOf("%$query%", "%$query%")
            
            val cursor = context.contentResolver.query(
                calendarUri,
                arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.DESCRIPTION, CalendarContract.Events.DTSTART),
                selection,
                selectionArgs,
                "${CalendarContract.Events.DTSTART} DESC"
            )

            cursor?.use {
                while (it.moveToNext()) {
                    val title = it.getString(0)
                    val description = it.getString(1)
                    val timestamp = it.getLong(2)
                    val date = Date(timestamp).toString()
                    memories.add("[$date] $title: $description")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to search memories in Diary")
        }
        return memories
    }
}
