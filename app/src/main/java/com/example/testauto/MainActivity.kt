package com.example.testauto

import android.R.attr.value
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.*


// 数据类用于存储列表项
data class ListItem(
    val text: String,
    val type: ItemType
)

enum class ItemType {
    ACCESSIBILITY_SERVICE,  // 无障碍服务
    APP_LIST               // 应用列表
}


class MainActivity : ComponentActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: InfiniteScrollAdapter
    private var dataList = ArrayList<ListItem>()
    private var isLoading = false
    private val visibleThreshold = 5

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 正确设置 activity_main 布局文件
        setContentView(R.layout.activity_main)

        // 获取并打印应用列表（优先执行）
        getInstalledApps()
        
        // 简单测试应用列表获取
        testGetApps()

        // 初始化 RecyclerView
        setupRecyclerView()

        // 设置按钮点击事件
        setupButtons()

        // 加载初始数据（包括无障碍服务）
        loadInitialData()

        // 检测 AutoJs 操作
        detectAutoJsOperation()
    }

    private fun setupButtons() {
        findViewById<android.widget.Button>(R.id.btnRefreshAccessibility).setOnClickListener {
            refreshAccessibilityServices()
        }

        findViewById<android.widget.Button>(R.id.btnOpenAccessibilitySettings).setOnClickListener {
            openAccessibilitySettings()
        }
    }

    private fun openAccessibilitySettings() {
        try {
            val intent = android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("MainActivity", "无法打开无障碍设置: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        // 刷新无障碍服务信息
        refreshAccessibilityServices()
    }

    /**
     * 获取正在运行的无障碍服务
     */
    private fun getAccessibilityServices(): List<ListItem> {
        val accessibilityManager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = accessibilityManager.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        
        val accessibilityItems = ArrayList<ListItem>()
        
        if (enabledServices.isEmpty()) {
            accessibilityItems.add(ListItem("没有启用的无障碍服务", ItemType.ACCESSIBILITY_SERVICE))
        } else {
            accessibilityItems.add(ListItem("=== 正在运行的无障碍服务 ===", ItemType.ACCESSIBILITY_SERVICE))
            
            for (serviceInfo in enabledServices) {
                val serviceName = try {
                    val packageManager = packageManager
                    val appInfo = packageManager.getApplicationInfo(serviceInfo.resolveInfo.serviceInfo.packageName, 0)
                    packageManager.getApplicationLabel(appInfo).toString()
                } catch (e: Exception) {
                    serviceInfo.resolveInfo.serviceInfo.packageName
                }
                
                val packageName = serviceInfo.resolveInfo.serviceInfo.packageName
                val serviceClassName = serviceInfo.resolveInfo.serviceInfo.name
                
                // 检查服务是否正在运行
                val isRunning = isAccessibilityServiceRunning(packageName, serviceClassName)
                val statusText = if (isRunning) "运行中" else "已停止"
                
                val itemText = "服务: $serviceName\n包名: $packageName\n类名: $serviceClassName\n状态: $statusText"
                accessibilityItems.add(ListItem(itemText, ItemType.ACCESSIBILITY_SERVICE))
            }
        }
        
        return accessibilityItems
    }

    /**
     * 检查无障碍服务是否正在运行
     */
    private fun isAccessibilityServiceRunning(packageName: String, className: String): Boolean {
        val accessibilityManager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val runningServices = accessibilityManager.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        
        return runningServices.any { serviceInfo ->
            serviceInfo.resolveInfo.serviceInfo.packageName == packageName &&
            serviceInfo.resolveInfo.serviceInfo.name == className
        }
    }

    /**
     * 检测特定的无障碍服务（如 AutoJs 相关服务）
     */
    private fun detectSpecificAccessibilityServices(): List<ListItem> {
        val specificServices = ArrayList<ListItem>()
        val accessibilityManager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = accessibilityManager.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        
        // 检测 AutoJs 相关服务
        val autoJsServices = enabledServices.filter { serviceInfo ->
            val packageName = serviceInfo.resolveInfo.serviceInfo.packageName
            packageName.contains("autojs") || 
            packageName.contains("auto.js") ||
            packageName.contains("autojspro") ||
            packageName.contains("hamibot") ||
            packageName.contains("autoxjs")
        }
        
        if (autoJsServices.isNotEmpty()) {
            specificServices.add(ListItem("=== 检测到自动化相关服务 ===", ItemType.ACCESSIBILITY_SERVICE))
            
            for (serviceInfo in autoJsServices) {
                val serviceName = try {
                    val packageManager = packageManager
                    val appInfo = packageManager.getApplicationInfo(serviceInfo.resolveInfo.serviceInfo.packageName, 0)
                    packageManager.getApplicationLabel(appInfo).toString()
                } catch (e: Exception) {
                    serviceInfo.resolveInfo.serviceInfo.packageName
                }
                
                val packageName = serviceInfo.resolveInfo.serviceInfo.packageName
                val serviceClassName = serviceInfo.resolveInfo.serviceInfo.name
                val isRunning = isAccessibilityServiceRunning(packageName, serviceClassName)
                val statusText = if (isRunning) "运行中" else "已停止"
                
                // 获取服务的详细信息
                val serviceDetails = getAccessibilityServiceDetails(serviceInfo)
                
                val itemText = "自动化服务: $serviceName\n包名: $packageName\n类名: $serviceClassName\n状态: $statusText\n$serviceDetails"
                specificServices.add(ListItem(itemText, ItemType.ACCESSIBILITY_SERVICE))
            }
        }
        
        return specificServices
    }

    /**
     * 获取无障碍服务的详细信息
     */
    private fun getAccessibilityServiceDetails(serviceInfo: AccessibilityServiceInfo): String {
        val details = StringBuilder()
        
        // 获取服务的事件类型
        val eventTypes = serviceInfo.eventTypes
        val eventTypeNames = when (eventTypes) {
            AccessibilityServiceInfo.TYPE_ALL_MASK -> "所有事件"
            AccessibilityServiceInfo.TYPE_VIEW_CLICKED -> "点击事件"
            AccessibilityServiceInfo.TYPE_VIEW_LONG_CLICKED -> "长按事件"
            AccessibilityServiceInfo.TYPE_VIEW_SELECTED -> "选择事件"
            AccessibilityServiceInfo.TYPE_VIEW_FOCUSED -> "焦点事件"
            AccessibilityServiceInfo.TYPE_VIEW_TEXT_CHANGED -> "文本变化事件"
            AccessibilityServiceInfo.TYPE_WINDOW_STATE_CHANGED -> "窗口状态变化"
            AccessibilityServiceInfo.TYPE_NOTIFICATION_STATE_CHANGED -> "通知状态变化"
            else -> "自定义事件类型: $eventTypes"
        }
        
        details.append("事件类型: $eventTypeNames\n")
        
        // 获取反馈类型
        val feedbackType = serviceInfo.feedbackType
        val feedbackTypeNames = when (feedbackType) {
            AccessibilityServiceInfo.FEEDBACK_SPOKEN -> "语音反馈"
            AccessibilityServiceInfo.FEEDBACK_HAPTIC -> "触觉反馈"
            AccessibilityServiceInfo.FEEDBACK_AUDIBLE -> "音频反馈"
            AccessibilityServiceInfo.FEEDBACK_VISUAL -> "视觉反馈"
            AccessibilityServiceInfo.FEEDBACK_GENERIC -> "通用反馈"
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK -> "所有反馈类型"
            else -> "自定义反馈类型: $feedbackType"
        }
        
        details.append("反馈类型: $feedbackTypeNames\n")
        
        // 获取标志
        val flags = serviceInfo.flags
        val flagNames = mutableListOf<String>()
        
        if (flags and AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS != 0) {
            flagNames.add("报告视图ID")
        }
        if (flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0) {
            flagNames.add("检索交互窗口")
        }
        if (flags and AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY != 0) {
            flagNames.add("增强网页无障碍")
        }
        if (flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS != 0) {
            flagNames.add("过滤按键事件")
        }
        if (flags and AccessibilityServiceInfo.FLAG_REQUEST_FINGERPRINT_GESTURES != 0) {
            flagNames.add("指纹手势")
        }
        if (flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE != 0) {
            flagNames.add("触摸探索模式")
        }
        
        if (flagNames.isNotEmpty()) {
            details.append("标志: ${flagNames.joinToString(", ")}")
        }
        
        return details.toString()
    }

    /**
     * 获取无障碍服务统计信息
     */
    private fun getAccessibilityServiceStats(): ListItem {
        val accessibilityManager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = accessibilityManager.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        
        val totalServices = enabledServices.size
        val runningServices = enabledServices.count { serviceInfo ->
            isAccessibilityServiceRunning(
                serviceInfo.resolveInfo.serviceInfo.packageName,
                serviceInfo.resolveInfo.serviceInfo.name
            )
        }
        
        val autoJsServices = enabledServices.count { serviceInfo ->
            val packageName = serviceInfo.resolveInfo.serviceInfo.packageName
            packageName.contains("autojs") || 
            packageName.contains("auto.js") ||
            packageName.contains("autojspro") ||
            packageName.contains("hamibot") ||
            packageName.contains("autoxjs")
        }
        
        val statsText = "无障碍服务统计:\n总服务数: $totalServices\n运行中: $runningServices\n自动化相关: $autoJsServices"
        
        return ListItem(statsText, ItemType.ACCESSIBILITY_SERVICE)
    }

    /**
     * 加载初始数据，包括无障碍服务信息
     */
    private fun loadInitialData() {
        // 首先添加统计信息
        val stats = getAccessibilityServiceStats()
        dataList.add(stats)
        dataList.add(ListItem("", ItemType.ACCESSIBILITY_SERVICE)) // 空行分隔
        
        // 添加特定服务检测（如 AutoJs 相关服务）
        val specificServices = detectSpecificAccessibilityServices()
        if (specificServices.isNotEmpty()) {
            dataList.addAll(specificServices)
            dataList.add(ListItem("", ItemType.ACCESSIBILITY_SERVICE)) // 空行分隔
        }
        
        // 添加所有无障碍服务信息
        val accessibilityServices = getAccessibilityServices()
        dataList.addAll(accessibilityServices)
        
        // 添加分隔符
        dataList.add(ListItem("=== 应用列表 ===", ItemType.APP_LIST))
        
        // 获取并添加真实的应用列表
        val apps = getAppListForDisplay()
        dataList.addAll(apps)
        
        adapter.notifyDataSetChanged()
    }

    /**
     * 获取应用列表用于显示
     */
    private fun getAppListForDisplay(): List<ListItem> {
        val appItems = ArrayList<ListItem>()
        
        try {
            val packageManager = packageManager
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN, null)
            intent.addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            
            val resolveInfoList = packageManager.queryIntentActivities(intent, 0)
            
            // 只显示前20个应用
            val appsToShow = resolveInfoList.take(20)
            for (resolveInfo in appsToShow) {
                val appName = resolveInfo.loadLabel(packageManager).toString()
                val packageName = resolveInfo.activityInfo.packageName
                
                val itemText = "$appName\n包名: $packageName"
                appItems.add(ListItem(itemText, ItemType.APP_LIST))
            }
            
            if (resolveInfoList.size > 20) {
                appItems.add(ListItem("... 还有 ${resolveInfoList.size - 20} 个应用", ItemType.APP_LIST))
            }
            
        } catch (e: Exception) {
            appItems.add(ListItem("获取应用列表失败: ${e.message}", ItemType.APP_LIST))
        }
        
        return appItems
    }

    /**
     * 刷新无障碍服务信息
     */
    private fun refreshAccessibilityServices() {
        // 清除现有的无障碍服务项
        dataList.removeAll { it.type == ItemType.ACCESSIBILITY_SERVICE }
        
        // 在开头重新添加无障碍服务信息
        val accessibilityServices = getAccessibilityServices()
        dataList.addAll(0, accessibilityServices)
        
        adapter.notifyDataSetChanged()
    }

    private fun testAdbProcess(){

    }

    private fun setupRecyclerView() {
        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = InfiniteScrollAdapter(dataList)
        recyclerView.adapter = adapter

        // 设置滚动监听器，实现无限加载功能
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)

                val linearLayoutManager = recyclerView.layoutManager as LinearLayoutManager
                val totalItemCount = linearLayoutManager.itemCount
                val lastVisibleItem = linearLayoutManager.findLastVisibleItemPosition()

                if (!isLoading && totalItemCount <= (lastVisibleItem + visibleThreshold)) {
                    // 加载更多数据
                    loadData()
                    isLoading = true
                }
            }
        })
    }

    private fun loadData() {
        // 模拟数据加载延迟
        recyclerView.postDelayed({
            val start = dataList.size
            val end = start + 20

            for (i in start until end) {
                dataList.add(ListItem("Item $i", ItemType.APP_LIST))
            }
            adapter.notifyDataSetChanged()
            isLoading = false
        }, 1500)
    }

    private fun detectAutoJsOperation() {
        // 需要用户在系统设置中授予 PACKAGE_USAGE_STATS 权限
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val currentTime = System.currentTimeMillis()

        // 获取最近1小时的应用使用情况
        val usageStatsList: List<UsageStats> = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            currentTime - 1000 * 3600,
            currentTime
        )

        // 输出每个应用的包名和最后使用时间
        for (usageStats in usageStatsList) {
            Log.d("UsageStats", "Package: ${usageStats.packageName} LastTimeUsed: ${usageStats.lastTimeUsed}")
        }
    }

    /**
     * 获取已安装的应用列表并打印
     */
    private fun getInstalledApps() {
        val packageManager = packageManager
        
        // 方法1: 获取所有应用（需要 QUERY_ALL_PACKAGES 权限）
        try {
            val installedApps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            Log.d("AppList", "=== 所有已安装的应用列表 ===")
            Log.d("AppList", "总共找到 ${installedApps.size} 个应用")
            
            // 只显示前20个应用，避免日志过长
            val appsToShow = installedApps.take(20)
            for (appInfo in appsToShow) {
                val appName = try {
                    packageManager.getApplicationLabel(appInfo).toString()
                } catch (e: Exception) {
                    "未知应用"
                }
                
                val packageName = appInfo.packageName
                val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                
                Log.d("AppList", "应用名称: $appName")
                Log.d("AppList", "包名: $packageName")
                Log.d("AppList", "是否系统应用: $isSystemApp")
                Log.d("AppList", "---")
            }
            
            if (installedApps.size > 20) {
                Log.d("AppList", "... 还有 ${installedApps.size - 20} 个应用未显示")
            }
            
        } catch (e: SecurityException) {
            Log.e("AppList", "没有权限获取所有应用列表: ${e.message}")
        }
        
        // 方法2: 获取用户安装的应用（更兼容）
        Log.d("AppList", "=== 用户安装的应用列表 ===")
        try {
            val userApps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { appInfo ->
                    (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0
                }
            
            Log.d("AppList", "用户安装的应用数量: ${userApps.size}")
            for (appInfo in userApps) {
                val appName = try {
                    packageManager.getApplicationLabel(appInfo).toString()
                } catch (e: Exception) {
                    "未知应用"
                }
                val packageName = appInfo.packageName
                
                Log.d("AppList", "用户应用: $appName ($packageName)")
            }
        } catch (e: Exception) {
            Log.e("AppList", "获取用户应用列表失败: ${e.message}")
        }
        
        // 方法3: 获取启动器应用（最兼容的方法）
        Log.d("AppList", "=== 可启动的应用列表 ===")
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN, null)
            intent.addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            
            val resolveInfoList = packageManager.queryIntentActivities(intent, 0)
            Log.d("AppList", "可启动的应用数量: ${resolveInfoList.size}")
            
            // 只显示前10个可启动应用
            val launcherApps = resolveInfoList.take(10)
            for (resolveInfo in launcherApps) {
                val appName = resolveInfo.loadLabel(packageManager).toString()
                val packageName = resolveInfo.activityInfo.packageName
                
                Log.d("AppList", "可启动应用: $appName ($packageName)")
            }
            
            if (resolveInfoList.size > 10) {
                Log.d("AppList", "... 还有 ${resolveInfoList.size - 10} 个可启动应用未显示")
            }
        } catch (e: Exception) {
            Log.e("AppList", "获取可启动应用列表失败: ${e.message}")
        }
    }

    /**
     * 简单的应用列表获取测试方法
     */
    private fun testGetApps() {
        Log.d("AppTest", "开始获取应用列表...")
        val packageManager = packageManager
        
        try {
            // 获取可启动的应用（最可靠的方法）
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN, null)
            intent.addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            val apps = packageManager.queryIntentActivities(intent, 0)
            
            Log.d("AppTest", "找到 ${apps.size} 个可启动应用")
            
            // 显示前5个应用
            for (i in 0 until minOf(5, apps.size)) {
                val app = apps[i]
                val appName = app.loadLabel(packageManager).toString()
                val packageName = app.activityInfo.packageName
                Log.d("AppTest", "${i + 1}. $appName ($packageName)")
            }
            
        } catch (e: Exception) {
            Log.e("AppTest", "获取应用列表失败: ${e.message}")
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {

        // 确保 ev 不为空
        if (ev != null) {
            Log.d("dispatchTouchEvent Touch Detection", "Event source is " + ev.source)

            Log.d("dispatchTouchEvent ToolType", "ev != null" )

            // 判断事件源是否来自触摸屏
            if ((ev.source and InputDevice.SOURCE_TOUCHSCREEN) != InputDevice.SOURCE_TOUCHSCREEN) {
                Log.d("dispatchTouchEvent Touch Detection", "Event is not from a touchscreen")
                // 可能是脚本模拟触摸事件
            }

            // 获取第一个触摸点的工具类型
            val toolType = ev.getToolType(0)
            Log.d("dispatchTouchEvent ToolType", "First ToolType source is " + toolType)
            // 根据工具类型进行判断
            when (toolType) {
                MotionEvent.TOOL_TYPE_FINGER -> Log.d("dispatchTouchEvent ToolType", "Tool type is finger")
                MotionEvent.TOOL_TYPE_STYLUS -> Log.d("dispatchTouchEvent ToolType", "Tool type is stylus")
                MotionEvent.TOOL_TYPE_MOUSE -> Log.d("dispatchTouchEvent ToolType", "Tool type is mouse")
                MotionEvent.TOOL_TYPE_ERASER -> Log.d("dispatchTouchEvent ToolType", "Tool type is eraser")
                MotionEvent.TOOL_TYPE_UNKNOWN -> Log.d("dispatchTouchEvent ToolType", "Tool type is unknwn")
                else -> Log.d("dispatchTouchEvent ToolType", "Unknown tool type: $toolType")
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 确保 ev 不为空
        Log.d("onTouchEvent Touch Detection", "Event source is " + event.source)

        Log.d("onTouchEvent ToolType", "ev != null" )

        // 判断事件源是否来自触摸屏
        if ((event.source and InputDevice.SOURCE_TOUCHSCREEN) != InputDevice.SOURCE_TOUCHSCREEN) {
            Log.d("onTouchEvent Touch Detection", "Event is not from a touchscreen")
            // 可能是脚本模拟触摸事件
        }

        // 获取第一个触摸点的工具类型
        val toolType = event.getToolType(0)
        Log.d("onTouchEvent ToolType", "First ToolType source is " + toolType)
        // 根据工具类型进行判断
        when (toolType) {
            MotionEvent.TOOL_TYPE_FINGER -> Log.d("onTouchEvent ToolType", "Tool type is finger")
            MotionEvent.TOOL_TYPE_STYLUS -> Log.d("onTouchEvent ToolType", "Tool type is stylus")
            MotionEvent.TOOL_TYPE_MOUSE -> Log.d("onTouchEvent ToolType", "Tool type is mouse")
            MotionEvent.TOOL_TYPE_ERASER -> Log.d("onTouchEvent ToolType", "Tool type is eraser")
            MotionEvent.TOOL_TYPE_UNKNOWN -> Log.d("onTouchEvent ToolType", "Tool type is unknwn")

            else -> Log.d("onTouchEvent ToolType", "Unknown tool type: $toolType")
        }
        return super.onTouchEvent(event)
    }
}
