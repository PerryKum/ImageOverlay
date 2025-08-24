package com.example.imageoverlay.adapter

import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.imageoverlay.R
import com.example.imageoverlay.model.Config

class ConfigAdapter(
    private val context: Context,
    private val configs: List<Config>,
    private val onStatusClick: (Int) -> Unit,
    private val onItemClick: (Int) -> Unit,
    private val onLongClick: (Int) -> Unit,
    private val defaultConfigName: String? = null
) : RecyclerView.Adapter<ConfigAdapter.ConfigViewHolder>() {
    class ConfigViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivStatus: ImageView = itemView.findViewById(R.id.ivStatus)
        val ivThumb: ImageView = itemView.findViewById(R.id.ivThumb)
        val tvConfigName: TextView = itemView.findViewById(R.id.tvConfigName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConfigViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_config, parent, false)
        return ConfigViewHolder(view)
    }

    override fun onBindViewHolder(holder: ConfigViewHolder, position: Int) {
        val config = configs[position]
        val displayName = if (config.configName == defaultConfigName) {
            "${config.configName} ⭐"
        } else {
            config.configName
        }
        holder.tvConfigName.text = displayName
        holder.ivStatus.setImageResource(if (config.active) R.drawable.ic_circle_green else R.drawable.ic_circle_red)
        holder.ivStatus.setOnClickListener { onStatusClick(position) }
        holder.itemView.setOnClickListener { onItemClick(position) }
        if (!config.imageUri.isNullOrBlank()) {
            try {
                holder.ivThumb.setImageURI(Uri.parse(config.imageUri))
            } catch (_: Exception) {
                holder.ivThumb.setImageResource(android.R.drawable.ic_menu_report_image)
            }
        } else {
            holder.ivThumb.setImageResource(android.R.drawable.ic_menu_report_image)
        }
        holder.itemView.setOnLongClickListener {
            onLongClick(position)
            true
        }
    }

    override fun getItemCount(): Int = configs.size
} 