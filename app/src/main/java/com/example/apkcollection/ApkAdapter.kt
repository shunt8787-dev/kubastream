package com.example.apkcollection

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ApkAdapter(
    private var items: List<RemoteApk>,
    private val onClick: (RemoteApk) -> Unit,
    private val onRename: (RemoteApk) -> Unit,
    private val onDelete: (RemoteApk) -> Unit,
    private val onLink: (RemoteApk) -> Unit
) : RecyclerView.Adapter<ApkAdapter.ViewHolder>() {

    private var unlocked = false

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.appName)
        val details: TextView = view.findViewById(R.id.appDetails)
        val linkBtn: TextView = view.findViewById(R.id.linkBtn)
        val adminRow: View = view.findViewById(R.id.adminRow)
        val renameBtn: TextView = view.findViewById(R.id.renameBtn)
        val deleteBtn: TextView = view.findViewById(R.id.deleteBtn)
    }

    fun update(newItems: List<RemoteApk>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun setUnlocked(value: Boolean) {
        unlocked = value
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_apk, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.name.text = item.key
        holder.details.text = "${fmtSize(item.size)}  •  ${item.uploaded.take(10)}"
        holder.itemView.setOnClickListener { onClick(item) }
        holder.linkBtn.setOnClickListener { onLink(item) }

        holder.adminRow.visibility = if (unlocked) View.VISIBLE else View.GONE
        holder.renameBtn.setOnClickListener { onRename(item) }
        holder.deleteBtn.setOnClickListener { onDelete(item) }
    }

    override fun getItemCount() = items.size

    private fun fmtSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024 * 1024) return "%.1f KB".format(bytes / 1024.0)
        return "%.1f MB".format(bytes / (1024.0 * 1024))
    }
}
