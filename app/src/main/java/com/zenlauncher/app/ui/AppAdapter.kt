package com.zenlauncher.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.zenlauncher.app.R
import com.zenlauncher.app.model.AppInfo

class AppAdapter(
    private var items: List<AppInfo>,
    private val onItemClick: (AppInfo) -> Unit,
    private val onItemLongClick: ((AppInfo) -> Unit)? = null
) : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

    fun updateList(newItems: List<AppInfo>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app_text, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = items[position]
        holder.tvAppName.text = app.appName

        // Show/hide tags
        holder.tvTagClone.visibility = if (app.isClone) View.VISIBLE else View.GONE
        holder.tvTagCalm.visibility = if (app.isDopamineApp) View.VISIBLE else View.GONE
        holder.tvTagHidden.visibility = if (app.isHidden) View.VISIBLE else View.GONE
        holder.tvTagPin.visibility = if (app.isFavorite) View.VISIBLE else View.GONE

        holder.itemView.setOnClickListener {
            onItemClick(app)
        }

        holder.itemView.setOnLongClickListener {
            onItemLongClick?.invoke(app)
            true
        }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvAppName: TextView = itemView.findViewById(R.id.tvAppName)
        val tvTagClone: TextView = itemView.findViewById(R.id.tvTagClone)
        val tvTagCalm: TextView = itemView.findViewById(R.id.tvTagCalm)
        val tvTagHidden: TextView = itemView.findViewById(R.id.tvTagHidden)
        val tvTagPin: TextView = itemView.findViewById(R.id.tvTagPin)
    }
}
