package com.zenlauncher.app.model

data class CalendarEvent(
    val id: Long,
    val eventId: Long,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val isAllDay: Boolean,
    val calendarId: Long,
    val calendarDisplayName: String,
    val formattedTime: String
)

data class CalendarAccount(
    val id: Long,
    val displayName: String,
    val accountName: String,
    val accountType: String,
    val color: Int
)
