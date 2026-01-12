package com.example.testauto.lsposed

import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed 模块入口类
 * 仅 Hook com.android.providers.media.module，修复 CVE-2024-43093 漏洞
 * 
 * 使用说明：
 * 1. 在 LSPosed Manager 中启用此模块
 * 2. 作用域设置：仅勾选 com.android.providers.media.module
 *    不要勾选其他应用
 * 3. 重启设备或重启媒体提供者服务
 */
class MainHook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "MorphRemover"
        private const val TARGET_PACKAGE = "com.android.providers.media.module"
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // 记录所有包加载，用于调试
            XposedBridge.log("MorphRemoverHook: handleLoadPackage called for package: ${lpparam.packageName}")
            Log.d(TAG, "handleLoadPackage called for package: ${lpparam.packageName}")
            
            // 对 com.android.providers.media.module 和所有应用都生效
            // 因为 File.listFiles() 和 DocumentFile.listFiles() 可能在任意应用中被调用
            if (lpparam.packageName == TARGET_PACKAGE) {
                Log.d(TAG, "Initializing hook for target package: ${lpparam.packageName}")
                XposedBridge.log("MorphRemoverHook: Enabled for ${lpparam.packageName}")
                
                // 在 media.module 中 Hook ExternalStorageProvider
                MorphRemoverHook().handleLoadPackage(lpparam)
            } else {
                // 在其他应用中 Hook File 和 DocumentFile 方法
                // 这样可以拦截所有应用中的文件列举操作
                Log.d(TAG, "Initializing hook for package: ${lpparam.packageName} (File/DocumentFile hooks)")
                XposedBridge.log("MorphRemoverHook: Enabled File hooks for ${lpparam.packageName}")
                
                MorphRemoverHook().handleLoadPackage(lpparam)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error in handleLoadPackage", e)
            XposedBridge.log("MorphRemoverHook error: ${e.message}")
            XposedBridge.log(e)
        }
    }
}

