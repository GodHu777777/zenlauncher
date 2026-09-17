package com.zenlauncher.app.manager

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.zenlauncher.app.model.CalendarAccount
import com.zenlauncher.app.model.CalendarEvent
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object CalendarManager {

    fun hasCalendarPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun getAvailableCalendars(context: Context): List<CalendarAccount> {
        if (!hasCalendarPermission(context)) return emptyList()

        val list = ArrayList<CalendarAccount>()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_COLOR
        )

        try {
            val cursor = context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                null,
                null,
                "${CalendarContract.Calendars.ACCOUNT_NAME} ASC, ${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC"
            )

            cursor?.use {
                val idIdx = it.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
                val nameIdx = it.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                val accNameIdx = it.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_NAME)
                val accTypeIdx = it.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_TYPE)
                val colorIdx = it.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_COLOR)

                while (it.moveToNext()) {
                    list.add(
                        CalendarAccount(
                            id = it.getLong(idIdx),
                            displayName = it.getString(nameIdx) ?: "日历",
                            accountName = it.getString(accNameIdx) ?: "",
                            accountType = it.getString(accTypeIdx) ?: "",
                            color = it.getInt(colorIdx)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return list
    }

    fun getUpcomingEvents(context: Context, pref: PrefManager): List<CalendarEvent> {
        if (!hasCalendarPermission(context)) return emptyList()

        val list = ArrayList<CalendarEvent>()
        val now = System.currentTimeMillis()
        val startMillis = now - 15 * 60 * 1000 // include ongoing events starting up to 15m ago
        val endMillis = now + 48 * 3600 * 1000L // next 48 hours
        val maxCount = pref.getCalendarMaxCount()
        val selectedCalendarIds = pref.getSelectedCalendars()

        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, startMillis)
        ContentUris.appendId(builder, endMillis)

        val projection = arrayOf(
            CalendarContract.Instances._ID,
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME
        )

        val selectionList = ArrayList<String>()
        val selectionArgs = ArrayList<String>()

        if (selectedCalendarIds.isNotEmpty()) {
            val placeholders = selectedCalendarIds.joinToString(",") { "?" }
            selectionList.add("${CalendarContract.Instances.CALENDAR_ID} IN ($placeholders)")
            selectionArgs.addAll(selectedCalendarIds)
        }

        val selection = if (selectionList.isEmpty()) null else selectionList.joinToString(" AND ")
        val args = if (selectionArgs.isEmpty()) null else selectionArgs.toTypedArray()

        try {
            val cursor = context.contentResolver.query(
                builder.build(),
                projection,
                selection,
                args,
                "${CalendarContract.Instances.BEGIN} ASC"
            )

            cursor?.use {
                val idIdx = it.getColumnIndexOrThrow(CalendarContract.Instances._ID)
                val eventIdIdx = it.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
                val titleIdx = it.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
                val beginIdx = it.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
                val endIdx = it.getColumnIndexOrThrow(CalendarContract.Instances.END)
                val allDayIdx = it.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
                val calIdIdx = it.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_ID)
                val calNameIdx = it.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_DISPLAY_NAME)

                while (it.moveToNext() && list.size < maxCount) {
                    val title = it.getString(titleIdx)?.trim()
                    if (title.isNullOrEmpty()) continue

                    val begin = it.getLong(beginIdx)
                    val end = it.getLong(endIdx)
                    val isAllDay = it.getInt(allDayIdx) != 0

                    list.add(
                        CalendarEvent(
                            id = it.getLong(idIdx),
                            eventId = it.getLong(eventIdIdx),
                            title = title,
                            startMillis = begin,
                            endMillis = end,
                            isAllDay = isAllDay,
                            calendarId = it.getLong(calIdIdx),
                            calendarDisplayName = it.getString(calNameIdx) ?: "",
                            formattedTime = formatEventTime(begin, end, isAllDay)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return list
    }

    fun openEventDetails(context: Context, eventId: Long) {
        try {
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = uri
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            openCalendarApp(context)
        }
    }

    fun openCalendarApp(context: Context) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("content://com.android.calendar/time")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun formatEventTime(startMillis: Long, endMillis: Long, isAllDay: Boolean): String {
        if (isAllDay) return "全天"

        val now = Calendar.getInstance()
        val eventCal = Calendar.getInstance().apply { timeInMillis = startMillis }

        val isToday = now.get(Calendar.YEAR) == eventCal.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) == eventCal.get(Calendar.DAY_OF_YEAR)

        val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
        val isTomorrow = tomorrow.get(Calendar.YEAR) == eventCal.get(Calendar.YEAR) &&
                tomorrow.get(Calendar.DAY_OF_YEAR) == eventCal.get(Calendar.DAY_OF_YEAR)

        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val timeStr = timeFormat.format(Date(startMillis))

        return when {
            isToday -> timeStr
            isTomorrow -> "明天 $timeStr"
            else -> {
                val dateFormat = SimpleDateFormat("M/d HH:mm", Locale.getDefault())
                dateFormat.format(Date(startMillis))
            }
        }
    }
}
