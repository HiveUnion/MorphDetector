package com.example.testauto

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import androidx.core.content.ContextCompat.getSystemService
import java.io.File


object AutoJsDetection {
    private const val TAG = "AutoJsDetection"

    /**
     * 检测系统中是否有可疑的 Auto.js 操作`
     * @param context 应用上下文
     * @param event   输入事件，用于检测模拟输入
     * @return true 如果检测到可疑操作, false 否则
     */
    fun isAutoJsOperationDetected(context: Context, event: InputEvent): Boolean {
        if (scanForAutoJsApks(context)) {
            return true
        }

        if (detectSuspiciousAccessibilityService(context)) {
            Log.w(TAG, "检测到可疑的无障碍服务")
            return true
        }

        if (detectSimulatedInput(event)) {
            Log.w(TAG, "检测到模拟的输入事件")
            return true
        }

        if (detectSuspiciousProcesses(context)) {
            Log.w(TAG, "检测到可疑的进程")
            return true
        }
        scanForAutoJsProviders(context);
        scanForAutoJsReceivers(context);

        return false
    }

    // 1. 检测系统中的 Auto.js APK
    private fun scanForAutoJsApks(context: Context): Boolean {
        val pm = context.packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        for (packageInfo in packages) {
            val apkPath = packageInfo.sourceDir
            if (isAutoJsApk(File(apkPath))) {
                Log.w(TAG, "检测到 Auto.js 打包的 APK：$apkPath")
                return true
            }
        }

        return false
    }

    private fun scanForAutoJsProviders(context: Context): Boolean {
        val pm = context.packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_PROVIDERS)

        for (packageInfo in packages) {
            Log.w(TAG, "Providers $packageInfo")
        }

        return false
    }

    private fun scanForAutoJsReceivers(context: Context): Boolean {
        val pm = context.packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_RECEIVERS)

        for (packageInfo in packages) {
            Log.w(TAG, "Receivers $packageInfo")
        }

        return false
    }


    // 2. 检查 APK 是否包含 Auto.js 的特征
    private fun isAutoJsApk(apkFile: File): Boolean {
        val apkFilePath = apkFile.absolutePath
        return apkFilePath.contains("autojs") || apkFilePath.contains("stardust")
    }

    // 3. 检测可疑的无障碍服务
    private fun detectSuspiciousAccessibilityService(context: Context): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        Log.w(TAG, "enabledServices: $enabledServices")

        if (!TextUtils.isEmpty(enabledServices)) {
            val serviceList = enabledServices.split(":".toRegex()).dropLastWhile { it.isEmpty() }
                .toTypedArray()
            for (service in serviceList) {
                Log.w(TAG, "进程列表 $service")
                if (service.contains("autojs") || service.contains("stardust") || service.contains("auto.js")) {
                    return true
                }
            }
        }
        return false
    }

    // 4. 检测模拟输入事件
    private fun detectSimulatedInput(event: InputEvent): Boolean {
        if (event is MotionEvent) {
            val source = event.source
            return (source and InputDevice.SOURCE_CLASS_POINTER) == 0
        }
        return false
    }

    // 5. 检测可疑的进程
    private fun detectSuspiciousProcesses(context: Context): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val runningProcesses = activityManager.runningAppProcesses

        for (processInfo in runningProcesses) {
            val processName = processInfo.processName
            if (processName.contains("autojs") || processName.contains("stardust")) {
                return true
            }
        }
        return false
    }
}