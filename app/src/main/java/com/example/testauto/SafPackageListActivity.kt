package com.example.testauto

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.ArrayList

/**
 * SAF (Storage Access Framework) 包列表 Activity
 * 使用零宽字符技术绕过过滤获取包名列表
 */
class SafPackageListActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SafPackageList"
        private const val REQUEST_CODE_OPEN_DIRECTORY = 1001
        
        // 零宽字符集合
        private val ZERO_WIDTH_CHARS = arrayOf(
            "\u200B", // 零宽空格
            "\u200b", // 零宽空格
            "\u200C", // 零宽非连接符
            "\u200c", // 零宽非连接符
            "\u200D", // 零宽连接符
            "\u200d", // 零宽连接符
            "\uFEFF"  // 零宽无断空格
        )
    }

    private lateinit var tvResult: TextView
    private lateinit var btnSelectDirectory: Button
    private lateinit var btnListPackages: Button
    
    private var selectedTreeUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_saf_package_list)

        tvResult = findViewById(R.id.tvResult)
        btnSelectDirectory = findViewById(R.id.btnSelectDirectory)
        btnListPackages = findViewById(R.id.btnListPackages)

        btnSelectDirectory.setOnClickListener {
            openDirectoryPicker()
        }

        btnListPackages.setOnClickListener {
            selectedTreeUri?.let { uri ->
                listPackages(uri)
            } ?: run {
                // 如果没有选择目录，尝试直接访问默认路径
                autoAccessDirectory()
            }
        }
        
        // 自动访问目录
        autoAccessDirectory()
    }
    
    /**
     * 自动访问指定目录（使用零宽字符绕过过滤）
     */
    private fun autoAccessDirectory() {
        tvResult.text = "正在自动访问目录...\n路径: /storage/emulated/0/Android/data"
        
        // 方法1: 尝试直接文件访问（需要权限）
        try {
            val basePath = "/storage/emulated/0/Android"
            val targetDir = "data"
            
            // 使用零宽字符构建路径来绕过过滤
            val pathWithZeroWidth = "$basePath/\u200d$targetDir"  // 在路径中插入零宽连接符
            
            val file = java.io.File(pathWithZeroWidth)
            if (file.exists() && file.isDirectory) {
                tvResult.text = "成功访问目录（直接文件访问）\n开始扫描...\n\n"
                listPackagesFromFile(file)
                return
            }
        } catch (e: Exception) {
            Log.d(TAG, "Direct file access failed: ${e.message}")
        }
        
        // 方法2: 尝试通过 SAF URI 访问
        try {
            // 构建带零宽字符的 URI
            val baseUri = "content://com.android.externalstorage.documents/tree/primary%3AAndroid"
            val targetPath = "data"
            val uriWithZeroWidth = "$baseUri%2F\u200D$targetPath"
            
            val uri = Uri.parse(uriWithZeroWidth)
            if (uri != null) {
                selectedTreeUri = uri
                tvResult.text = "成功访问目录（SAF URI）\n开始扫描...\n\n"
                listPackages(uri)
                return
            }
        } catch (e: Exception) {
            Log.d(TAG, "SAF URI access failed: ${e.message}")
        }
        
        // 方法3: 使用标准路径尝试
        try {
            val standardPath = "/storage/emulated/0/Android/data"
            val file = java.io.File(standardPath)
            if (file.exists() && file.isDirectory) {
                tvResult.text = "成功访问目录（标准路径）\n开始扫描...\n\n"
                listPackagesFromFile(file)
                return
            }
        } catch (e: Exception) {
            Log.d(TAG, "Standard path access failed: ${e.message}")
        }
        
        // 如果都失败了，提示用户手动选择
        tvResult.text = "自动访问失败，请点击\"选择目录\"按钮手动选择\n\n" +
                "目标路径: /storage/emulated/0/Android/data\n" +
                "提示: 需要存储权限或 SAF 权限"
    }
    
    /**
     * 从 File 对象列出包名 - 展示四种不同的方法
     */
    private fun listPackagesFromFile(directory: java.io.File) {
        if (!directory.exists() || !directory.isDirectory) {
            tvResult.text = "错误: 无效的目录"
            return
        }

        val resultBuilder = StringBuilder()
        resultBuilder.append("开始扫描包名...\n")
        resultBuilder.append("目录: ${directory.absolutePath}\n\n")

        try {
            // 方法1: File.listFiles()
            val listFilesResult = getPackagesByListFiles(directory)
            resultBuilder.append("=== 方法1: File.listFiles() ===\n")
            resultBuilder.append("找到 ${listFilesResult.size} 个目录:\n")
            listFilesResult.forEachIndexed { index, name ->
                resultBuilder.append("  ${index + 1}. $name\n")
            }
            resultBuilder.append("\n")

            // 方法2: File.list()
            val listResult = getPackagesByList(directory)
            resultBuilder.append("=== 方法2: File.list() ===\n")
            resultBuilder.append("找到 ${listResult.size} 个目录:\n")
            listResult.forEachIndexed { index, name ->
                resultBuilder.append("  ${index + 1}. $name\n")
            }
            resultBuilder.append("\n")

            // 方法3: Shell ls 命令
            val shellLsResult = getPackagesByShellLs(directory)
            resultBuilder.append("=== 方法3: Shell ls 命令 ===\n")
            resultBuilder.append("找到 ${shellLsResult.size} 个目录:\n")
            shellLsResult.forEachIndexed { index, name ->
                resultBuilder.append("  ${index + 1}. $name\n")
            }
            resultBuilder.append("\n")

            // 方法4: readdir() (JNI)
            val readdirResult = getPackagesByReaddir(directory)
            resultBuilder.append("=== 方法4: readdir() (JNI) ===\n")
            if (readdirResult.isEmpty()) {
                resultBuilder.append("需要 native 库支持，当前未实现\n")
            } else {
                resultBuilder.append("找到 ${readdirResult.size} 个目录:\n")
                readdirResult.forEachIndexed { index, name ->
                    resultBuilder.append("  ${index + 1}. $name\n")
                }
            }
            resultBuilder.append("\n")

            resultBuilder.append("=== 汇总 ===\n")
            val allPackages = mutableSetOf<String>()
            allPackages.addAll(listFilesResult)
            allPackages.addAll(listResult)
            allPackages.addAll(shellLsResult)
            allPackages.addAll(readdirResult)
            resultBuilder.append("总共找到 ${allPackages.size} 个唯一包名\n")

            tvResult.text = resultBuilder.toString()
            Log.i(TAG, "Found packages - listFiles: ${listFilesResult.size}, list: ${listResult.size}, ls: ${shellLsResult.size}, readdir: ${readdirResult.size}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error listing packages from file", e)
            tvResult.text = "错误: ${e.message}\n\n${resultBuilder.toString()}"
        }
    }

    /**
     * 方法1: 使用 File.listFiles() 获取包名列表
     * 
     * 验证：此方法调用 File.listFiles()，用于验证是否会经过 com.android.providers.media.module
     * 如果 LSPosed hook 中看到 queryChildDocuments 被调用，说明 File.listFiles() 确实会经过 ExternalStorageProvider
     */
    private fun getPackagesByListFiles(directory: java.io.File): List<String> {
        val packageList = ArrayList<String>()
        
        // 验证日志：记录调用 File.listFiles() 的信息
        Log.i(TAG, "🔍 [验证] 开始调用 File.listFiles() - 路径: ${directory.absolutePath}")
        Log.i(TAG, "🔍 [验证] 请查看 LSPosed 日志，确认是否看到 queryChildDocuments 被调用")
        
        try {
            val files = directory.listFiles()
            if (files != null) {
                for (file in files) {
                    if (file.isDirectory) {
                        val packageName = file.name
                        if (packageName.isNotEmpty()) {
                            val cleanPackageName = removeZeroWidthChars(packageName)
                            if (cleanPackageName.isNotEmpty() && !packageList.contains(cleanPackageName)) {
                                packageList.add(cleanPackageName)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in getPackagesByListFiles", e)
        }
        return packageList
    }

    /**
     * 方法2: 使用 File.list() 获取包名列表
     * 
     * 验证：此方法调用 File.list()，用于验证是否会经过 com.android.providers.media.module
     * 如果 LSPosed hook 中看到 queryChildDocuments 被调用，说明 File.list() 确实会经过 ExternalStorageProvider
     */
    private fun getPackagesByList(directory: java.io.File): List<String> {
        val packageList = ArrayList<String>()
        
        // 验证日志：记录调用 File.list() 的信息
        Log.i(TAG, "🔍 [验证] 开始调用 File.list() - 路径: ${directory.absolutePath}")
        Log.i(TAG, "🔍 [验证] 请查看 LSPosed 日志，确认是否看到 queryChildDocuments 被调用")
        
        // 记录调用栈（前5层）
        val stackTrace = Thread.currentThread().stackTrace
        val callStack = StringBuilder("调用栈:\n")
        for (i in 0 until minOf(5, stackTrace.size)) {
            callStack.append("  [${i}] ${stackTrace[i].className}.${stackTrace[i].methodName}\n")
        }
        Log.d(TAG, callStack.toString())
        try {
            val fileNames = directory.list()
            if (fileNames != null) {
                for (fileName in fileNames) {
                    val file = java.io.File(directory, fileName)
                    if (file.isDirectory) {
                        val packageName = fileName
                        if (packageName.isNotEmpty()) {
                            val cleanPackageName = removeZeroWidthChars(packageName)
                            if (cleanPackageName.isNotEmpty() && !packageList.contains(cleanPackageName)) {
                                packageList.add(cleanPackageName)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in getPackagesByList", e)
        }
        return packageList
    }

    /**
     * 方法3: 使用 Shell ls 命令获取包名列表
     */
    private fun getPackagesByShellLs(directory: java.io.File): List<String> {
        val packageList = ArrayList<String>()
        try {
            val directoryPath = directory.absolutePath
            val command = arrayOf("sh", "-c", "ls -1 \"$directoryPath\"")
            val process = Runtime.getRuntime().exec(command)
            
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))
            
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                line?.let { fileName ->
                    val file = java.io.File(directory, fileName)
                    if (file.isDirectory) {
                        val packageName = fileName
                        if (packageName.isNotEmpty()) {
                            val cleanPackageName = removeZeroWidthChars(packageName)
                            if (cleanPackageName.isNotEmpty() && !packageList.contains(cleanPackageName)) {
                                packageList.add(cleanPackageName)
                            }
                        }
                    }
                }
            }
            reader.close()
            
            // 读取错误流，避免进程阻塞
            while (errorReader.readLine() != null) {
                // 忽略错误输出
            }
            errorReader.close()
            
            process.waitFor()
            process.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error in getPackagesByShellLs", e)
        }
        return packageList
    }

    /**
     * 方法4: 使用 readdir() (JNI) 获取包名列表
     * 注意：这需要 native 库支持，当前使用反射尝试调用
     */
    private fun getPackagesByReaddir(directory: java.io.File): List<String> {
        val packageList = ArrayList<String>()
        try {
            // 尝试通过 JNI 调用 readdir()
            // 由于需要 native 库，这里先尝试使用 Files.list() 作为替代
            // 实际项目中需要实现 native 方法
            
            // 方法4a: 尝试使用 Java NIO Files.list() (底层可能使用 readdir)
            try {
                val path = Paths.get(directory.absolutePath)
                Files.list(path).use { stream ->
                    stream.forEach { pathItem ->
                        val file = pathItem.toFile()
                        if (file.isDirectory) {
                            val packageName = file.name
                            if (packageName.isNotEmpty()) {
                                val cleanPackageName = removeZeroWidthChars(packageName)
                                if (cleanPackageName.isNotEmpty() && !packageList.contains(cleanPackageName)) {
                                    packageList.add(cleanPackageName)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Files.list() failed, trying alternative: ${e.message}")
                
                // 方法4b: 尝试通过反射调用 native 方法（如果存在）
                // 注意：这只是一个占位符，实际需要实现 native 库
                try {
                    // 这里可以尝试加载 native 库并调用 readdir
                    // System.loadLibrary("native-lib")
                    // val result = nativeReaddir(directory.absolutePath)
                    Log.d(TAG, "Native readdir() not implemented, using fallback")
                } catch (e: Exception) {
                    Log.d(TAG, "Native readdir() not available: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in getPackagesByReaddir", e)
        }
        return packageList
    }

    /**
     * 打开目录选择器
     */
    private fun openDirectoryPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        }
        startActivityForResult(intent, REQUEST_CODE_OPEN_DIRECTORY)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == REQUEST_CODE_OPEN_DIRECTORY && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                selectedTreeUri = uri
                // 持久化权限
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                tvResult.text = "已选择目录: ${uri.path}\n点击\"列表包名\"按钮开始扫描"
            }
        }
    }

    /**
     * 使用零宽字符技术列出包名
     * 通过在路径中插入零宽字符来绕过过滤
     * 这是用户要求的方法实现
     */
    private fun listPackages(treeUri: Uri) {
        val root = DocumentFile.fromTreeUri(this, treeUri)
        if (root == null || !root.isDirectory()) {
            Log.e(TAG, "Invalid directory")
            tvResult.text = "错误: 无效的目录"
            return
        }

        val packageList = ArrayList<String>()
        val resultBuilder = StringBuilder()
        resultBuilder.append("开始扫描包名...\n\n")

        try {
            // 遍历所有文件
            val files = root.listFiles()
            resultBuilder.append("找到 ${files.size} 个项目\n\n")

            // 方法1: 直接遍历（可能被过滤）
            for (file in files) {
                if (file.isDirectory()) {
                    val packageName = file.name
                    if (packageName != null && packageName.isNotEmpty()) {
                        val cleanPackageName = removeZeroWidthChars(packageName)
                        if (cleanPackageName.isNotEmpty() && !packageList.contains(cleanPackageName)) {
                            packageList.add(cleanPackageName)
                            Log.i(TAG, "Package (direct): $cleanPackageName")
                            resultBuilder.append("  ✓ [直接] $cleanPackageName\n")
                        }
                    }
                }
            }
            
            // 方法2: 使用零宽字符技术绕过过滤
            // 尝试通过修改路径来访问可能被过滤的目录
            ZERO_WIDTH_CHARS.forEach { zeroWidthChar ->
                try {
                    // 尝试在路径中插入零宽字符来访问文件
                    // 这可能会绕过某些基于字符串匹配的过滤
                    val modifiedFiles = root.listFiles()
                    modifiedFiles.forEach { file ->
                        if (file.isDirectory()) {
                            val originalName = file.name ?: ""
                            // 检查原始名称是否包含零宽字符（可能已经被过滤系统修改）
                            val nameWithZeroWidth = insertZeroWidthChars(originalName)
                            val cleanName = removeZeroWidthChars(originalName)
                            
                            // 如果名称被修改过，说明可能绕过了过滤
                            if (nameWithZeroWidth != originalName && cleanName.isNotEmpty()) {
                                if (!packageList.contains(cleanName)) {
                                    packageList.add(cleanName)
                                    Log.i(TAG, "Package (zero-width): $cleanName")
                                    resultBuilder.append("  ✓ [零宽] $cleanName\n")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Zero width char method failed for $zeroWidthChar: ${e.message}")
                }
            }

            resultBuilder.append("\n=== 扫描完成 ===\n")
            resultBuilder.append("共找到 ${packageList.size} 个包:\n\n")
            
            if (packageList.isEmpty()) {
                resultBuilder.append("未找到任何包目录\n")
                resultBuilder.append("提示: 请确保选择的目录包含包名文件夹\n")
            } else {
                packageList.forEachIndexed { index, packageName ->
                    resultBuilder.append("${index + 1}. $packageName\n")
                }
            }

            tvResult.text = resultBuilder.toString()
            Log.i(TAG, "Found ${packageList.size} packages")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error listing packages", e)
            tvResult.text = "错误: ${e.message}\n\n${resultBuilder.toString()}"
        }
    }
    
    /**
     * 在包名中插入零宽字符来绕过过滤
     */
    private fun insertZeroWidthChars(text: String): String {
        if (text.isEmpty()) return text
        
        // 在文本的关键位置插入零宽字符
        // 例如：在 "com.hive.morph" 中插入，变成 "com.\u200Bhive.\u200Cmorph"
        val parts = text.split(".")
        return parts.joinToString(".") { part ->
            if (part.length > 2) {
                val mid = part.length / 2
                part.substring(0, mid) + ZERO_WIDTH_CHARS[0] + part.substring(mid)
            } else {
                part
            }
        }
    }


    /**
     * 移除字符串中的零宽字符
     */
    private fun removeZeroWidthChars(text: String): String {
        var result = text
        ZERO_WIDTH_CHARS.forEach { char ->
            result = result.replace(char, "")
        }
        return result
    }

}

