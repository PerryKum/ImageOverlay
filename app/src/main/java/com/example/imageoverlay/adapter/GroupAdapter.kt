package com.example.imageoverlay.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.imageoverlay.R
import com.example.imageoverlay.model.Group

class GroupAdapter(
    private val groups: List<Group>,
    private val onClick: (Group) -> Unit,
    private val onLongClick: (Int) -> Unit
) : RecyclerView.Adapter<GroupAdapter.GroupViewHolder>() {
    class GroupViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvGroupName: TextView = itemView.findViewById(R.id.tvGroupName)
        val tvRemark: TextView = itemView.findViewById(R.id.tvRemark)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_group, parent, false)
        return GroupViewHolder(view)
    }

    override fun onBindViewHolder(holder: GroupViewHolder, position: Int) {
        val group = groups[position]
        val displayName = if (group.boundPackageName != null) {
            "${group.groupName} 📱"
        } else {
            group.groupName
        }
        holder.tvGroupName.text = displayName
        holder.tvRemark.text = group.remark
        holder.itemView.setOnClickListener { onClick(group) }
        holder.itemView.setOnLongClickListener {
            onLongClick(position)
            true
        }
    }

    override fun getItemCount(): Int = groups.size
} 