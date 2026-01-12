package com.example.testauto.lsposed

import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed 模块入口类
 * 仅 Hook com.android.providers.media.module，修复 CVE-2024-43093 漏洞
 * 
 * ## 为什么只需要 Hook com.android.providers.media.module？
 * 
 * **核心原理**：
 * - 所有应用的 SAF (Storage Access Framework) 访问都会经过 `com.android.providers.media.module`
 * - 当应用使用 DocumentFile、SAF URI 等方式访问文件时，都会调用 `ExternalStorageProvider` 的方法
 * - 零宽字符攻击主要是通过 SAF 进行的，所以只需要在 `ExternalStorageProvider` 中拦截即可
 * 
 * **优势**：
 * - 不需要勾选任何三方应用，只需要勾选系统模块 `com.android.providers.media.module`
 * - 所有 SAF 访问都会被拦截，覆盖范围广
 * - 性能影响小，只在系统模块中 Hook
 * 
 * **重要说明**：
 * - `File.listFiles()` 是直接的文件系统调用，**不会**经过 `com.android.providers.media.module`
 * - 只有使用 SAF (Storage Access Framework) 时才会经过这里：
 *   - `DocumentFile.listFiles()` ✅ 会经过
 *   - SAF URI 访问 ✅ 会经过
 *   - `File.listFiles()` ❌ 不会经过（直接调用文件系统）
 * - 零宽字符攻击主要是通过 SAF 进行的（绕过 Scoped Storage），所以这个方案已经足够
 * - 如果攻击者使用 `File.listFiles()` 直接访问，需要应用有直接文件访问权限，此时不需要零宽字符
 * 
 * 使用说明：
 * 1. 在 LSPosed Manager 中启用此模块
 * 2. 作用域设置：仅勾选 com.android.providers.media.module（已默认勾选）
 *    不需要勾选任何三方应用
 * 3. 重启设备或重启媒体提供者服务
 */
class MainHook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "MorphRemover"
        private const val TARGET_PACKAGE = "com.android.providers.media.module"
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // 只 Hook com.android.providers.media.module
            // 所有 SAF 访问都会经过这个模块，所以只需要在这里 Hook 即可
            if (lpparam.packageName != TARGET_PACKAGE) {
                return // 不是目标包，直接返回
            }
            
            Log.d(TAG, "Initializing hook for target package: ${lpparam.packageName}")
            XposedBridge.log("MorphRemoverHook: Enabled for ${lpparam.packageName}")
            
            // 在 media.module 中 Hook ExternalStorageProvider
            // 所有应用的 SAF 访问都会经过这里，所以只需要 Hook 这一个模块
            MorphRemoverHook().handleLoadPackage(lpparam)
        } catch (e: Throwable) {
            Log.e(TAG, "Error in handleLoadPackage", e)
            XposedBridge.log("MorphRemoverHook error: ${e.message}")
            XposedBridge.log(e)
        }
    }
}

