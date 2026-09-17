package com.zenlauncher.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.zenlauncher.app.R
import com.zenlauncher.app.model.CalendarEvent

class CalendarAdapter(
    private var events: List<CalendarEvent>,
    private val onEventClick: (CalendarEvent) -> Unit
) : RecyclerView.Adapter<CalendarAdapter.ViewHolder>() {

    fun updateEvents(newEvents: List<CalendarEvent>) {
        events = newEvents
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_calendar_event, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val event = events[position]
        holder.tvTime.text = event.formattedTime
        holder.tvTitle.text = event.title
        holder.itemView.setOnClickListener {
            onEventClick(event)
        }
    }

    override fun getItemCount(): Int = events.size

    class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val tvBullet: TextView = v.findViewById(R.id.tvEventBullet)
        val tvTime: TextView = v.findViewById(R.id.tvEventTime)
        val tvTitle: TextView = v.findViewById(R.id.tvEventTitle)
    }
}
