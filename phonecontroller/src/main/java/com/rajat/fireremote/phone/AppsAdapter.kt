package com.rajat.fireremote.phone

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Quick-start list: pinned apps first, then everything else A-Z. */
class AppsAdapter(
    private val onLaunch: (AppEntry) -> Unit,
    private val onPinsChanged: (Set<String>) -> Unit,
    initialPins: Set<String>
) : RecyclerView.Adapter<AppsAdapter.VH>() {

    private val pins = initialPins.toMutableSet()
    private var all: List<AppEntry> = emptyList()
    private var shown: List<AppEntry> = emptyList()

    fun submit(apps: List<AppEntry>) {
        all = apps
        resort()
    }

    private fun resort() {
        shown = all.sortedWith(
            compareByDescending<AppEntry> { it.pkg in pins }.thenBy { it.label.lowercase() }
        )
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = shown.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val app = shown[position]
        holder.label.text = app.label
        holder.pkg.text = app.pkg
        holder.pin.text = if (app.pkg in pins) "★" else "☆"
        holder.itemView.setOnClickListener { onLaunch(app) }
        holder.pin.setOnClickListener {
            if (!pins.remove(app.pkg)) pins.add(app.pkg)
            onPinsChanged(pins.toSet())
            resort()
        }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val label: TextView = view.findViewById(R.id.app_label)
        val pkg: TextView = view.findViewById(R.id.app_pkg)
        val pin: TextView = view.findViewById(R.id.app_pin)
    }
}
