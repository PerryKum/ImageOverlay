package com.example.imageoverlay

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.imageoverlay.model.ConfigRepository

class AppSelectorActivity : AppCompatActivity() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: AppAdapter
    private var appList: List<AppInfo> = listOf()
    private var groupName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_selector)
        
        groupName = intent.getStringExtra("group_name") ?: ""
        title = "选择要绑定的应用"
        
        recyclerView = findViewById(R.id.recyclerViewApps)
        recyclerView.layoutManager = LinearLayoutManager(this)
        
        loadInstalledApps()
        
        adapter = AppAdapter(appList) { appInfo ->
            // 绑定应用到组
            ConfigRepository.bindAppToGroup(groupName, appInfo.packageName)
            ConfigRepository.save(this)
            
            setResult(Activity.RESULT_OK, Intent().apply {
                putExtra("package_name", appInfo.packageName)
                putExtra("app_name", appInfo.appName)
            })
            finish()
        }
        recyclerView.adapter = adapter
    }

    private fun loadInstalledApps() {
        val packageManager = packageManager
        val installedApps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        
        appList = installedApps
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 } // 只显示用户安装的应用
            .map { appInfo ->
                AppInfo(
                    appName = appInfo.loadLabel(packageManager).toString(),
                    packageName = appInfo.packageName,
                    icon = appInfo.loadIcon(packageManager)
                )
            }
            .sortedBy { it.appName }
    }

    data class AppInfo(
        val appName: String,
        val packageName: String,
        val icon: android.graphics.drawable.Drawable
    )

    class AppAdapter(
        private val apps: List<AppInfo>,
        private val onAppClick: (AppInfo) -> Unit
    ) : RecyclerView.Adapter<AppAdapter.AppViewHolder>() {
        
        class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val ivIcon: ImageView = itemView.findViewById(R.id.ivAppIcon)
            val tvAppName: TextView = itemView.findViewById(R.id.tvAppName)
            val tvPackageName: TextView = itemView.findViewById(R.id.tvPackageName)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app, parent, false)
            return AppViewHolder(view)
        }

        override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
            val app = apps[position]
            holder.ivIcon.setImageDrawable(app.icon)
            holder.tvAppName.text = app.appName
            holder.tvPackageName.text = app.packageName
            holder.itemView.setOnClickListener { onAppClick(app) }
        }

        override fun getItemCount(): Int = apps.size
    }
}
