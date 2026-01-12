package com.example.testauto

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 应用列表适配器
 */
class AppListAdapter(private val appList: List<AppListActivity.AppInfo>) :
    RecyclerView.Adapter<AppListAdapter.AppViewHolder>() {

    class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvAppName: TextView = itemView.findViewById(R.id.tvAppName)
        val tvPackageName: TextView = itemView.findViewById(R.id.tvPackageName)
        val tvSystemApp: TextView = itemView.findViewById(R.id.tvSystemApp)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_list, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val app = appList[position]
        
        holder.tvAppName.text = app.appName
        holder.tvPackageName.text = "包名: ${app.packageName}"
        
        if (app.isSystemApp) {
            holder.tvSystemApp.text = "系统应用"
            holder.tvSystemApp.visibility = View.VISIBLE
        } else {
            holder.tvSystemApp.visibility = View.GONE
        }
    }

    override fun getItemCount(): Int {
        return appList.size
    }
}

