package com.example.testauto

import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.fingerprint.FingerprintManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 锁屏解锁方式检测工具类
 * 
 * 用于检测设备是否设置了以下解锁方式：
 * 1. 密码/PIN/图案解锁
 * 2. 指纹解锁
 * 3. 人脸解锁
 * 
 * @author TestAuto Team
 * @since 2025-01-XX
 */
object LockScreenDetection {
    private const val TAG = "LockScreenDetection"

    /**
     * 锁屏状态数据类
     */
    data class LockScreenStatus(
        val hasPassword: Boolean,           // 是否有密码/PIN/图案
        val passwordType: String,            // 密码类型 (NONE, PASSWORD, PIN, PATTERN)
        val hasFingerprint: Boolean,         // 是否有指纹解锁
        val fingerprintCount: Int,          // 已注册指纹数量
        val hasFaceUnlock: Boolean,         // 是否有人脸解锁
        val isDeviceSecure: Boolean         // 设备是否有安全保护 (通过 KeyguardManager)
    )

    /**
     * 检测锁屏状态
     * 
     * @param context 应用上下文
     * @return LockScreenStatus 锁屏状态信息
     */
    fun detectLockScreenStatus(context: Context): LockScreenStatus {
        val hasPassword = checkPasswordUnlock(context)
        val passwordType = getPasswordType(context)
        val fingerprintInfo = checkFingerprintUnlock(context)
        val hasFaceUnlock = checkFaceUnlock(context)
        val isDeviceSecure = checkDeviceSecure(context)

        return LockScreenStatus(
            hasPassword = hasPassword,
            passwordType = passwordType,
            hasFingerprint = fingerprintInfo.first,
            fingerprintCount = fingerprintInfo.second,
            hasFaceUnlock = hasFaceUnlock,
            isDeviceSecure = isDeviceSecure
        )
    }

    /**
     * 检查是否有密码/PIN/图案解锁
     * 
     * @param context 应用上下文
     * @return true 如果设置了密码/PIN/图案，false 否则
     */
    fun checkPasswordUnlock(context: Context): Boolean {
        return try {
            val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.isKeyguardSecure
        } catch (e: Exception) {
            Log.e(TAG, "检查密码解锁失败: ${e.message}", e)
            false
        }
    }

    /**
     * 获取密码类型
     * 
     * @param context 应用上下文
     * @return 密码类型字符串 (NONE, PASSWORD, PIN, PATTERN)
     */
    fun getPasswordType(context: Context): String {
        return try {
            // 尝试通过 dumpsys 命令获取密码类型（需要 root 权限）
            val process = Runtime.getRuntime().exec("su -c 'dumpsys lock_settings | grep CredentialType:'")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val line = reader.readLine()
            reader.close()
            process.destroy()

            if (line != null && line.contains("CredentialType:")) {
                val type = line.split("CredentialType:")[1].trim()
                Log.d(TAG, "通过 dumpsys 获取密码类型: $type")
                type
            } else {
                // 如果无法通过 dumpsys 获取，根据是否有密码返回
                if (checkPasswordUnlock(context)) {
                    "UNKNOWN" // 有密码但无法确定类型
                } else {
                    "NONE"
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "无法通过 dumpsys 获取密码类型: ${e.message}")
            // 降级方案：根据是否有密码返回
            if (checkPasswordUnlock(context)) {
                "UNKNOWN"
            } else {
                "NONE"
            }
        }
    }

    /**
     * 检查是否有指纹解锁
     * 
     * @param context 应用上下文
     * @return Pair<是否有指纹, 指纹数量>
     */
    fun checkFingerprintUnlock(context: Context): Pair<Boolean, Int> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val fingerprintManager = context.getSystemService(Context.FINGERPRINT_SERVICE) as? FingerprintManager
                
                if (fingerprintManager != null && fingerprintManager.isHardwareDetected) {
                    val hasEnrolled = fingerprintManager.hasEnrolledFingerprints()
                    val count = getFingerprintCount(context)
                    Log.d(TAG, "指纹硬件检测: 已检测, 已注册: $hasEnrolled, 数量: $count")
                    Pair(hasEnrolled, count)
                } else {
                    Log.d(TAG, "指纹硬件未检测到")
                    Pair(false, 0)
                }
            } else {
                Log.d(TAG, "Android 版本过低，不支持指纹 API")
                Pair(false, 0)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "检查指纹解锁失败: 权限不足，需要 USE_BIOMETRIC 或 USE_FINGERPRINT 权限 - ${e.message}", e)
            Pair(false, 0)
        } catch (e: Exception) {
            Log.e(TAG, "检查指纹解锁失败: ${e.message}", e)
            Pair(false, 0)
        }
    }

    /**
     * 获取已注册指纹数量
     * 
     * @param context 应用上下文
     * @return 指纹数量
     */
    private fun getFingerprintCount(context: Context): Int {
        return try {
            // 尝试通过 dumpsys 命令获取指纹数量（需要 root 权限）
            val process = Runtime.getRuntime().exec("su -c 'dumpsys fingerprint | grep -o \\\"count\\\":[0-9]* | cut -d: -f2'")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val line = reader.readLine()?.trim()
            reader.close()
            process.destroy()

            if (line != null && line.isNotEmpty()) {
                val count = line.toIntOrNull() ?: 0
                Log.d(TAG, "通过 dumpsys 获取指纹数量: $count")
                count
            } else {
                // 降级方案：如果有指纹就返回 1，否则返回 0
                try {
                    val fingerprintManager = context.getSystemService(Context.FINGERPRINT_SERVICE) as? FingerprintManager
                    if (fingerprintManager != null && fingerprintManager.hasEnrolledFingerprints()) {
                        1 // 无法获取准确数量，假设至少有一个
                    } else {
                        0
                    }
                } catch (e: SecurityException) {
                    Log.d(TAG, "获取指纹数量失败: 权限不足 - ${e.message}")
                    0
                }
            }
        } catch (e: SecurityException) {
            Log.d(TAG, "无法通过 dumpsys 获取指纹数量: 权限不足 - ${e.message}")
            // 降级方案
            try {
                val fingerprintManager = context.getSystemService(Context.FINGERPRINT_SERVICE) as? FingerprintManager
                if (fingerprintManager != null && fingerprintManager.hasEnrolledFingerprints()) {
                    1
                } else {
                    0
                }
            } catch (se: SecurityException) {
                Log.d(TAG, "获取指纹数量失败: 权限不足 - ${se.message}")
                0
            }
        } catch (e: Exception) {
            Log.d(TAG, "无法通过 dumpsys 获取指纹数量: ${e.message}")
            // 降级方案
            try {
            val fingerprintManager = context.getSystemService(Context.FINGERPRINT_SERVICE) as? FingerprintManager
            if (fingerprintManager != null && fingerprintManager.hasEnrolledFingerprints()) {
                1
            } else {
                    0
                }
            } catch (se: SecurityException) {
                Log.d(TAG, "获取指纹数量失败: 权限不足 - ${se.message}")
                0
            }
        }
    }

    /**
     * 检查是否有人脸解锁
     * 
     * @param context 应用上下文
     * @return true 如果设置了人脸解锁，false 否则
     */
    fun checkFaceUnlock(context: Context): Boolean {
        return try {
            // 方法1: 通过 Settings 检查（MIUI 等定制系统）
            val faceFeature = Settings.Secure.getInt(
                context.contentResolver,
                "face_unlock_has_feature",
                0
            )
            
            if (faceFeature == 1) {
                Log.d(TAG, "通过 Settings 检测到人脸解锁功能")
                return true
            }

            // 方法2: 通过 BiometricManager 检查（Android 11+）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                val biometricManager = context.getSystemService(Context.BIOMETRIC_SERVICE) as? BiometricManager
                if (biometricManager != null) {
                    val canAuthenticate = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                    if (canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS) {
                        Log.d(TAG, "通过 BiometricManager 检测到生物识别功能")
                        // 注意：BiometricManager 无法区分指纹和人脸，只能检测是否有生物识别
                        // 这里假设如果指纹未设置，则可能是人脸
                        val fingerprintInfo = checkFingerprintUnlock(context)
                        if (!fingerprintInfo.first) {
                            return true // 有生物识别但无指纹，可能是人脸
                        }
                    }
                    }
                } catch (e: SecurityException) {
                    Log.d(TAG, "通过 BiometricManager 检查人脸解锁失败: 权限不足 - ${e.message}")
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10 使用旧版 API
                try {
                val biometricManager = context.getSystemService(Context.BIOMETRIC_SERVICE) as? BiometricManager
                if (biometricManager != null) {
                    val canAuthenticate = biometricManager.canAuthenticate()
                    if (canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS) {
                        Log.d(TAG, "通过 BiometricManager 检测到生物识别功能 (Android 10)")
                        val fingerprintInfo = checkFingerprintUnlock(context)
                        if (!fingerprintInfo.first) {
                            return true // 有生物识别但无指纹，可能是人脸
                        }
                    }
                    }
                } catch (e: SecurityException) {
                    Log.d(TAG, "通过 BiometricManager 检查人脸解锁失败: 权限不足 - ${e.message}")
                }
            }

            // 方法3: 尝试通过 dumpsys 命令检查（需要 root 权限）
            try {
                val process = Runtime.getRuntime().exec("su -c 'dumpsys face 2>/dev/null | head -20'")
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val output = reader.readText()
                reader.close()
                process.destroy()

                if (output.isNotEmpty() && output.contains("enrolled", ignoreCase = true)) {
                    Log.d(TAG, "通过 dumpsys 检测到人脸识别服务")
                    return true
                }
            } catch (e: Exception) {
                Log.d(TAG, "无法通过 dumpsys 检查人脸解锁: ${e.message}")
            }

            false
        } catch (e: Exception) {
            Log.e(TAG, "检查人脸解锁失败: ${e.message}", e)
            false
        }
    }

    /**
     * 检查设备是否有安全保护（通过 KeyguardManager）
     * 
     * @param context 应用上下文
     * @return true 如果设备有安全保护，false 否则
     */
    fun checkDeviceSecure(context: Context): Boolean {
        return try {
            val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                keyguardManager.isDeviceSecure
            } else {
                keyguardManager.isKeyguardSecure
            }
        } catch (e: Exception) {
            Log.e(TAG, "检查设备安全状态失败: ${e.message}", e)
            false
        }
    }

    /**
     * 获取锁屏状态的文本描述
     * 
     * @param status 锁屏状态
     * @return 格式化的文本描述
     */
    fun getStatusDescription(status: LockScreenStatus): String {
        val sb = StringBuilder()
        sb.append("=== 锁屏解锁方式状态 ===\n\n")

        // 密码/PIN/图案
        sb.append("[1] 密码/PIN/图案解锁:\n")
        if (status.hasPassword) {
            sb.append("  ✓ 已设置 (类型: ${status.passwordType})\n")
        } else {
            sb.append("  ✗ 未设置\n")
        }
        sb.append("\n")

        // 指纹
        sb.append("[2] 指纹解锁:\n")
        if (status.hasFingerprint) {
            sb.append("  ✓ 已设置 (已注册 ${status.fingerprintCount} 个指纹)\n")
        } else {
            sb.append("  ✗ 未设置\n")
        }
        sb.append("\n")

        // 人脸
        sb.append("[3] 人脸解锁:\n")
        if (status.hasFaceUnlock) {
            sb.append("  ✓ 已设置\n")
        } else {
            sb.append("  ✗ 未设置\n")
        }
        sb.append("\n")

        // 设备安全状态
        sb.append("[4] 设备安全状态:\n")
        sb.append("  - isDeviceSecure: ${status.isDeviceSecure}\n")
        sb.append("  - isKeyguardSecure: ${status.hasPassword}\n")

        return sb.toString()
    }
}

