package com.example.testauto

import android.R.attr.value
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.*
import java.io.BufferedReader
import java.io.InputStreamReader


// 数据类用于存储列表项
data class ListItem(
    val text: String,
    val type: ItemType
)

enum class ItemType {
    BOOT_ID,               // Boot ID
    DEVICE_ID,             // 设备标识符 (AndroidId, OAId, IMEI, Serial)
    ACCESSIBILITY_SERVICE,  // 无障碍服务
    VPN_STATUS,            // VPN状态
    LOCK_SCREEN,           // 锁屏解锁方式状态
    APP_LIST               // 应用列表
}


class MainActivity : ComponentActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: InfiniteScrollAdapter
    private lateinit var tvImei: android.widget.TextView
    private lateinit var tvBootId: android.widget.TextView
    private lateinit var tvDeviceIds: android.widget.TextView
    private lateinit var tvAccessibilityServices: android.widget.TextView
    private lateinit var tvVpnStatus: android.widget.TextView
    private lateinit var tvLockScreenStatus: android.widget.TextView
    private var dataList = ArrayList<ListItem>()
    private var isLoading = false
    private val visibleThreshold = 5
    
    companion object {
        private const val PERMISSION_REQUEST_CODE_READ_PHONE_STATE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 正确设置 activity_main 布局文件
        setContentView(R.layout.activity_main)

        // 将耗时操作移到后台线程执行，避免阻塞主线程
        Thread {
            // 获取并打印应用列表（优先执行）
            getInstalledApps()
            
            // 简单测试应用列表获取
            testGetApps()
            
            // 获取并打印 vendor.gsm.serial
            getAndPrintVendorGsmSerial()
        }.start()

        // 初始化 RecyclerView
        setupRecyclerView()

        // 初始化所有 TextView
        tvImei = findViewById(R.id.tvImei)
        tvBootId = findViewById(R.id.tvBootId)
        tvDeviceIds = findViewById(R.id.tvDeviceIds)
        tvAccessibilityServices = findViewById(R.id.tvAccessibilityServices)
        tvVpnStatus = findViewById(R.id.tvVpnStatus)
        tvLockScreenStatus = findViewById(R.id.tvLockScreenStatus)

        // 设置按钮点击事件
        setupButtons()

        // 加载初始数据（包括无障碍服务）
        loadInitialData()

        // 检测 AutoJs 操作
        detectAutoJsOperation()
        
        // 检查并请求权限
        checkAndRequestPhoneStatePermission()
    }

    /**
     * 检查 READ_PHONE_STATE 权限
     */
    private fun hasPhoneStatePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 检查并请求 READ_PHONE_STATE 权限
     */
    private fun checkAndRequestPhoneStatePermission() {
        if (!hasPhoneStatePermission()) {
            // 如果权限未授予，请求权限
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.READ_PHONE_STATE),
                PERMISSION_REQUEST_CODE_READ_PHONE_STATE
            )
        } else {
            // 权限已授予，刷新设备标识符
            refreshDeviceIds()
        }
    }

    /**
     * 处理权限请求结果
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        when (requestCode) {
            PERMISSION_REQUEST_CODE_READ_PHONE_STATE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // 权限已授予
                    Log.d("MainActivity", "READ_PHONE_STATE 权限已授予")
                    // 刷新设备标识符以显示 IMEI 和 Serial
                    refreshDeviceIds()
                    // 刷新IMEI显示
                    getAndDisplayImei()
                } else {
                    // 权限被拒绝
                    Log.d("MainActivity", "READ_PHONE_STATE 权限被拒绝")
                    // 仍然刷新设备标识符，但会显示权限不足
                    refreshDeviceIds()
                    // 更新IMEI显示为权限不足
                    tvImei.text = "权限被拒绝，无法获取IMEI\n请授予 READ_PHONE_STATE 权限"
                }
            }
        }
    }

    private fun setupButtons() {
        findViewById<android.widget.Button>(R.id.btnRefreshBootId).setOnClickListener {
            refreshBootId()
        }

        findViewById<android.widget.Button>(R.id.btnRefreshDrmId).setOnClickListener {
            if (hasPhoneStatePermission()) {
                refreshDeviceIds()
            } else {
                // 如果权限未授予，先请求权限
                checkAndRequestPhoneStatePermission()
            }
        }

        findViewById<android.widget.Button>(R.id.btnRefreshAccessibility).setOnClickListener {
            refreshAccessibilityServices()
        }

        findViewById<android.widget.Button>(R.id.btnOpenAccessibilitySettings).setOnClickListener {
            openAccessibilitySettings()
        }

        findViewById<android.widget.Button>(R.id.btnRefreshVpn).setOnClickListener {
            refreshVpnStatus()
        }

        findViewById<android.widget.Button>(R.id.btnOpenVpnSettings).setOnClickListener {
            openVpnSettings()
        }

        findViewById<android.widget.Button>(R.id.btnGetImei).setOnClickListener {
            getAndDisplayImei()
        }

        findViewById<android.widget.Button>(R.id.btnRefreshLockScreen).setOnClickListener {
            refreshLockScreenStatus()
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

    private fun openVpnSettings() {
        try {
            val intent = android.content.Intent(android.provider.Settings.ACTION_VPN_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("MainActivity", "无法打开VPN设置: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        // 异步刷新所有信息显示，避免阻塞主线程
        refreshAllDataAsync()
    }
    
    /**
     * 异步刷新所有数据
     */
    private fun refreshAllDataAsync() {
        Thread {
            // 在后台线程执行耗时操作
            val bootId = try {
                val bootIdFile = java.io.File("/proc/sys/kernel/random/boot_id")
                if (bootIdFile.exists()) {
                    bootIdFile.readText().trim()
                } else {
                    "无法读取 Boot ID"
                }
            } catch (e: Exception) {
                "读取失败: ${e.message}"
            }
            
            // 在主线程更新UI
            runOnUiThread {
                tvBootId.text = bootId
            }
            
            // 刷新设备标识符（包含耗时操作）
            refreshDeviceIdsAsync()
            
            // 刷新无障碍服务
            refreshAccessibilityServicesAsync()
            
            // 刷新VPN状态
            refreshVpnStatusAsync()
            
            // 刷新锁屏状态
            refreshLockScreenStatusAsync()
            
            // 刷新IMEI（最耗时）
            refreshImeiAsync()
        }.start()
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
//            AccessibilityServiceInfo.TYPE_ALL_MASK -> "所有事件"
//            AccessibilityServiceInfo.TYPE_VIEW_CLICKED -> "点击事件"
//            AccessibilityServiceInfo.TYPE_VIEW_LONG_CLICKED -> "长按事件"
//            AccessibilityServiceInfo.TYPE_VIEW_SELECTED -> "选择事件"
//            AccessibilityServiceInfo.TYPE_VIEW_FOCUSED -> "焦点事件"
//            AccessibilityServiceInfo.TYPE_VIEW_TEXT_CHANGED -> "文本变化事件"
//            AccessibilityServiceInfo.TYPE_WINDOW_STATE_CHANGED -> "窗口状态变化"
//            AccessibilityServiceInfo.TYPE_NOTIFICATION_STATE_CHANGED -> "通知状态变化"
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
     * 获取 Boot ID
     */
    private fun getBootId(): ListItem {
        return try {
            val bootIdFile = java.io.File("/proc/sys/kernel/random/boot_id")
            val bootId = if (bootIdFile.exists()) {
                bootIdFile.readText().trim()
            } else {
                "无法读取 Boot ID"
            }
            
            val bootIdText = "=== Boot ID ===\n$bootId"
            ListItem(bootIdText, ItemType.BOOT_ID)
        } catch (e: Exception) {
            Log.e("MainActivity", "读取 Boot ID 失败", e)
            ListItem("=== Boot ID ===\n读取失败: ${e.message}", ItemType.BOOT_ID)
        }
    }

    /**
     * 刷新 Boot ID
     */
    private fun refreshBootId() {
        try {
            val bootIdFile = java.io.File("/proc/sys/kernel/random/boot_id")
            val bootId = if (bootIdFile.exists()) {
                bootIdFile.readText().trim()
            } else {
                "无法读取 Boot ID"
            }
            tvBootId.text = bootId
        } catch (e: Exception) {
            Log.e("MainActivity", "读取 Boot ID 失败", e)
            tvBootId.text = "读取失败: ${e.message}"
        }
    }

    /**
     * 获取设备标识符 (AndroidId, OAId, IMEI, Serial)
     */
    private fun getDeviceIds(): List<ListItem> {
        val deviceIdItems = ArrayList<ListItem>()
        
        try {
            deviceIdItems.add(ListItem("=== 设备标识符 ===", ItemType.DEVICE_ID))
            
            // 1. AndroidId
            try {
                val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                val androidIdText = if (androidId != null && androidId.isNotEmpty()) {
                    "AndroidId: $androidId"
                } else {
                    "AndroidId: 无法获取"
                }
                deviceIdItems.add(ListItem(androidIdText, ItemType.DEVICE_ID))
            } catch (e: Exception) {
                Log.e("MainActivity", "读取 AndroidId 失败", e)
                deviceIdItems.add(ListItem("AndroidId: 读取失败 - ${e.message}", ItemType.DEVICE_ID))
            }
            
            // 2. OAId (需要 MSASDK，这里先尝试获取，如果没有则显示未获取)
            try {
                // OAId 通常需要通过 MSASDK 获取，这里先显示提示
                deviceIdItems.add(ListItem("OAId: 需要通过 MSASDK 获取", ItemType.DEVICE_ID))
            } catch (e: Exception) {
                Log.e("MainActivity", "读取 OAId 失败", e)
                deviceIdItems.add(ListItem("OAId: 读取失败 - ${e.message}", ItemType.DEVICE_ID))
            }
            
            // 3. IMEI
            if (hasPhoneStatePermission()) {
                try {
                    val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                    val imei = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        telephonyManager.imei
                    } else {
                        @Suppress("DEPRECATION")
                        telephonyManager.deviceId
                    }
                    val imeiText = if (imei != null && imei.isNotEmpty()) {
                        "IMEI: $imei"
                    } else {
                        "IMEI: 无法获取"
                    }
                    deviceIdItems.add(ListItem(imeiText, ItemType.DEVICE_ID))
                } catch (e: SecurityException) {
                    Log.e("MainActivity", "读取 IMEI 权限不足", e)
                    deviceIdItems.add(ListItem("IMEI: 权限不足", ItemType.DEVICE_ID))
                } catch (e: Exception) {
                    Log.e("MainActivity", "读取 IMEI 失败", e)
                    deviceIdItems.add(ListItem("IMEI: 读取失败 - ${e.message}", ItemType.DEVICE_ID))
                }
            } else {
                deviceIdItems.add(ListItem("IMEI: 需要 READ_PHONE_STATE 权限", ItemType.DEVICE_ID))
            }
            
            // 4. Serial
            if (hasPhoneStatePermission()) {
                try {
                    val serial = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        Build.getSerial()
                    } else {
                        @Suppress("DEPRECATION")
                        Build.SERIAL
                    }
                    val serialText = if (serial != null && serial.isNotEmpty() && serial != "unknown") {
                        "Serial: $serial"
                    } else {
                        "Serial: 无法获取"
                    }
                    deviceIdItems.add(ListItem(serialText, ItemType.DEVICE_ID))
                } catch (e: SecurityException) {
                    Log.e("MainActivity", "读取 Serial 权限不足", e)
                    deviceIdItems.add(ListItem("Serial: 权限不足", ItemType.DEVICE_ID))
                } catch (e: Exception) {
                    Log.e("MainActivity", "读取 Serial 失败", e)
                    deviceIdItems.add(ListItem("Serial: 读取失败 - ${e.message}", ItemType.DEVICE_ID))
                }
            } else {
                deviceIdItems.add(ListItem("Serial: 需要 READ_PHONE_STATE 权限", ItemType.DEVICE_ID))
            }
            
        } catch (e: Exception) {
            Log.e("MainActivity", "获取设备标识符失败", e)
            deviceIdItems.add(ListItem("获取设备标识符失败: ${e.message}", ItemType.DEVICE_ID))
        }
        
        return deviceIdItems
    }

    /**
     * 刷新设备标识符（异步版本）
     */
    private fun refreshDeviceIdsAsync() {
        Thread {
            val deviceInfo = StringBuilder()
            
            try {
                // 1. AndroidId
                try {
                    val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                    if (androidId != null && androidId.isNotEmpty()) {
                        deviceInfo.append("AndroidId: $androidId\n")
                    } else {
                        deviceInfo.append("AndroidId: 无法获取\n")
                    }
                } catch (e: Exception) {
                    deviceInfo.append("AndroidId: 读取失败 - ${e.message}\n")
                }
                
                // 2. IMEI
                if (hasPhoneStatePermission()) {
                    try {
                        val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                        val imei = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            telephonyManager.imei
                        } else {
                            @Suppress("DEPRECATION")
                            telephonyManager.deviceId
                        }
                        if (imei != null && imei.isNotEmpty()) {
                            deviceInfo.append("IMEI: $imei\n")
                        } else {
                            deviceInfo.append("IMEI: 无法获取\n")
                        }
                    } catch (e: SecurityException) {
                        deviceInfo.append("IMEI: 权限不足\n")
                    } catch (e: Exception) {
                        deviceInfo.append("IMEI: 读取失败 - ${e.message}\n")
                    }
                } else {
                    deviceInfo.append("IMEI: 需要 READ_PHONE_STATE 权限\n")
                }
                
                // 3. Serial
                if (hasPhoneStatePermission()) {
                    try {
                        val serial = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            Build.getSerial()
                        } else {
                            @Suppress("DEPRECATION")
                            Build.SERIAL
                        }
                        if (serial != null && serial.isNotEmpty() && serial != "unknown") {
                            deviceInfo.append("Serial: $serial\n")
                        } else {
                            deviceInfo.append("Serial: 无法获取\n")
                        }
                    } catch (e: SecurityException) {
                        deviceInfo.append("Serial: 权限不足\n")
                    } catch (e: Exception) {
                        deviceInfo.append("Serial: 读取失败 - ${e.message}\n")
                    }
                } else {
                    deviceInfo.append("Serial: 需要 READ_PHONE_STATE 权限\n")
                }
                
                // 4. vendor.gsm.serial（耗时操作，在后台线程执行）
                try {
                    val vendorGsmSerial = SystemPropertyUtil.getVendorGsmSerial()
                    if (vendorGsmSerial.isNotEmpty()) {
                        deviceInfo.append("vendor.gsm.serial: $vendorGsmSerial\n")
                    } else {
                        deviceInfo.append("vendor.gsm.serial: 无法获取\n")
                    }
                } catch (e: Exception) {
                    deviceInfo.append("vendor.gsm.serial: 读取失败 - ${e.message}\n")
                }
                
            } catch (e: Exception) {
                deviceInfo.append("获取设备标识符失败: ${e.message}")
            }
            
            // 在主线程更新UI
            runOnUiThread {
                tvDeviceIds.text = deviceInfo.toString().trim()
            }
        }.start()
    }
    
    /**
     * 刷新设备标识符（同步版本，用于按钮点击）
     */
    private fun refreshDeviceIds() {
        refreshDeviceIdsAsync()
    }

    /**
     * 通过反射调用SystemProperties获取系统属性
     */
    private fun getSystemProperty(key: String, defaultValue: String = ""): String {
        return try {
            val systemPropertiesClass = Class.forName("android.os.SystemProperties")
            val getMethod = systemPropertiesClass.getMethod("get", String::class.java, String::class.java)
            getMethod.invoke(null, key, defaultValue) as String
        } catch (e: Exception) {
            Log.d("MainActivity", "无法通过反射获取系统属性 $key: ${e.message}")
            ""
        }
    }

    /**
     * 通过getprop命令获取系统属性
     */
    private fun getSystemPropertyViaGetprop(key: String): String {
        return try {
            val process = Runtime.getRuntime().exec("getprop $key")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val result = reader.readLine() ?: ""
            reader.close()
            process.destroy()
            result.trim()
        } catch (e: Exception) {
            Log.d("MainActivity", "无法通过getprop获取属性 $key: ${e.message}")
            ""
        }
    }

    /**
     * 按slot获取IMEI（基于系统属性）
     * @param slot 0表示第一张SIM卡，1表示第二张SIM卡
     * @return IMEI字符串，如果获取失败返回空字符串
     */
    private fun getImeiBySlot(slot: Int): String {
        return try {
            // 构造主属性名和备选属性列表
            val propKey = when (slot) {
                1 -> "ro.ril.oem.imei2"
                else -> "ro.ril.oem.imei"
            }

            val alternativeProps = when (slot) {
                1 -> listOf(
                    "ro.vendor.oem.imei2",
                    "ro.vendor.oem.imei.2",
                    "ro.ril.oem.imei2",
                    "ro.ril.oem.imei.2",
                    "ril.imei1",
                    "gsm.imei1"
                )
                else -> listOf(
                    "ro.vendor.oem.imei",
                    "ro.vendor.oem.imei1",
                    "ro.vendor.oem.imei.1",
                    "ro.ril.oem.imei1",
                    "ro.ril.oem.imei",
                    "ril.imei0",
                    "gsm.imei0"
                )
            }

            // 先尝试通过反射获取主属性
            var imei = getSystemProperty(propKey)
            
            if (imei.isEmpty()) {
                Log.d("MainActivity", "反射获取IMEI(slot=$slot)失败，尝试getprop命令")
                // 尝试通过getprop命令获取主属性
                imei = getSystemPropertyViaGetprop(propKey)
            }

            // 如果主属性获取失败，尝试备选属性
            if (imei.isEmpty()) {
                Log.d("MainActivity", "主属性获取失败，尝试备选属性")
                for (prop in alternativeProps) {
                    imei = getSystemProperty(prop)
                    if (imei.isEmpty()) {
                        imei = getSystemPropertyViaGetprop(prop)
                    }
                    if (imei.isNotEmpty() && imei != "unknown" && imei != "null") {
                        Log.d("MainActivity", "通过备选属性 $prop 获取IMEI(slot=$slot) 成功: $imei")
                        break
                    }
                }
            } else {
                Log.d("MainActivity", "通过主属性获取IMEI(slot=$slot) 成功: $imei")
            }

            Log.d("MainActivity", "最终IMEI(slot=$slot)获取结果: $imei")
            imei

        } catch (e: Exception) {
            Log.e("MainActivity", "获取IMEI(slot=$slot)失败: ${e.message}")
            ""
        }
    }

    /**
     * 从系统属性中获取IMEI相关信息
     */
    private fun getImeiFromSystemProperties(): StringBuilder {
        val propertiesInfo = StringBuilder()
        propertiesInfo.append("=== 从系统属性获取 ===\n")
        
        // 常见的IMEI相关系统属性键
        val imeiPropertyKeys = listOf(
            "ro.telephony.imei",
            "ro.ril.oem.imei",
            "ro.vendor.oem.imei",
            "ril.IMEI",
            "ril.imei",
            "gsm.imei",
            "ro.ril.imei",
            "persist.radio.imei",
            "ro.serialno",
            "ro.boot.serialno",
            "ril.serialnumber"
        )
        
        var foundAny = false
        for (key in imeiPropertyKeys) {
            // 先尝试通过反射获取
            var value = getSystemProperty(key)
            if (value.isEmpty()) {
                // 如果反射失败，尝试通过getprop命令
                value = getSystemPropertyViaGetprop(key)
            }
            
            if (value.isNotEmpty() && value != "unknown" && value != "null") {
                propertiesInfo.append("$key: $value\n")
                foundAny = true
            }
        }
        
        // 尝试获取双卡IMEI - 使用按slot获取的方法
        for (slotIndex in 0..1) {
            val imei = getImeiBySlot(slotIndex)
            if (imei.isNotEmpty()) {
                propertiesInfo.append("IMEI (SIM$slotIndex): $imei\n")
                foundAny = true
            }
        }
        
        // 如果按slot获取失败，尝试直接获取已知的双卡IMEI属性
        if (!foundAny) {
            val dualSimKeys = listOf(
                "ro.ril.oem.imei1",
                "ro.ril.oem.imei2",
                "ro.vendor.oem.imei2",
                "ro.vendor.oem.imei.1",
                "ro.vendor.oem.imei.2",
                "ro.ril.oem.imei.1",
                "ro.ril.oem.imei.2"
            )
            
            for (key in dualSimKeys) {
                var value = getSystemProperty(key)
                if (value.isEmpty()) {
                    value = getSystemPropertyViaGetprop(key)
                }
                
                if (value.isNotEmpty() && value != "unknown" && value != "null") {
                    propertiesInfo.append("$key: $value\n")
                    foundAny = true
                }
            }
        }
        
        // 最后尝试通过slotIndex动态获取其他格式的属性
        for (slotIndex in 0..1) {
            val slotKeys = listOf(
                "ril.imei$slotIndex",
                "gsm.imei$slotIndex",
                "ro.ril.imei$slotIndex",
                "persist.radio.imei$slotIndex"
            )
            
            for (key in slotKeys) {
                var value = getSystemProperty(key)
                if (value.isEmpty()) {
                    value = getSystemPropertyViaGetprop(key)
                }
                
                if (value.isNotEmpty() && value != "unknown" && value != "null") {
                    propertiesInfo.append("$key (SIM$slotIndex): $value\n")
                    foundAny = true
                }
            }
        }
        
        if (!foundAny) {
            propertiesInfo.append("未找到IMEI相关系统属性\n")
        }
        
        return propertiesInfo
    }

    /**
     * 刷新IMEI信息（异步版本）
     */
    private fun refreshImeiAsync() {
        Thread {
            val imeiInfo = StringBuilder()
            
            // 首先尝试从系统属性获取IMEI（不需要权限，但耗时）
            val propertiesInfo = getImeiFromSystemProperties()
            imeiInfo.append(propertiesInfo)
            imeiInfo.append("\n")
            
            // 然后通过TelephonyManager获取（需要权限）
            if (!hasPhoneStatePermission()) {
                imeiInfo.append("=== 通过TelephonyManager获取 ===\n")
                imeiInfo.append("需要 READ_PHONE_STATE 权限\n")
                // 在主线程更新UI并请求权限
                runOnUiThread {
                    tvImei.text = imeiInfo.toString()
                    checkAndRequestPhoneStatePermission()
                }
                return@Thread
            }

        try {
            imeiInfo.append("=== 通过TelephonyManager获取 ===\n")
            val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            
            // 获取IMEI（Android 8.0及以上）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    val imei = telephonyManager.imei
                    if (imei != null && imei.isNotEmpty()) {
                        imeiInfo.append("IMEI: $imei\n")
                    } else {
                        imeiInfo.append("IMEI: 无法获取\n")
                    }
                } catch (e: Exception) {
                    imeiInfo.append("IMEI: 获取失败 - ${e.message}\n")
                }
                
                // 获取IMEI2（双卡设备）
                try {
                    val imei2 = telephonyManager.getImei(1) // slotIndex = 1 for second SIM
                    if (imei2 != null && imei2.isNotEmpty()) {
                        imeiInfo.append("IMEI2: $imei2\n")
                    }
                } catch (e: Exception) {
                    // 可能没有第二张SIM卡，忽略错误
                }
            } else {
                // Android 8.0以下使用deviceId
                @Suppress("DEPRECATION")
                val deviceId = telephonyManager.deviceId
                if (deviceId != null && deviceId.isNotEmpty()) {
                    imeiInfo.append("Device ID: $deviceId\n")
                } else {
                    imeiInfo.append("Device ID: 无法获取\n")
                }
            }
            
            // 获取MEID（CDMA设备）
            try {
                val meid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    telephonyManager.meid
                } else {
                    @Suppress("DEPRECATION")
                    telephonyManager.deviceId
                }
                if (meid != null && meid.isNotEmpty() && meid != telephonyManager.imei) {
                    imeiInfo.append("MEID: $meid\n")
                }
            } catch (e: Exception) {
                // 忽略MEID获取错误
            }
            
            // 获取设备序列号
            try {
                val serial = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Build.getSerial()
                } else {
                    @Suppress("DEPRECATION")
                    Build.SERIAL
                }
                if (serial != null && serial.isNotEmpty() && serial != "unknown") {
                    imeiInfo.append("Serial: $serial\n")
                }
            } catch (e: SecurityException) {
                imeiInfo.append("Serial: 权限不足\n")
            } catch (e: Exception) {
                imeiInfo.append("Serial: 获取失败 - ${e.message}\n")
            }
            
            // 获取Android ID
            try {
                val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                if (androidId != null && androidId.isNotEmpty()) {
                    imeiInfo.append("Android ID: $androidId")
                }
            } catch (e: Exception) {
                imeiInfo.append("Android ID: 获取失败 - ${e.message}")
            }
            
        } catch (e: SecurityException) {
            imeiInfo.append("权限不足，无法通过TelephonyManager获取IMEI\n")
            Log.e("MainActivity", "获取IMEI权限不足", e)
        } catch (e: Exception) {
            imeiInfo.append("通过TelephonyManager获取失败: ${e.message}\n")
            Log.e("MainActivity", "获取IMEI失败", e)
        }
        
        // 在主线程更新UI
        runOnUiThread {
            tvImei.text = if (imeiInfo.isNotEmpty()) {
                imeiInfo.toString().trim()
            } else {
                "无法获取IMEI信息"
            }
        }
        }.start()
    }
    
    /**
     * 获取并显示IMEI信息到TextView（同步版本，用于按钮点击）
     */
    private fun getAndDisplayImei() {
        refreshImeiAsync()
    }

    /**
     * 加载初始数据
     */
    private fun loadInitialData() {
        // 异步刷新所有卡片显示
        refreshAllDataAsync()
        
        // 应用列表加载是耗时操作，移到后台线程
        dataList.clear()
        dataList.add(ListItem("=== 应用列表 ===", ItemType.APP_LIST))
        dataList.add(ListItem("正在加载应用列表...", ItemType.APP_LIST))
        adapter.notifyDataSetChanged()
        
        // 在后台线程加载应用列表
        Thread {
            try {
                val apps = getAppListForDisplay()
                // 在主线程更新UI
                runOnUiThread {
                    dataList.clear()
                    dataList.add(ListItem("=== 应用列表 ===", ItemType.APP_LIST))
                    dataList.addAll(apps)
                    adapter.notifyDataSetChanged()
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "加载应用列表失败", e)
                runOnUiThread {
                    dataList.clear()
                    dataList.add(ListItem("=== 应用列表 ===", ItemType.APP_LIST))
                    dataList.add(ListItem("加载应用列表失败: ${e.message}", ItemType.APP_LIST))
                    adapter.notifyDataSetChanged()
                }
            }
        }.start()
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
     * 刷新无障碍服务信息（异步版本）
     */
    private fun refreshAccessibilityServicesAsync() {
        Thread {
            val accessibilityManager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val enabledServices = accessibilityManager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            )
            
            val serviceInfo = StringBuilder()
            
            if (enabledServices.isEmpty()) {
                serviceInfo.append("没有启用的无障碍服务")
            } else {
                serviceInfo.append("已启用 ${enabledServices.size} 个无障碍服务:\n\n")
                
                for (serviceInfoItem in enabledServices) {
                    val serviceName = try {
                        val packageManager = packageManager
                        val appInfo = packageManager.getApplicationInfo(serviceInfoItem.resolveInfo.serviceInfo.packageName, 0)
                        packageManager.getApplicationLabel(appInfo).toString()
                    } catch (e: Exception) {
                        serviceInfoItem.resolveInfo.serviceInfo.packageName
                    }
                    
                    val packageName = serviceInfoItem.resolveInfo.serviceInfo.packageName
                    val isRunning = isAccessibilityServiceRunning(packageName, serviceInfoItem.resolveInfo.serviceInfo.name)
                    val statusText = if (isRunning) "运行中" else "已停止"
                    
                    serviceInfo.append("• $serviceName\n")
                    serviceInfo.append("  包名: $packageName\n")
                    serviceInfo.append("  状态: $statusText\n\n")
                }
            }
            
            runOnUiThread {
                tvAccessibilityServices.text = serviceInfo.toString().trim()
            }
        }.start()
    }
    
    /**
     * 刷新无障碍服务信息（同步版本，用于按钮点击）
     */
    private fun refreshAccessibilityServices() {
        refreshAccessibilityServicesAsync()
    }

    /**
     * 检查VPN状态
     */
    private fun checkVpnStatus(): List<ListItem> {
        val vpnItems = ArrayList<ListItem>()

        try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            // 获取所有网络连接
            val networks = connectivityManager.allNetworks
            var vpnConnected = false
            val vpnNetworks = ArrayList<String>()

            for (network in networks) {
                val networkCapabilities = connectivityManager.getNetworkCapabilities(network)

                if (networkCapabilities != null) {
                    // 检查是否是VPN连接
                    if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                        vpnConnected = true

                        // 获取VPN的详细信息
                        val networkInfo = StringBuilder()
                        networkInfo.append("VPN网络: $network\n")

                        // 检查VPN是否已验证
                        if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                            networkInfo.append("状态: 已验证\n")
                        } else {
                            networkInfo.append("状态: 未验证\n")
                        }

                        // 检查是否有互联网访问
                        if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                            networkInfo.append("互联网: 可用\n")
                        } else {
                            networkInfo.append("互联网: 不可用\n")
                        }

                        // 检查其他传输类型
                        val transports = mutableListOf<String>()
                        if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                            transports.add("WiFi")
                        }
                        if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                            transports.add("移动数据")
                        }
                        if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                            transports.add("以太网")
                        }

                        if (transports.isNotEmpty()) {
                            networkInfo.append("底层传输: ${transports.joinToString(", ")}")
                        }

                        vpnNetworks.add(networkInfo.toString())
                    }
                }
            }

            if (vpnConnected) {
                vpnItems.add(ListItem("=== VPN状态: 已连接 ===", ItemType.VPN_STATUS))
                vpnItems.add(ListItem("检测到 ${vpnNetworks.size} 个VPN连接", ItemType.VPN_STATUS))
                vpnItems.add(ListItem("", ItemType.VPN_STATUS)) // 空行

                for (vpnInfo in vpnNetworks) {
                    vpnItems.add(ListItem(vpnInfo, ItemType.VPN_STATUS))
                    vpnItems.add(ListItem("", ItemType.VPN_STATUS)) // 空行分隔
                }
            } else {
                vpnItems.add(ListItem("=== VPN状态: 未连接 ===", ItemType.VPN_STATUS))
                vpnItems.add(ListItem("当前没有检测到VPN连接", ItemType.VPN_STATUS))
            }

            // 添加网络统计信息
            vpnItems.add(ListItem("", ItemType.VPN_STATUS)) // 空行
            vpnItems.add(ListItem("网络统计:\n总网络数: ${networks.size}\nVPN连接数: ${vpnNetworks.size}", ItemType.VPN_STATUS))

        } catch (e: Exception) {
            vpnItems.add(ListItem("VPN状态检测失败: ${e.message}", ItemType.VPN_STATUS))
            Log.e("MainActivity", "检测VPN状态失败", e)
        }

        return vpnItems
    }

    /**
     * 刷新VPN状态（异步版本）
     */
    private fun refreshVpnStatusAsync() {
        Thread {
            val vpnInfo = StringBuilder()
            
            try {
                val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val networks = connectivityManager.allNetworks
                var vpnConnected = false
                val vpnNetworks = ArrayList<String>()

                for (network in networks) {
                    val networkCapabilities = connectivityManager.getNetworkCapabilities(network)

                    if (networkCapabilities != null) {
                        if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                            vpnConnected = true
                            val networkInfo = StringBuilder()
                            
                            if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                                networkInfo.append("状态: 已验证\n")
                            } else {
                                networkInfo.append("状态: 未验证\n")
                            }

                            if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                                networkInfo.append("互联网: 可用\n")
                            } else {
                                networkInfo.append("互联网: 不可用\n")
                            }

                            vpnNetworks.add(networkInfo.toString())
                        }
                    }
                }

                if (vpnConnected) {
                    vpnInfo.append("VPN 状态: 已连接\n")
                    vpnInfo.append("检测到 ${vpnNetworks.size} 个 VPN 连接\n\n")
                    for ((index, info) in vpnNetworks.withIndex()) {
                        vpnInfo.append("VPN 连接 ${index + 1}:\n$info\n")
                    }
                } else {
                    vpnInfo.append("VPN 状态: 未连接\n")
                    vpnInfo.append("当前没有检测到 VPN 连接")
                }

            } catch (e: Exception) {
                vpnInfo.append("VPN 状态检测失败: ${e.message}")
                Log.e("MainActivity", "检测VPN状态失败", e)
            }
            
            runOnUiThread {
                tvVpnStatus.text = vpnInfo.toString().trim()
            }
        }.start()
    }
    
    /**
     * 刷新VPN状态（同步版本，用于按钮点击）
     */
    private fun refreshVpnStatus() {
        refreshVpnStatusAsync()
    }

    /**
     * 获取锁屏状态
     */
    private fun getLockScreenStatus(): List<ListItem> {
        val lockScreenItems = ArrayList<ListItem>()

        try {
            val status = LockScreenDetection.detectLockScreenStatus(this)
            val description = LockScreenDetection.getStatusDescription(status)
            
            // 将描述文本按行分割，每行作为一个 ListItem
            val lines = description.split("\n")
            for (line in lines) {
                if (line.isNotEmpty()) {
                    lockScreenItems.add(ListItem(line, ItemType.LOCK_SCREEN))
                }
            }
        } catch (e: Exception) {
            lockScreenItems.add(ListItem("锁屏状态检测失败: ${e.message}", ItemType.LOCK_SCREEN))
            Log.e("MainActivity", "获取锁屏状态失败", e)
        }

        return lockScreenItems
    }

    /**
     * 刷新锁屏状态（异步版本）
     */
    private fun refreshLockScreenStatusAsync() {
        val context = this
        Thread {
            try {
                val status = LockScreenDetection.detectLockScreenStatus(context)
                val description = LockScreenDetection.getStatusDescription(status)
                runOnUiThread {
                    tvLockScreenStatus.text = description.trim()
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "获取锁屏状态失败", e)
                runOnUiThread {
                    tvLockScreenStatus.text = "锁屏状态检测失败: ${e.message}"
                }
            }
        }.start()
    }
    
    /**
     * 刷新锁屏状态（同步版本，用于按钮点击）
     */
    private fun refreshLockScreenStatus() {
        refreshLockScreenStatusAsync()
    }

    /**
     * 获取并打印 vendor.gsm.serial
     */
    private fun getAndPrintVendorGsmSerial() {
        try {
            val vendorGsmSerial = SystemPropertyUtil.getVendorGsmSerial()
            if (vendorGsmSerial.isNotEmpty()) {
                Log.d("MainActivity", "=== vendor.gsm.serial ===")
                Log.d("MainActivity", "vendor.gsm.serial: $vendorGsmSerial")
                Log.d("MainActivity", "========================")
            } else {
                Log.d("MainActivity", "vendor.gsm.serial: 未获取到值或为空")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "获取 vendor.gsm.serial 失败: ${e.message}", e)
        }
    }

    private fun testAdbProcess(){

    }

    private fun setupRecyclerView() {
        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = InfiniteScrollAdapter(dataList)
        recyclerView.adapter = adapter
        // 优化 RecyclerView 性能
        recyclerView.setHasFixedSize(true)
        recyclerView.itemAnimator = null // 禁用动画以提高性能

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

            Log.d("dispatchTouchEvent Pressure", "First Pressure" + ev.pressure)
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
