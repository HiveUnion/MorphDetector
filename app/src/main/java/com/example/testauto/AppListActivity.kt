package com.example.testauto

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 应用列表 Activity
 * 显示所有已安装的应用列表
 */
class AppListActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: AppListAdapter
    private lateinit var tvStatus: TextView
    private val appList = ArrayList<AppInfo>()

    data class AppInfo(
        val appName: String,
        val packageName: String,
        val isSystemApp: Boolean
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_list)

        tvStatus = findViewById(R.id.tvStatus)
        recyclerView = findViewById(R.id.recyclerView)

        // 设置RecyclerView
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = AppListAdapter(appList)
        recyclerView.adapter = adapter

        // 加载应用列表
        loadAppList()
    }

    /**
     * 加载应用列表
     */
    private fun loadAppList() {
        tvStatus.text = "正在加载应用列表..."
        
        Thread {
            try {
                val apps = getAppList()
                
                runOnUiThread {
                    appList.clear()
                    appList.addAll(apps)
                    adapter.notifyDataSetChanged()
                    
                    tvStatus.text = "共找到 ${apps.size} 个应用"
                    Log.d("AppListActivity", "加载完成，共 ${apps.size} 个应用")
                }
            } catch (e: Exception) {
                Log.e("AppListActivity", "加载应用列表失败", e)
                runOnUiThread {
                    tvStatus.text = "加载失败: ${e.message}"
                }
            }
        }.start()
    }

    /**
     * 获取应用列表
     */
    private fun getAppList(): List<AppInfo> {
        val apps = ArrayList<AppInfo>()
        val packageManager = packageManager
        
        try {
            // 方法1: 获取所有可启动的应用（最兼容的方法）
            val intent = Intent(Intent.ACTION_MAIN, null)
            intent.addCategory(Intent.CATEGORY_LAUNCHER)
            
            val resolveInfoList = packageManager.queryIntentActivities(intent, 0)
            
            for (resolveInfo in resolveInfoList) {
                try {
                    val appName = resolveInfo.loadLabel(packageManager).toString()
                    val packageName = resolveInfo.activityInfo.packageName
                    
                    // 判断是否为系统应用
                    val appInfo = packageManager.getApplicationInfo(packageName, 0)
                    val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    
                    apps.add(AppInfo(appName, packageName, isSystemApp))
                } catch (e: Exception) {
                    Log.d("AppListActivity", "获取应用信息失败: ${e.message}")
                }
            }
            
            // 按应用名称排序
            apps.sortBy { it.appName }
            
        } catch (e: Exception) {
            Log.e("AppListActivity", "获取应用列表失败", e)
        }
        
        return apps
    }
}

