package com.example.testauto

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

object VpnDetection {

    private const val TAG = "VpnDetection"

    data class DetectionResult(
        val method: String,
        val detected: Boolean,
        val detail: String
    )

    /**
     * 执行所有检测，返回汇总结果
     */
    fun detectAll(context: Context): List<DetectionResult> {
        val results = mutableListOf<DetectionResult>()
        results.add(checkTransportVpn(context))
        results.add(checkTunInterface())
        results.add(checkPppInterface())
        results.add(checkProcNetTcp())
        results.addAll(checkProxyPorts())
        results.add(checkHttpProxy())
        results.add(checkProcNetRoute())
        results.add(checkDnsLeak())
        results.add(checkProxyProcesses())
        results.add(checkBoxFiles())
        return results
    }

    fun formatResults(results: List<DetectionResult>): String {
        val detected = results.filter { it.detected }
        val sb = StringBuilder()
        sb.appendLine("=== VPN/代理 综合检测 ===")
        sb.appendLine("检测项: ${results.size} | 命中: ${detected.size}")
        sb.appendLine()
        for (r in results) {
            val flag = if (r.detected) "[!]" else "[ok]"
            sb.appendLine("$flag ${r.method}")
            sb.appendLine("    ${r.detail}")
        }
        sb.appendLine()
        if (detected.isNotEmpty()) {
            sb.appendLine(">>> 结论: 检测到 VPN/代理 (${detected.size} 项命中)")
        } else {
            sb.appendLine(">>> 结论: 未检测到 VPN/代理")
        }
        return sb.toString()
    }

    // ==================== 1. TRANSPORT_VPN (原有方法) ====================

    private fun checkTransportVpn(context: Context): DetectionResult {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val networks = cm.allNetworks
            var vpnFound = false
            val vpnDetails = mutableListOf<String>()
            for (network in networks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    vpnFound = true
                    val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    vpnDetails.add("network=$network, validated=$validated")
                }
            }
            DetectionResult(
                "ConnectivityManager TRANSPORT_VPN",
                vpnFound,
                if (vpnFound) "发现VPN网络: ${vpnDetails.joinToString("; ")}" else "未发现VPN传输层"
            )
        } catch (e: Exception) {
            DetectionResult("ConnectivityManager TRANSPORT_VPN", false, "检测异常: ${e.message}")
        }
    }

    // ==================== 2. 网络接口检测 (tun/ppp) ====================

    private fun checkTunInterface(): DetectionResult {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
            val tunInterfaces = interfaces.filter {
                val name = it.name.lowercase()
                (name.startsWith("tun") || name.startsWith("utun")) && it.isUp
            }
            val names = tunInterfaces.map { "${it.name} (up=${it.isUp}, addrs=${it.inetAddresses.toList().map { a -> a.hostAddress }})" }
            DetectionResult(
                "TUN 网络接口",
                tunInterfaces.isNotEmpty(),
                if (tunInterfaces.isNotEmpty()) "发现TUN接口: ${names.joinToString("; ")}" else "未发现TUN接口"
            )
        } catch (e: Exception) {
            DetectionResult("TUN 网络接口", false, "检测异常: ${e.message}")
        }
    }

    private fun checkPppInterface(): DetectionResult {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
            val pppInterfaces = interfaces.filter {
                it.name.lowercase().startsWith("ppp") && it.isUp
            }
            DetectionResult(
                "PPP 网络接口",
                pppInterfaces.isNotEmpty(),
                if (pppInterfaces.isNotEmpty()) "发现PPP接口: ${pppInterfaces.map { it.name }}" else "未发现PPP接口"
            )
        } catch (e: Exception) {
            DetectionResult("PPP 网络接口", false, "检测异常: ${e.message}")
        }
    }

    // ==================== 3. /proc/net/tcp 本地端口扫描 ====================

    private fun checkProcNetTcp(): DetectionResult {
        return try {
            val suspiciousPorts = mutableListOf<String>()
            // 常见代理本地监听端口
            val knownPorts = mapOf(
                "1080" to "SOCKS",
                "1081" to "SOCKS",
                "7890" to "mihomo/Clash",
                "7891" to "mihomo/Clash",
                "7892" to "mihomo/Clash",
                "7893" to "mihomo/Clash",
                "9090" to "Clash控制面板",
                "8080" to "HTTP代理",
                "8118" to "Privoxy",
                "10808" to "V2Ray SOCKS",
                "10809" to "V2Ray HTTP",
                "2080" to "sing-box",
                "2081" to "sing-box",
                "6450" to "sing-box TProxy",
            )

            for (path in listOf("/proc/net/tcp", "/proc/net/tcp6")) {
                val file = File(path)
                if (!file.canRead()) continue
                val lines = file.readLines()
                for (line in lines.drop(1)) { // 跳过标题行
                    val parts = line.trim().split("\\s+".toRegex())
                    if (parts.size < 4) continue
                    val localAddr = parts[1]
                    val state = parts[3]
                    // state=0A 是 LISTEN
                    if (state != "0A") continue
                    val portHex = localAddr.substringAfter(":")
                    val port = portHex.toIntOrNull(16)?.toString() ?: continue
                    val addrHex = localAddr.substringBefore(":")
                    // 只关注 127.0.0.1 (0100007F) 或 0.0.0.0 (00000000) 上的监听
                    val isLocal = addrHex == "0100007F" || addrHex == "00000000" ||
                            addrHex == "00000000000000000000000000000000" || // :: in tcp6
                            addrHex == "00000000000000000000000001000000"    // ::1 in tcp6
                    if (!isLocal) continue

                    if (knownPorts.containsKey(port)) {
                        suspiciousPorts.add("port=$port (${knownPorts[port]})")
                    }
                }
            }

            DetectionResult(
                "/proc/net/tcp 代理端口",
                suspiciousPorts.isNotEmpty(),
                if (suspiciousPorts.isNotEmpty()) "发现可疑监听: ${suspiciousPorts.joinToString(", ")}"
                else "未发现已知代理端口监听"
            )
        } catch (e: Exception) {
            DetectionResult("/proc/net/tcp 代理端口", false, "检测异常: ${e.message}")
        }
    }

    // ==================== 4. Socket 连接探测常见代理端口 ====================

    private fun checkProxyPorts(): List<DetectionResult> {
        val portsToCheck = listOf(7890, 1080, 9090, 10808, 10809, 8080, 2080)
        val results = mutableListOf<DetectionResult>()
        val openPorts = mutableListOf<Int>()
        for (port in portsToCheck) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 100)
                    openPorts.add(port)
                }
            } catch (_: Exception) {
                // 端口未开放，正常
            }
        }
        results.add(
            DetectionResult(
                "Socket 探测代理端口",
                openPorts.isNotEmpty(),
                if (openPorts.isNotEmpty()) "127.0.0.1 上开放的代理端口: ${openPorts.joinToString(", ")}"
                else "未探测到开放的代理端口"
            )
        )
        return results
    }

    // ==================== 5. 系统 HTTP 代理设置 ====================

    private fun checkHttpProxy(): DetectionResult {
        val host = System.getProperty("http.proxyHost") ?: ""
        val port = System.getProperty("http.proxyPort") ?: ""
        val httpsHost = System.getProperty("https.proxyHost") ?: ""
        val httpsPort = System.getProperty("https.proxyPort") ?: ""
        val socksHost = System.getProperty("socksProxyHost") ?: ""
        val socksPort = System.getProperty("socksProxyPort") ?: ""

        val proxies = mutableListOf<String>()
        if (host.isNotEmpty()) proxies.add("http=$host:$port")
        if (httpsHost.isNotEmpty()) proxies.add("https=$httpsHost:$httpsPort")
        if (socksHost.isNotEmpty()) proxies.add("socks=$socksHost:$socksPort")

        return DetectionResult(
            "系统代理设置",
            proxies.isNotEmpty(),
            if (proxies.isNotEmpty()) "检测到系统代理: ${proxies.joinToString(", ")}" else "未设置系统代理"
        )
    }

    // ==================== 6. /proc/net/route 路由表 ====================

    private fun checkProcNetRoute(): DetectionResult {
        return try {
            val file = File("/proc/net/route")
            if (!file.canRead()) {
                return DetectionResult("/proc/net/route 路由表", false, "无法读取路由表")
            }
            val lines = file.readLines()
            val tunRoutes = mutableListOf<String>()
            for (line in lines.drop(1)) {
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.isEmpty()) continue
                val iface = parts[0]
                if (iface.startsWith("tun") || iface.startsWith("utun")) {
                    val destHex = parts.getOrElse(1) { "" }
                    val maskHex = parts.getOrElse(7) { "" }
                    tunRoutes.add("iface=$iface dest=$destHex mask=$maskHex")
                }
            }
            DetectionResult(
                "/proc/net/route 路由表",
                tunRoutes.isNotEmpty(),
                if (tunRoutes.isNotEmpty()) "发现TUN路由: ${tunRoutes.joinToString("; ")}" else "路由表中无TUN接口"
            )
        } catch (e: Exception) {
            DetectionResult("/proc/net/route 路由表", false, "检测异常: ${e.message}")
        }
    }

    // ==================== 7. DNS 泄漏检测 ====================

    private fun checkDnsLeak(): DetectionResult {
        return try {
            val startTime = System.nanoTime()
            val addr = java.net.InetAddress.getByName("google.com")
            val elapsed = (System.nanoTime() - startTime) / 1_000_000 // ms
            val ip = addr.hostAddress ?: "unknown"

            // fakeip 特征: 198.18.x.x 或解析异常快 (<2ms)
            val isFakeIp = ip.startsWith("198.18.") || ip.startsWith("198.19.")
            val isSuspiciouslyFast = elapsed < 2

            val detail = "google.com -> $ip (${elapsed}ms)"
            val detected = isFakeIp || isSuspiciouslyFast

            DetectionResult(
                "DNS FakeIP 检测",
                detected,
                when {
                    isFakeIp -> "$detail [FakeIP 地址段]"
                    isSuspiciouslyFast -> "$detail [解析异常快，疑似本地劫持]"
                    else -> "$detail [正常]"
                }
            )
        } catch (e: Exception) {
            DetectionResult("DNS FakeIP 检测", false, "DNS解析异常: ${e.message}")
        }
    }

    // ==================== 8. 代理进程名检测 (/proc) ====================

    private fun checkProxyProcesses(): DetectionResult {
        return try {
            val knownProcessNames = listOf(
                "sing-box", "mihomo", "clash", "xray", "v2ray",
                "v2fly", "hysteria", "trojan", "ss-local", "ss-tunnel",
                "tun2socks", "hev-socks5-tunnel", "shadowsocks"
            )
            val found = mutableListOf<String>()

            val procDir = File("/proc")
            val pidDirs = procDir.listFiles()?.filter {
                it.isDirectory && it.name.all { c -> c.isDigit() }
            } ?: emptyList()

            for (pidDir in pidDirs) {
                try {
                    val cmdline = File(pidDir, "cmdline").readText().replace('\u0000', ' ').trim()
                    if (cmdline.isEmpty()) continue
                    val processName = cmdline.split(" ").first().split("/").last()
                    for (known in knownProcessNames) {
                        if (processName.contains(known, ignoreCase = true)) {
                            found.add("pid=${pidDir.name} cmd=$cmdline")
                            break
                        }
                    }
                } catch (_: Exception) {
                    // 无权读取某些 pid，正常
                }
            }

            DetectionResult(
                "代理进程检测",
                found.isNotEmpty(),
                if (found.isNotEmpty()) "发现代理进程:\n      ${found.joinToString("\n      ")}"
                else "未发现已知代理进程"
            )
        } catch (e: Exception) {
            DetectionResult("代理进程检测", false, "检测异常: ${e.message}")
        }
    }

    // ==================== 9. Box 模块文件检测 ====================

    private fun checkBoxFiles(): DetectionResult {
        val paths = listOf(
            "/data/adb/box",
            "/data/adb/modules/box_for_magisk",
            "/data/adb/modules/box_for_root",
        )
        val found = mutableListOf<String>()
        for (path in paths) {
            try {
                if (File(path).exists()) {
                    found.add(path)
                }
            } catch (_: Exception) {
                // 无权限访问
            }
        }
        return DetectionResult(
            "Box/Magisk 模块文件",
            found.isNotEmpty(),
            if (found.isNotEmpty()) "发现模块路径: ${found.joinToString(", ")}" else "未发现模块文件 (可能无权限)"
        )
    }
}
