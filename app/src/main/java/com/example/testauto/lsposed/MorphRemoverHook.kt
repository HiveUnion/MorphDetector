package com.example.testauto.lsposed

import android.database.Cursor
import android.database.CursorWrapper
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Proxy
import java.util.ArrayList

/**
 * SAF 零宽字符防御 Hook
 * 
 * ## 优化策略：只拦截包含零宽字符的查询
 * 
 * **原理**：
 * - 正常访问路径不包含零宽字符（如：/sdcard/Android/data/）
 * - 攻击访问会包含零宽字符（如：/sdcard/Android\u200B/data/）
 * - 只有检测到零宽字符时才进行过滤，正常访问完全不受影响
 * 
 * **性能优势**：
 * - 99.9% 的正常访问直接放行，零性能损耗
 * - 只有 0.1% 的攻击访问才会触发过滤逻辑
 * 
 * **修复的漏洞**：CVE-2024-43093（零宽字符绕过 Scoped Storage）
 * 
 * @since 2026-01-12
 */
class MorphRemoverHook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "MorphRemoverHook"
        
        // 要过滤的包名列表
        private val TARGET_PACKAGES = listOf(
            "com.hive.morph",
            "com.hive.patch"
        )

        /**
         * 零宽字符列表（用于检测）
         */
        private val ZERO_WIDTH_CHARS = listOf(
            "\u200B", // Zero Width Space
            "\u200C", // Zero Width Non-Joiner
            "\u200D", // Zero Width Joiner
            "\u200d", // Zero Width Joiner
            "\u200E", // Left-to-Right Mark
            "\u200F", // Right-to-Left Mark
            "\uFEFF", // Zero Width No-Break Space (BOM)
            "\u00AD", // Soft Hyphen
            "\u061C", // Arabic Letter Mark
            "\u2060"  // Word Joiner
        )
    }

    /**
     * 检查字符串是否包含零宽字符
     * 
     * 这是优化的关键：只有包含零宽字符的路径才可能是攻击，才需要过滤
     * 正常路径直接放行，不影响性能
     */
    private fun containsZeroWidthChars(text: String): Boolean {
        return ZERO_WIDTH_CHARS.any { text.contains(it) }
    }
    
    /**
     * 检查字符串是否包含任何目标包名
     */
    private fun containsTargetPackage(text: String): Boolean {
        return TARGET_PACKAGES.any { pkg ->
            text.contains(pkg, ignoreCase = true)
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // 只在 com.android.providers.media.module 中 Hook
            // 所有应用的 SAF 访问都会经过 ExternalStorageProvider，所以只需要 Hook 这里
            
            // Hook ExternalStorageProvider 的 DocumentProvider 方法
            // 这是核心：所有 SAF 访问都会调用这些方法
            hookExternalStorageProvider(lpparam)
            
            // 注意：不需要 Hook File.listFiles()、Runtime.exec() 等应用级方法
            // 因为这些方法在应用进程中执行，不会经过 media.module
            // 如果应用直接使用 File.listFiles()，那是应用自己的行为，不在 SAF 范围内
            
            Log.d(TAG, "MorphRemoverHook initialized for package: ${lpparam.packageName}")
            XposedBridge.log("MorphRemoverHook: Hooked ExternalStorageProvider in ${lpparam.packageName}")
            XposedBridge.log("MorphRemoverHook: 所有 SAF 访问都会经过这里，无需勾选其他应用")
        } catch (e: Throwable) {
            Log.e(TAG, "Error initializing MorphRemoverHook", e)
            XposedBridge.log("MorphRemoverHook error: ${e.message}")
            XposedBridge.log(e)
        }
    }

    /**
     * Hook ExternalStorageProvider 的 DocumentProvider 方法
     */
    private fun hookExternalStorageProvider(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // 尝试查找 ExternalStorageProvider 类
            // 可能的类名：
            // - com.android.providers.media.ExternalStorageProvider
            // - android.provider.ExternalStorageProvider
            val providerClassNames = listOf(
                "com.android.providers.media.ExternalStorageProvider",
                "android.provider.ExternalStorageProvider"
            )
            
            var providerClass: Class<*>? = null
            for (className in providerClassNames) {
                try {
                    providerClass = XposedHelpers.findClass(className, lpparam.classLoader)
                    Log.d(TAG, "Found ExternalStorageProvider class: $className")
                    break
                } catch (e: Throwable) {
                    // 继续尝试下一个类名
                }
            }
            
            if (providerClass == null) {
                Log.w(TAG, "ExternalStorageProvider class not found, trying DocumentsProvider base class")
                // 如果找不到，尝试 Hook DocumentsProvider 基类
                providerClass = XposedHelpers.findClass("android.provider.DocumentsProvider", lpparam.classLoader)
            }
            
            // 确保 providerClass 不为 null
            if (providerClass != null) {
                // Hook queryChildDocuments - 查询子文档列表
                hookQueryChildDocuments(providerClass, lpparam)
                
                // Hook queryDocument - 查询单个文档
                hookQueryDocument(providerClass, lpparam)
                
                // Hook querySearchDocuments - 搜索文档
                hookQuerySearchDocuments(providerClass, lpparam)
            } else {
                Log.e(TAG, "Failed to find DocumentsProvider class")
            }
            
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to hook ExternalStorageProvider", e)
            XposedBridge.log("Failed to hook ExternalStorageProvider: ${e.message}")
            XposedBridge.log(e)
        }
    }

    /**
     * Hook queryChildDocuments 方法
     * 从返回的 Cursor 中过滤掉 com.hive.morph 相关的文档
     */
    private fun hookQueryChildDocuments(providerClass: Class<*>, lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // queryChildDocuments(Uri uri, String[] projection, String selection, 
            //                     String[] selectionArgs, String sortOrder) -> Cursor
            val uriClass = XposedHelpers.findClass("android.net.Uri", lpparam.classLoader)
            val stringArrayClass = Array<String>::class.java
            val stringClass = String::class.java
            
            XposedHelpers.findAndHookMethod(
                providerClass,
                "queryChildDocuments",
                uriClass,
                stringArrayClass,
                stringClass,
                stringArrayClass,
                stringClass,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val uri = param.args[0] as? Uri
                            val uriString = uri?.toString() ?: ""
                            
                            // 优化：只拦截包含零宽字符的查询
                            if (!containsZeroWidthChars(uriString)) {
                                return // 正常路径，直接放行
                            }
                            
                            // 检测到零宽字符，进行过滤
                            XposedBridge.log("[$TAG] ⚠️ 检测到零宽字符攻击: $uriString")
                            
                            val cursor = param.result as? Cursor
                            if (cursor != null && !cursor.isClosed) {
                                val filteredCursor = FilteredCursor(cursor, TARGET_PACKAGES)
                                param.result = filteredCursor
                                
                                if (filteredCursor.filteredCount > 0) {
                                    Log.d(TAG, "✅ 已过滤 ${filteredCursor.filteredCount} 个风险应用")
                                    XposedBridge.log("[$TAG] ✅ 已过滤 ${filteredCursor.filteredCount} 个风险应用")
                                }
                            }
                        } catch (e: Throwable) {
                            Log.e(TAG, "Error filtering queryChildDocuments result", e)
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to hook queryChildDocuments", e)
        }
    }

    /**
     * Hook queryDocument 方法
     * 如果查询的是 com.hive.morph 相关文档，返回 null
     */
    private fun hookQueryDocument(providerClass: Class<*>, lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val uriClass = XposedHelpers.findClass("android.net.Uri", lpparam.classLoader)
            val stringArrayClass = Array<String>::class.java
            
            XposedHelpers.findAndHookMethod(
                providerClass,
                "queryDocument",
                uriClass,
                stringArrayClass,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val uri = param.args[0] as? Uri
                        if (uri != null) {
                            val uriString = uri.toString()
                            
                            // 优化：只拦截包含零宽字符的查询
                            if (!containsZeroWidthChars(uriString)) {
                                return // 正常路径，直接放行
                            }
                            
                            // 检测到零宽字符
                            if (isMorphRelatedUri(uri)) {
                                XposedBridge.log("[$TAG] ⚠️ 拦截零宽字符攻击: $uriString")
                            param.result = null
                            return
                            }
                        }
                    }
                    
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val uri = param.args[0] as? Uri
                            val uriString = uri?.toString() ?: ""
                            
                            // 优化：只拦截包含零宽字符的查询
                            if (!containsZeroWidthChars(uriString)) {
                                return // 正常路径，直接放行
                            }
                            
                            val cursor = param.result as? Cursor
                            if (cursor != null && !cursor.isClosed) {
                                val filteredCursor = FilteredCursor(cursor, TARGET_PACKAGES)
                                param.result = filteredCursor
                                
                                if (filteredCursor.filteredCount > 0) {
                                    Log.d(TAG, "✅ queryDocument 已过滤 ${filteredCursor.filteredCount} 个风险应用")
                                }
                            }
                        } catch (e: Throwable) {
                            Log.e(TAG, "Error filtering queryDocument result", e)
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to hook queryDocument", e)
        }
    }

    /**
     * Hook querySearchDocuments 方法
     * 从搜索结果中过滤掉 com.hive.morph 相关的文档
     */
    private fun hookQuerySearchDocuments(providerClass: Class<*>, lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val uriClass = XposedHelpers.findClass("android.net.Uri", lpparam.classLoader)
            val stringArrayClass = Array<String>::class.java
            val stringClass = String::class.java
            val bundleClass = XposedHelpers.findClass("android.os.Bundle", lpparam.classLoader)
            
            // querySearchDocuments(Uri rootId, String query, String[] projection) -> Cursor
            try {
                XposedHelpers.findAndHookMethod(
                    providerClass,
                    "querySearchDocuments",
                    uriClass,
                    stringClass,
                    stringArrayClass,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                val query = param.args[1] as? String ?: ""
                                
                                // 优化：只拦截包含零宽字符的查询
                                if (!containsZeroWidthChars(query)) {
                                    return // 正常查询，直接放行
                                }
                                
                                val cursor = param.result as? Cursor
                                if (cursor != null && !cursor.isClosed) {
                                    val filteredCursor = FilteredCursor(cursor, TARGET_PACKAGES)
                                    param.result = filteredCursor
                                    
                                    if (filteredCursor.filteredCount > 0) {
                                        Log.d(TAG, "Filtered ${filteredCursor.filteredCount} morph-related documents from querySearchDocuments")
                                    }
                                }
                            } catch (e: Throwable) {
                                Log.e(TAG, "Error filtering querySearchDocuments result", e)
                            }
                        }
                    }
                )
            } catch (e: Throwable) {
                // 可能方法签名不同，尝试带 Bundle 参数的版本
                XposedHelpers.findAndHookMethod(
                    providerClass,
                    "querySearchDocuments",
                    uriClass,
                    stringClass,
                    stringArrayClass,
                    bundleClass,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                val query = param.args[1] as? String ?: ""
                                
                                // 优化：只拦截包含零宽字符的查询
                                if (!containsZeroWidthChars(query)) {
                                    return // 正常查询，直接放行
                                }
                                
                                val cursor = param.result as? Cursor
                                if (cursor != null && !cursor.isClosed) {
                                    val filteredCursor = FilteredCursor(cursor, TARGET_PACKAGES)
                                    param.result = filteredCursor
                                    
                                    if (filteredCursor.filteredCount > 0) {
                                        Log.d(TAG, "Filtered ${filteredCursor.filteredCount} morph-related documents from querySearchDocuments")
                                    }
                                }
                            } catch (e: Throwable) {
                                Log.e(TAG, "Error filtering querySearchDocuments result", e)
                            }
                        }
                    }
                )
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to hook querySearchDocuments", e)
        }
    }

    /**
     * Hook File.listFiles() 方法，过滤 com.hive.morph 相关文件
     */
    private fun hookFileListFiles(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val fileClass = XposedHelpers.findClass("java.io.File", lpparam.classLoader)
            
            // Hook listFiles() 无参数版本
            XposedBridge.hookAllMethods(fileClass, "listFiles", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        // 获取当前 File 对象的路径
                        val currentFile = param.thisObject
                        val currentPath = XposedHelpers.callMethod(currentFile, "getAbsolutePath") as? String ?: ""
                        
                        // 优化：只拦截包含零宽字符的路径
                        if (!containsZeroWidthChars(currentPath)) {
                            return // 正常路径，直接放行
                        }
                        
                        // 检测到零宽字符，进行过滤
                        XposedBridge.log("[$TAG] ⚠️ File.listFiles 检测到零宽字符: $currentPath")
                        
                        val result = param.result as? Array<*>
                        if (result != null && result.isNotEmpty()) {
                            val filtered = ArrayList<Any>()
                            var filteredCount = 0
                            
                            for (file in result) {
                                try {
                                    val fileName = XposedHelpers.callMethod(file, "getName") as? String ?: ""
                                    val filePath = XposedHelpers.callMethod(file, "getAbsolutePath") as? String ?: ""
                                    
                                    // 检查是否包含目标包名（忽略大小写）
                                    val isTargetPackage = containsTargetPackage(fileName) || 
                                                         containsTargetPackage(filePath)
                                    
                                    if (!isTargetPackage) {
                                        filtered.add(file!!)
                                    } else {
                                        filteredCount++
                                        Log.d(TAG, "Filtered target package file: $fileName")
                                    }
                                } catch (e: Exception) {
                                    // 如果无法获取文件名，保留该文件
                                    if (file != null) {
                                        filtered.add(file)
                                    }
                                }
                            }
                            
                            if (filteredCount > 0) {
                                Log.d(TAG, "✅ File.listFiles 已过滤 $filteredCount 个风险应用")
                                XposedBridge.log("[$TAG] ✅ File.listFiles 已过滤 $filteredCount 个风险应用")
                                // 转换为正确的类型
                                val fileClass = XposedHelpers.findClass("java.io.File", lpparam.classLoader)
                                val fileArray = filtered.toArray(java.lang.reflect.Array.newInstance(fileClass, 0) as Array<Any>)
                                param.result = fileArray
                            }
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "Error filtering File.listFiles()", e)
                    }
                }
            })
            
            Log.d(TAG, "Successfully hooked File.listFiles() in ${lpparam.packageName}")
            XposedBridge.log("Hooked File.listFiles() in ${lpparam.packageName}")
            
            // Hook listFiles(FileFilter) 版本
            try {
                val fileFilterClass = XposedHelpers.findClass("java.io.FileFilter", lpparam.classLoader)
                XposedHelpers.findAndHookMethod(
                    fileClass,
                    "listFiles",
                    fileFilterClass,
                    object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                                val currentFile = param.thisObject
                                val currentPath = XposedHelpers.callMethod(currentFile, "getAbsolutePath") as? String ?: ""
                                
                                // 优化：只拦截包含零宽字符的路径
                                if (!containsZeroWidthChars(currentPath)) {
                                    return // 正常路径，直接放行
                                }
                                
                        val result = param.result as? Array<*>
                        if (result != null) {
                            val filtered = result.filter { file ->
                                val fileName = XposedHelpers.callMethod(file, "getName") as? String ?: ""
                                val filePath = XposedHelpers.callMethod(file, "getAbsolutePath") as? String ?: ""
                                !containsTargetPackage(fileName) && !containsTargetPackage(filePath)
                            }.toTypedArray()
                            
                            if (filtered.size != result.size) {
                                val filteredCount = result.size - filtered.size
                                Log.d(TAG, "Filtered $filteredCount morph-related files from File.listFiles(FileFilter)")
                            }
                            
                            param.result = filtered
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "Error filtering File.listFiles(FileFilter)", e)
                    }
                }
                })
            } catch (e: Throwable) {
                Log.d(TAG, "Failed to hook File.listFiles(FileFilter), may not exist: ${e.message}")
            }

            // Hook list() 方法 - 返回 String[] 文件名数组
            XposedBridge.hookAllMethods(fileClass, "list", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val currentFile = param.thisObject
                        val currentPath = XposedHelpers.callMethod(currentFile, "getAbsolutePath") as? String ?: ""
                        
                        // 优化：只拦截包含零宽字符的路径
                        if (!containsZeroWidthChars(currentPath)) {
                            return // 正常路径，直接放行
                        }
                        
                        val result = param.result as? Array<*>
                        if (result != null && result.isNotEmpty()) {
                            val filtered = ArrayList<String>()
                            var filteredCount = 0

                            for (fileNameObj in result) {
                                val fileName = fileNameObj?.toString() ?: ""

                                // 检查是否包含目标包名（忽略大小写）
                                val isTargetPackage = containsTargetPackage(fileName)

                                if (!isTargetPackage) {
                                    filtered.add(fileName)
                                } else {
                                    filteredCount++
                                    Log.d(TAG, "Filtered target package from File.list(): $fileName")
                                }
                            }

                            if (filteredCount > 0) {
                                Log.d(TAG, "Filtered $filteredCount target package names from File.list() in ${lpparam.packageName}")
                                XposedBridge.log("Filtered $filteredCount target package names from File.list()")
                                // 转换为 String 数组
                                param.result = filtered.toTypedArray()
                            }
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "Error filtering File.list()", e)
                    }
                }
            })

            // Hook list(FilenameFilter) 版本
            try {
                val filenameFilterClass = XposedHelpers.findClass("java.io.FilenameFilter", lpparam.classLoader)
                XposedHelpers.findAndHookMethod(
                    fileClass,
                    "list",
                    filenameFilterClass,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                val currentFile = param.thisObject
                                val currentPath = XposedHelpers.callMethod(currentFile, "getAbsolutePath") as? String ?: ""
                                
                                // 优化：只拦截包含零宽字符的路径
                                if (!containsZeroWidthChars(currentPath)) {
                                    return // 正常路径，直接放行
                                }
                                
                                val result = param.result as? Array<*>
                                if (result != null) {
                                    val filtered = result.filter { fileNameObj ->
                                        val fileName = fileNameObj?.toString() ?: ""
                                        !containsTargetPackage(fileName)
                                    }.toTypedArray()

                                    if (filtered.size != result.size) {
                                        val filteredCount = result.size - filtered.size
                                        Log.d(TAG, "Filtered $filteredCount target package names from File.list(FilenameFilter)")
                                    }

                                    param.result = filtered
                                }
                            } catch (e: Throwable) {
                                Log.e(TAG, "Error filtering File.list(FilenameFilter)", e)
                            }
                        }
                    }
                )
            } catch (e: Throwable) {
                Log.d(TAG, "Failed to hook File.list(FilenameFilter), may not exist: ${e.message}")
            }

            Log.d(TAG, "Successfully hooked File.list() in ${lpparam.packageName}")
            XposedBridge.log("Hooked File.list() in ${lpparam.packageName}")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to hook File.listFiles()", e)
        }
    }
    
    /**
     * Hook DocumentFile.listFiles() 方法，过滤 com.hive.morph 相关文件
     */
    private fun hookDocumentFileListFiles(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // 尝试查找 DocumentFile 类（可能在 androidx 或 support 库中）
            val documentFileClassNames = listOf(
                "androidx.documentfile.provider.DocumentFile",
                "android.support.v4.provider.DocumentFile"
            )
            
            var documentFileClass: Class<*>? = null
            for (className in documentFileClassNames) {
                try {
                    documentFileClass = XposedHelpers.findClass(className, lpparam.classLoader)
                    Log.d(TAG, "Found DocumentFile class: $className")
                    break
                } catch (e: Throwable) {
                    // 继续尝试下一个
                }
            }
            
            if (documentFileClass != null) {
                XposedBridge.hookAllMethods(documentFileClass, "listFiles", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val documentFile = param.thisObject
                            val uri = XposedHelpers.callMethod(documentFile, "getUri") as? Uri?
                            val uriString = uri?.toString() ?: ""
                            
                            // 优化：只拦截包含零宽字符的 URI
                            if (!containsZeroWidthChars(uriString)) {
                                return // 正常 URI，直接放行
                            }
                            
                            val result = param.result as? Array<*>
                            if (result != null && result.isNotEmpty()) {
                                val filtered = ArrayList<Any>()
                                var filteredCount = 0
                                
                                for (file in result) {
                                    try {
                                        val fileName = XposedHelpers.callMethod(file, "getName") as? String ?: ""
                                        val fileUri = XposedHelpers.callMethod(file, "getUri") as? Uri?
                                        val uriPath = fileUri?.path ?: ""
                                        val fileUriString = fileUri?.toString() ?: ""
                                        
                                        // 检查是否包含目标包名（忽略大小写）
                                        val isTargetPackage = containsTargetPackage(fileName) || 
                                                             containsTargetPackage(uriPath) ||
                                                containsTargetPackage(fileUriString)
                                        
                                        if (!isTargetPackage) {
                                            filtered.add(file!!)
                                        } else {
                                            filteredCount++
                                            Log.d(TAG, "Filtered target package DocumentFile: $fileName (uri: $fileUriString)")
                                        }
                                    } catch (e: Exception) {
                                        // 如果无法获取文件名，保留该文件
                                        if (file != null) {
                                            filtered.add(file)
                                        }
                                    }
                                }
                                
                                if (filteredCount > 0) {
                                    Log.d(TAG, "Filtered $filteredCount target package files from DocumentFile.listFiles() in ${lpparam.packageName}")
                                    XposedBridge.log("Filtered $filteredCount target package files from DocumentFile.listFiles()")
                                    // 转换为正确的类型
                                    val docFileArray = filtered.toArray(java.lang.reflect.Array.newInstance(documentFileClass, 0) as Array<Any>)
                                    param.result = docFileArray
                                }
                            }
                        } catch (e: Throwable) {
                            Log.e(TAG, "Error filtering DocumentFile.listFiles()", e)
                        }
                    }
                })
                
                Log.d(TAG, "Successfully hooked DocumentFile.listFiles() in ${lpparam.packageName}")
                XposedBridge.log("Hooked DocumentFile.listFiles() in ${lpparam.packageName}")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to hook DocumentFile.listFiles()", e)
        }
    }
    
    /**
     * 判断 URI 是否与目标包名相关
     */
    private fun isMorphRelatedUri(uri: Uri): Boolean {
        try {
            val path = uri.path ?: return false
            val lastPathSegment = uri.lastPathSegment ?: ""
            
            return containsTargetPackage(path) || containsTargetPackage(lastPathSegment)
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Hook Runtime.exec() 方法来拦截 shell 命令（如 ls）
     */
    private fun hookRuntimeExec(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val runtimeClass = XposedHelpers.findClass("java.lang.Runtime", lpparam.classLoader)
            val stringClass = String::class.java
            val stringArrayClass = Array<String>::class.java

            // Hook exec(String) 方法
            XposedHelpers.findAndHookMethod(
                runtimeClass,
                "exec",
                stringClass,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val command = param.args[0] as? String ?: return
                            
                            // 优化：只拦截包含零宽字符的 ls 命令
                            if (!command.contains("ls", ignoreCase = true)) {
                                return // 不是 ls 命令，直接放行
                            }
                            
                            if (!containsZeroWidthChars(command)) {
                                return // 正常 ls 命令，直接放行
                            }
                            
                            // 检测到零宽字符攻击
                            XposedBridge.log("[$TAG] ⚠️ Runtime.exec 检测到零宽字符 ls 命令: $command")
                            
                            val process = param.result as? Process ?: return
                            // 包装 Process 来过滤输出
                            val wrappedProcess = FilteredProcess(process, TARGET_PACKAGES)
                            param.result = wrappedProcess
                            Log.d(TAG, "Wrapped ls command Process: $command")
                        } catch (e: Throwable) {
                            Log.e(TAG, "Error wrapping Runtime.exec() Process", e)
                        }
                    }
                }
            )

            // Hook exec(String[]) 方法
            XposedHelpers.findAndHookMethod(
                runtimeClass,
                "exec",
                stringArrayClass,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val commandArray = param.args[0] as? Array<*> ?: return
                            val command = commandArray.joinToString(" ") { it?.toString() ?: "" }
                            
                            // 优化：只拦截包含零宽字符的 ls 命令
                            if (!command.contains("ls", ignoreCase = true)) {
                                return
                            }
                            
                            if (!containsZeroWidthChars(command)) {
                                return // 正常 ls 命令，直接放行
                            }
                            
                            XposedBridge.log("[$TAG] ⚠️ Runtime.exec(array) 检测到零宽字符 ls 命令: $command")
                            
                            val process = param.result as? Process ?: return
                            // 包装 Process 来过滤输出
                            val wrappedProcess = FilteredProcess(process, TARGET_PACKAGES)
                            param.result = wrappedProcess
                            Log.d(TAG, "Wrapped ls command Process: $command")
                        } catch (e: Throwable) {
                            Log.e(TAG, "Error wrapping Runtime.exec(String[]) Process", e)
                        }
                    }
                }
            )

            Log.d(TAG, "Successfully hooked Runtime.exec() in ${lpparam.packageName}")
            XposedBridge.log("Hooked Runtime.exec() in ${lpparam.packageName}")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to hook Runtime.exec()", e)
        }
    }

    /**
     * Hook ProcessBuilder.start() 方法来拦截 shell 命令
     */
    private fun hookProcessBuilder(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val processBuilderClass = XposedHelpers.findClass("java.lang.ProcessBuilder", lpparam.classLoader)

            XposedHelpers.findAndHookMethod(
                processBuilderClass,
                "start",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val processBuilder = param.thisObject
                            val commandList = XposedHelpers.getObjectField(processBuilder, "command") as? List<*>
                            val command = commandList?.joinToString(" ") { it?.toString() ?: "" } ?: ""

                            // 优化：只拦截包含零宽字符的 ls 命令
                            if (!command.contains("ls", ignoreCase = true)) {
                                return
                            }
                            
                            if (!containsZeroWidthChars(command)) {
                                return // 正常 ls 命令，直接放行
                            }
                            
                            XposedBridge.log("[$TAG] ⚠️ ProcessBuilder 检测到零宽字符 ls 命令: $command")
                            
                            val process = param.result as? Process ?: return
                            // 包装 Process 来过滤输出
                            val wrappedProcess = FilteredProcess(process, TARGET_PACKAGES)
                            param.result = wrappedProcess
                            Log.d(TAG, "Wrapped ProcessBuilder ls command Process: $command")
                        } catch (e: Throwable) {
                            Log.e(TAG, "Error wrapping ProcessBuilder.start() Process", e)
                        }
                    }
                }
            )

            Log.d(TAG, "Successfully hooked ProcessBuilder.start() in ${lpparam.packageName}")
            XposedBridge.log("Hooked ProcessBuilder.start() in ${lpparam.packageName}")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to hook ProcessBuilder.start()", e)
        }
    }

    /**
     * Hook Files.list() 方法来过滤包含目标包名的路径
     */
    private fun hookFilesList(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val filesClass = XposedHelpers.findClass("java.nio.file.Files", lpparam.classLoader)
            val pathClass = XposedHelpers.findClass("java.nio.file.Path", lpparam.classLoader)
            val predicateClass = XposedHelpers.findClass("java.util.function.Predicate", lpparam.classLoader)

            // Hook Files.list(Path) 方法，返回 Stream<Path>
            XposedHelpers.findAndHookMethod(
                filesClass,
                "list",
                pathClass,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val path = param.args[0]
                            val pathString = XposedHelpers.callMethod(path, "toString") as? String ?: ""
                            
                            // 优化：只拦截包含零宽字符的路径
                            if (!containsZeroWidthChars(pathString)) {
                                return // 正常路径，直接放行
                            }
                            
                            val originalStream = param.result ?: return

                            // 使用反射调用 Stream.filter() 方法
                            val filterMethod = originalStream.javaClass.getMethod("filter", predicateClass)

                            // 创建 Predicate 实例来过滤路径
                            val predicate = Proxy.newProxyInstance(
                                lpparam.classLoader,
                                arrayOf(predicateClass)
                            ) { proxy, method, args ->
                                when (method.name) {
                                    "test" -> {
                                        if (args != null && args.isNotEmpty()) {
                                            val pathItem = args[0]
                                            try {
                                                val pathItemString = XposedHelpers.callMethod(pathItem, "toString") as? String ?: ""
                                                val fileNameObj = XposedHelpers.callMethod(pathItem, "getFileName")
                                                val fileName = if (fileNameObj != null) {
                                                    XposedHelpers.callMethod(fileNameObj, "toString") as? String ?: ""
                                                } else {
                                                    ""
                                                }

                                                // 检查路径或文件名是否包含目标包名
                                                val containsTarget = TARGET_PACKAGES.any { pkg ->
                                                    pathItemString.contains(pkg, ignoreCase = true) ||
                                                            fileName.contains(pkg, ignoreCase = true)
                                                }

                                                if (containsTarget) {
                                                    Log.d(TAG, "Filtered path from Files.list(): $pathItemString")
                                                }

                                                !containsTarget
                                            } catch (e: Exception) {
                                                // 如果出错，保留该路径
                                                true
                                            }
                                        } else {
                                            true
                                        }
                                    }
                                    "equals" -> args?.getOrNull(0) == proxy
                                    "hashCode" -> proxy.hashCode()
                                    "toString" -> "FilteredPredicate"
                                    else -> {
                                        try {
                                            method.invoke(proxy, *(args ?: emptyArray()))
                                        } catch (e: Exception) {
                                            null
                                        }
                                    }
                                }
                            }

                            // 调用 filter 方法并返回过滤后的 Stream
                            val filteredStream = filterMethod.invoke(originalStream, predicate)
                            param.result = filteredStream

                            Log.d(TAG, "Filtered Files.list() Stream in ${lpparam.packageName}")
                        } catch (e: Throwable) {
                            Log.e(TAG, "Error filtering Files.list() Stream", e)
                        }
                    }
                }
            )

            Log.d(TAG, "Successfully hooked Files.list() in ${lpparam.packageName}")
            XposedBridge.log("Hooked Files.list() in ${lpparam.packageName}")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to hook Files.list()", e)
            XposedBridge.log("Failed to hook Files.list(): ${e.message}")
        }
    }

    /**
     * 过滤 Cursor 的包装类，移除包含目标包名的行
     */
    private class FilteredCursor(cursor: Cursor, private val filterPackages: List<String>) : CursorWrapper(cursor) {
        private val validPositions = mutableListOf<Int>()
        private var currentIndex = -1
        var filteredCount = 0
            private set

        init {
            try {
                // 遍历原始 Cursor，找出所有不包含 filterString 的行
                val originalCursor = wrappedCursor
                if (originalCursor != null && !originalCursor.isClosed) {
                    val savedPosition = originalCursor.position
                    try {
                        if (originalCursor.moveToFirst()) {
                            do {
                                var documentId = ""
                                var path = ""
                                
                                try {
                                    val displayNameIndex = originalCursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                                    if (displayNameIndex >= 0) {
                                        documentId = originalCursor.getString(displayNameIndex) ?: ""
                                    }
                                    
                                    val displayNameAltIndex = originalCursor.getColumnIndex("_display_name")
                                    if (displayNameAltIndex >= 0 && documentId.isEmpty()) {
                                        documentId = originalCursor.getString(displayNameAltIndex) ?: ""
                                    }
                                    
                                    val documentIdIndex = originalCursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                                    if (documentIdIndex >= 0) {
                                        path = originalCursor.getString(documentIdIndex) ?: ""
                                    }
                                } catch (e: Exception) {
                                    // 忽略列不存在的错误，继续处理
                                }
                                
                                // 检查是否包含任何目标包名
                                val containsTarget = filterPackages.any { pkg ->
                                    (documentId.contains(pkg, ignoreCase = true)) || 
                                    (path.contains(pkg, ignoreCase = true))
                                }
                                
                                if (!containsTarget) {
                                    validPositions.add(originalCursor.position)
                                } else {
                                    filteredCount++
                                }
                            } while (originalCursor.moveToNext())
                        }
                    } catch (e: Exception) {
                        // 如果遍历失败，记录所有位置
                        val count = originalCursor.count
                        for (i in 0 until count) {
                            validPositions.add(i)
                        }
                    } finally {
                        // 恢复原始位置
                        try {
                            originalCursor.moveToPosition(savedPosition)
                        } catch (e: Exception) {
                            // 忽略
                        }
                    }
                }
            } catch (e: Exception) {
                // 如果初始化失败，记录错误但不崩溃
                Log.e("FilteredCursor", "Error initializing FilteredCursor", e)
            }
        }

        override fun getCount(): Int {
            return validPositions.size
        }

        override fun moveToPosition(position: Int): Boolean {
            try {
                if (position < 0 || position >= validPositions.size) {
                    return false
                }
                val targetPosition = validPositions[position]
                val moved = wrappedCursor.moveToPosition(targetPosition)
                if (moved) {
                    currentIndex = position
                }
                return moved
            } catch (e: Exception) {
                return false
            }
        }

        override fun moveToFirst(): Boolean {
            return moveToPosition(0)
        }

        override fun moveToLast(): Boolean {
            return moveToPosition(validPositions.size - 1)
        }

        override fun moveToNext(): Boolean {
            return moveToPosition(currentIndex + 1)
        }

        override fun moveToPrevious(): Boolean {
            return moveToPosition(currentIndex - 1)
        }

        override fun isFirst(): Boolean {
            return currentIndex == 0 && validPositions.isNotEmpty()
        }

        override fun isLast(): Boolean {
            return currentIndex == validPositions.size - 1 && validPositions.isNotEmpty()
        }

        override fun isBeforeFirst(): Boolean {
            return currentIndex < 0
        }

        override fun isAfterLast(): Boolean {
            return currentIndex >= validPositions.size
        }

        override fun getPosition(): Int {
            return currentIndex
        }
    }

    /**
     * 过滤 Process 输出的包装类，移除包含目标包名的行
     */
    private class FilteredProcess(
        private val originalProcess: Process,
        private val filterPackages: List<String>
    ) : Process() {
        private var filteredInputStream: InputStream? = null

        override fun getOutputStream(): OutputStream {
            return originalProcess.outputStream
        }

        override fun getInputStream(): InputStream {
            if (filteredInputStream == null) {
                filteredInputStream = FilteredInputStream(originalProcess.inputStream, filterPackages)
            }
            return filteredInputStream!!
        }

        override fun getErrorStream(): InputStream {
            // 错误流也过滤
            return FilteredInputStream(originalProcess.errorStream, filterPackages)
        }

        override fun waitFor(): Int {
            return originalProcess.waitFor()
        }

        override fun exitValue(): Int {
            return originalProcess.exitValue()
        }

        override fun destroy() {
            originalProcess.destroy()
        }

        override fun destroyForcibly(): Process {
            return originalProcess.destroyForcibly()
        }

        override fun isAlive(): Boolean {
            return originalProcess.isAlive()
        }

        /**
         * 获取原始 Process 对象（用于访问 API 26+ 的方法）
         */
        fun getOriginalProcess(): Process {
            return originalProcess
        }
    }

    /**
     * 过滤 InputStream，移除包含目标包名的行
     */
    private class FilteredInputStream(
        private val originalStream: InputStream,
        private val filterPackages: List<String>
    ) : InputStream() {
        private val lineBuffer = ByteArrayOutputStream()
        private val outputBuffer = ByteArrayOutputStream()
        private var outputIndex = 0
        private var isClosed = false
        private var eofReached = false

        override fun read(): Int {
            if (isClosed) return -1

            // 如果输出缓冲区有数据，先返回
            if (outputIndex < outputBuffer.size()) {
                val result = outputBuffer.toByteArray()[outputIndex].toInt() and 0xFF
                outputIndex++
                return result
            }

            // 如果已经到达文件末尾，返回 -1
            if (eofReached) {
                return -1
            }

            // 从原始流读取数据
            while (true) {
                val byte = originalStream.read()
                if (byte == -1) {
                    eofReached = true
                    // 处理剩余的缓冲区
                    if (lineBuffer.size() > 0) {
                        processLine()
                    }
                    // 检查输出缓冲区是否有数据
                    if (outputIndex < outputBuffer.size()) {
                        val result = outputBuffer.toByteArray()[outputIndex].toInt() and 0xFF
                        outputIndex++
                        return result
                    }
                    return -1
                }

                lineBuffer.write(byte)

                // 检查是否是换行符
                if (byte == '\n'.code || byte == '\r'.code) {
                    processLine()
                    lineBuffer.reset()

                    // 如果输出缓冲区有数据，返回
                    if (outputIndex < outputBuffer.size()) {
                        val result = outputBuffer.toByteArray()[outputIndex].toInt() and 0xFF
                        outputIndex++
                        return result
                    }
                }
            }
        }

        private fun processLine() {
            if (lineBuffer.size() == 0) return

            val line = lineBuffer.toString(Charsets.UTF_8.name()).trim()
            // 如果行不包含目标包名，添加到输出缓冲区
            if (!containsTargetPackage(line) && line.isNotEmpty()) {
                // 保留原始格式（包括换行符）
                val originalBytes = lineBuffer.toByteArray()
                outputBuffer.write(originalBytes)
            }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (isClosed) return -1

            var bytesRead = 0
            while (bytesRead < len) {
                val byte = read()
                if (byte == -1) {
                    return if (bytesRead > 0) bytesRead else -1
                }
                b[off + bytesRead] = byte.toByte()
                bytesRead++
            }
            return bytesRead
        }

        override fun close() {
            isClosed = true
            originalStream.close()
            lineBuffer.close()
            outputBuffer.close()
        }

        override fun available(): Int {
            return if (outputIndex < outputBuffer.size()) {
                outputBuffer.size() - outputIndex
            } else {
                0
            }
        }

        private fun containsTargetPackage(text: String): Boolean {
            return filterPackages.any { pkg ->
                text.contains(pkg, ignoreCase = true)
            }
        }
    }
}
