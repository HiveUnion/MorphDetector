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
 * - 在 Android 10+ (Scoped Storage) 下，文件访问会经过 `com.android.providers.media.module`
 * - 所有应用的 SAF (Storage Access Framework) 访问都会经过 `ExternalStorageProvider`
 * - 即使是 `File.listFiles()` / `File.list()` 等直接文件系统调用，在某些路径下也会被重定向到 `ExternalStorageProvider`
 * - 零宽字符攻击会通过这些文件访问方法进行，所以在 `ExternalStorageProvider` 中拦截即可
 * 
 * **优势**：
 * - 不需要勾选任何三方应用，只需要勾选系统模块 `com.android.providers.media.module`
 * - 所有文件访问（包括 File API 和 SAF）都会被拦截，覆盖范围广
 * - 性能影响小，只在系统模块中 Hook
 * 
 * **重要说明**：
 * - 以下文件访问方式都会经过 `com.android.providers.media.module`：
 *   - `DocumentFile.listFiles()` ✅ 会经过
 *   - SAF URI 访问 ✅ 会经过
 *   - `File.listFiles()` ✅ 会经过（在 Scoped Storage 下会被重定向）
 *   - `File.list()` ✅ 会经过（在 Scoped Storage 下会被重定向）
 * - 通过 Hook `ExternalStorageProvider` 的方法，可以拦截所有通过零宽字符绕过 Scoped Storage 的攻击
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

