package com.example.testauto

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * SystemPropertyUtil
 * 系统属性工具类，用于获取Android系统属性
 * @since 2025-01-XX
 */
object SystemPropertyUtil {

    /**
     * 获取系统属性
     * @param key 属性键名
     * @param defaultValue 默认值
     * @return 属性值，如果获取失败返回默认值
     */
    fun getSystemProperty(key: String, defaultValue: String = ""): String {
        return try {
            val systemProperties = Class.forName("android.os.SystemProperties")
            val getMethod =
                systemProperties.getMethod("get", String::class.java, String::class.java)
            getMethod.invoke(null, key, defaultValue) as String
        } catch (e: Exception) {
            Log.e("SystemPropertyUtil", "Failed to read property: $key", e)
            defaultValue
        }
    }

    /**
     * 通过getprop命令获取系统属性（作为反射的备选方案）
     * @param key 属性键名
     * @return 属性值，如果获取失败返回空字符串
     */
    fun getSystemPropertyViaGetprop(key: String): String {
        return try {
            val process = Runtime.getRuntime().exec("getprop $key")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val result = reader.readLine() ?: ""
            reader.close()
            process.destroy()
            result.trim()
        } catch (e: Exception) {
            Log.e("SystemPropertyUtil", "Failed to read property via getprop: $key", e)
            ""
        }
    }

    /**
     * 获取 vendor.gsm.serial 系统属性
     * 先尝试通过反射获取，失败则尝试通过getprop命令获取
     * @return vendor.gsm.serial 的值，如果获取失败返回空字符串
     */
    fun getVendorGsmSerial(): String {
        return try {
            val key = "vendor.gsm.serial"
            // 先尝试通过反射获取
            var value = getSystemProperty(key)
            
            if (value.isEmpty()) {
                // 如果反射失败，尝试通过getprop命令获取
                value = getSystemPropertyViaGetprop(key)
            }
            
            if (value.isNotEmpty() && value != "unknown" && value != "null") {
                Log.d("SystemPropertyUtil", "成功获取 vendor.gsm.serial: $value")
                value
            } else {
                Log.d("SystemPropertyUtil", "vendor.gsm.serial 为空或无效")
                ""
            }
        } catch (e: Exception) {
            Log.e("SystemPropertyUtil", "获取 vendor.gsm.serial 失败: ${e.message}", e)
            ""
        }
    }
}

