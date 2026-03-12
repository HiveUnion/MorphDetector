package com.example.testauto

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 粘贴操作检测页面 — 对比所有粘贴路径的参数差异
 *
 * 检测路径：
 * A. input keyevent KEYCODE_PASTE (279) — 走 dispatchKeyEvent，有完整 KeyEvent 参数
 * B. input keyevent Ctrl+V              — 走 dispatchKeyEvent，有完整 KeyEvent 参数
 * C. 长按菜单「粘贴」                      — 走 onTextContextMenuItem，无 KeyEvent
 * D. performAction(ACTION_PASTE)         — 走 onTextContextMenuItem，无 KeyEvent
 * E. ACTION_SET_TEXT                      — 走 setText，无 KeyEvent，无粘贴
 *
 * 对比维度：
 * - 路径 A/B: source, deviceId, scanCode, flags, pressDuration, metaState
 * - 路径 C/D: 仅有 menuItemId + 剪贴板内容 + 时间戳
 * - 剪贴板时序: copy→paste 间隔
 */
class KeyEventPasteDetectionActivity : AppCompatActivity() {

    private lateinit var tvVerdict: TextView
    private lateinit var tvEventDetails: TextView
    private lateinit var tvHistory: TextView
    private lateinit var tvClipboardInfo: TextView
    private lateinit var etInputArea: PasteMonitorEditText
    private lateinit var btnClearHistory: Button

    private val historyRecords = mutableListOf<String>()
    private var eventCounter = 0

    // 剪贴板变化追踪
    private var lastClipChangeTime = 0L
    private var clipChangeCount = 0
    private var lastClipContent: String? = null

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_keyevent_paste_detection)

        tvVerdict = findViewById(R.id.tvVerdict)
        tvEventDetails = findViewById(R.id.tvEventDetails)
        tvHistory = findViewById(R.id.tvHistory)
        tvClipboardInfo = findViewById(R.id.tvClipboardInfo)
        etInputArea = findViewById(R.id.etInputArea)
        btnClearHistory = findViewById(R.id.btnClearHistory)

        etInputArea.setText("")
        etInputArea.hint = "点击此处获取焦点，然后触发粘贴..."

        btnClearHistory.setOnClickListener {
            historyRecords.clear()
            eventCounter = 0
            clipChangeCount = 0
            tvHistory.text = "暂无记录"
            tvVerdict.text = "等待粘贴事件..."
            tvEventDetails.text = INITIAL_HINT
            tvClipboardInfo.text = "等待剪贴板变化..."
            etInputArea.setText("")
        }

        setupClipboardListener()
        setupPasteMonitoring()
    }

    /**
     * 设置 PasteMonitorEditText 的回调，监听所有粘贴路径
     */
    private fun setupPasteMonitoring() {
        // 路径 A/B: KeyEvent 粘贴 (input keyevent 279 / Ctrl+V)
        etInputArea.onKeyEventPaste = { event ->
            analyzeKeyEventPaste(event)
        }

        // 路径 C/D: 长按菜单粘贴 / performAction(ACTION_PASTE)
        etInputArea.onContextMenuPaste = { menuItemId, clipboardText ->
            analyzeContextMenuPaste(menuItemId, clipboardText)
        }
    }

    /**
     * 分析 KeyEvent 粘贴 (input keyevent KEYCODE_PASTE / Ctrl+V)
     */
    private fun analyzeKeyEventPaste(event: KeyEvent) {
        eventCounter++
        val timestamp = timeFormat.format(Date())

        val source = event.source
        val deviceId = event.deviceId
        val flags = event.flags
        val downTime = event.downTime
        val eventTime = event.eventTime
        val pressDuration = eventTime - downTime
        val scanCode = event.scanCode
        val repeatCount = event.repeatCount
        val metaState = event.metaState
        val isCtrlV = event.keyCode == KeyEvent.KEYCODE_V && event.isCtrlPressed

        // ===== 各维度评分 =====
        var injectedScore = 0
        var realScore = 0
        val reasons = mutableListOf<String>()

        val pasteMethod = if (isCtrlV) "Ctrl+V 组合键" else "KEYCODE_PASTE (279)"
        reasons.add("路径: KeyEvent ($pasteMethod)")

        // 1. source
        val sourceName = sourceToString(source)
        when {
            source == InputDevice.SOURCE_KEYBOARD -> {
                reasons.add("source=$sourceName($source) -> 可能注入或物理键盘")
            }
            source == 0 -> {
                injectedScore += 2
                reasons.add("source=0 -> 高度疑似注入(无输入设备)")
            }
            else -> {
                realScore++
                reasons.add("source=$sourceName($source) -> 疑似真实设备")
            }
        }

        // 2. deviceId
        when {
            deviceId == -1 -> {
                injectedScore += 2
                reasons.add("deviceId=$deviceId -> 高度疑似注入(VIRTUAL_KEYBOARD)")
            }
            deviceId == 0 -> {
                injectedScore++
                reasons.add("deviceId=$deviceId -> 疑似注入(虚拟设备)")
            }
            else -> {
                realScore++
                reasons.add("deviceId=$deviceId -> 疑似真实(物理设备ID)")
            }
        }

        // 3. scanCode
        when {
            scanCode == 0 -> {
                injectedScore++
                reasons.add("scanCode=$scanCode -> 疑似注入(无扫描码)")
            }
            else -> {
                realScore++
                reasons.add("scanCode=$scanCode -> 疑似真实(有物理扫描码)")
            }
        }

        // 4. pressDuration
        when {
            pressDuration == 0L -> {
                injectedScore++
                reasons.add("pressDuration=${pressDuration}ms -> 疑似注入(DOWN/UP同时)")
            }
            pressDuration in 1..30 -> {
                reasons.add("pressDuration=${pressDuration}ms -> 可疑(极短按压)")
            }
            pressDuration in 31..2000 -> {
                realScore++
                reasons.add("pressDuration=${pressDuration}ms -> 疑似真实(正常按压)")
            }
            else -> {
                reasons.add("pressDuration=${pressDuration}ms -> 异常(过长)")
            }
        }

        // 5. flags
        val flagDetails = flagsToString(flags)
        if (flags and KeyEvent.FLAG_FROM_SYSTEM != 0) {
            reasons.add("flags: FLAG_FROM_SYSTEM")
        }
        if (flags and KeyEvent.FLAG_SOFT_KEYBOARD != 0) {
            realScore++
            reasons.add("flags: FLAG_SOFT_KEYBOARD")
        }
        if (flags and KeyEvent.FLAG_VIRTUAL_HARD_KEY != 0) {
            realScore++
            reasons.add("flags: FLAG_VIRTUAL_HARD_KEY")
        }
        if (flags == 0) {
            reasons.add("flags=0 -> 无特殊标记")
        }

        // 6. metaState
        val metaDetails = metaStateToString(metaState)
        if (isCtrlV) {
            reasons.add("metaState=$metaDetails -> Ctrl 组合键")
            if (deviceId == -1 || deviceId == 0) {
                injectedScore++
                reasons.add("Ctrl+V 但 deviceId=$deviceId -> 注入模拟组合键")
            }
        }

        // 7. 剪贴板时序
        val timeSinceClipChange = System.currentTimeMillis() - lastClipChangeTime
        if (lastClipChangeTime > 0) {
            when {
                timeSinceClipChange < 100 -> {
                    injectedScore++
                    reasons.add("剪贴板刚变化(${timeSinceClipChange}ms前) -> 疑似自动化 copy+paste")
                }
                timeSinceClipChange < 500 -> {
                    reasons.add("剪贴板近期变化(${timeSinceClipChange}ms前) -> 可疑")
                }
                else -> {
                    reasons.add("剪贴板变化: ${timeSinceClipChange}ms前 -> 正常")
                }
            }
        }

        // 8. repeatCount
        if (repeatCount > 0) {
            reasons.add("repeatCount=$repeatCount -> 长按重复粘贴(异常)")
            injectedScore++
        }

        // ===== 综合判定 =====
        val verdict = when {
            injectedScore >= 4 -> "KeyEvent 注入粘贴"
            injectedScore >= 3 -> "高度疑似 KeyEvent 注入"
            injectedScore >= 2 && realScore == 0 -> "疑似 KeyEvent 注入"
            realScore >= 3 -> "真实物理键盘粘贴"
            realScore >= 2 && injectedScore == 0 -> "疑似真实粘贴"
            else -> "无法明确判断 (注入:$injectedScore 真实:$realScore)"
        }

        val verdictColor = when {
            injectedScore >= 2 && realScore <= 1 -> getColor(R.color.error)
            realScore >= 2 && injectedScore <= 1 -> getColor(R.color.success)
            else -> getColor(R.color.warning)
        }

        tvVerdict.text = verdict
        tvVerdict.setTextColor(verdictColor)

        val detailText = buildString {
            append("=== KeyEvent 粘贴参数 ===\n")
            append("触发方式     : $pasteMethod\n")
            append("action       : DOWN\n")
            append("keyCode      : ${event.keyCode} (${KeyEvent.keyCodeToString(event.keyCode)})\n")
            append("source       : $sourceName ($source / 0x${Integer.toHexString(source)})\n")
            append("deviceId     : $deviceId\n")
            append("scanCode     : $scanCode\n")
            append("flags        : $flagDetails ($flags / 0x${Integer.toHexString(flags)})\n")
            append("metaState    : $metaDetails ($metaState / 0x${Integer.toHexString(metaState)})\n")
            append("downTime     : $downTime\n")
            append("eventTime    : $eventTime\n")
            append("pressDuration: ${pressDuration}ms\n")
            append("repeatCount  : $repeatCount\n")
            append("isCtrlPressed: ${event.isCtrlPressed}\n")
            append("\n=== 剪贴板状态 ===\n")
            val clipPreview = lastClipContent?.let {
                if (it.length > 30) it.take(30) + "..." else it
            } ?: "(空)"
            append("内容: \"$clipPreview\"\n")
            append("距上次变化: ${if (lastClipChangeTime > 0) "${timeSinceClipChange}ms" else "无记录"}\n")
            append("\n=== 评分 ===\n")
            append("注入得分: $injectedScore  真实得分: $realScore\n")
            append("\n=== 分析细节 ===\n")
            reasons.forEachIndexed { i, r -> append("${i + 1}. $r\n") }
        }
        tvEventDetails.text = detailText

        val record = buildString {
            append("#$eventCounter [$timestamp] [KeyEvent] $verdict\n")
            append("  $pasteMethod src=$sourceName dev=$deviceId scan=$scanCode\n")
            append("  press=${pressDuration}ms flags=$flagDetails meta=$metaDetails")
        }
        addHistory(record)
    }

    /**
     * 分析长按菜单粘贴 / performAction(ACTION_PASTE)
     * 这个路径没有 KeyEvent，只能获取 menuItemId 和剪贴板内容
     */
    private fun analyzeContextMenuPaste(menuItemId: Int, clipboardText: String?) {
        eventCounter++
        val timestamp = timeFormat.format(Date())
        val timeSinceClipChange = System.currentTimeMillis() - lastClipChangeTime

        val menuName = when (menuItemId) {
            android.R.id.paste -> "paste"
            android.R.id.pasteAsPlainText -> "pasteAsPlainText"
            else -> "unknown($menuItemId)"
        }

        val clipPreview = clipboardText?.let {
            if (it.length > 50) it.take(50) + "..." else it
        } ?: "(空)"

        // 长按粘贴无法从 KeyEvent 维度判断注入，但可以从时序判断
        val reasons = mutableListOf<String>()
        reasons.add("路径: onTextContextMenuItem ($menuName)")
        reasons.add("说明: 无 KeyEvent 产生，无法检测 source/deviceId/scanCode")

        var suspicious = false
        if (lastClipChangeTime > 0) {
            when {
                timeSinceClipChange < 100 -> {
                    suspicious = true
                    reasons.add("剪贴板刚变化(${timeSinceClipChange}ms前) -> 疑似自动化 copy+paste")
                }
                timeSinceClipChange < 500 -> {
                    reasons.add("剪贴板近期变化(${timeSinceClipChange}ms前) -> 可疑")
                }
                else -> {
                    reasons.add("剪贴板变化: ${timeSinceClipChange}ms前 -> 正常")
                }
            }
        }

        // 判定：长按粘贴和 performAction(ACTION_PASTE) 走同一路径，
        // 无法从参数区分，只能从剪贴板时序推断
        val verdict = when {
            suspicious -> "长按/无障碍粘贴 (剪贴板时序可疑)"
            else -> "长按/无障碍粘贴 (正常)"
        }
        val verdictColor = when {
            suspicious -> getColor(R.color.warning)
            else -> getColor(R.color.success)
        }

        tvVerdict.text = verdict
        tvVerdict.setTextColor(verdictColor)

        val detailText = buildString {
            append("=== 长按菜单粘贴参数 ===\n")
            append("触发方式     : onTextContextMenuItem\n")
            append("menuItemId   : $menuName (${menuItemId})\n")
            append("                android.R.id.paste=${android.R.id.paste}\n")
            append("                android.R.id.pasteAsPlainText=${android.R.id.pasteAsPlainText}\n")
            append("\n")
            append("=== 无 KeyEvent 参数 ===\n")
            append("source       : N/A (不走 dispatchKeyEvent)\n")
            append("deviceId     : N/A\n")
            append("scanCode     : N/A\n")
            append("flags        : N/A\n")
            append("metaState    : N/A\n")
            append("pressDuration: N/A\n")
            append("\n=== 剪贴板状态 ===\n")
            append("内容: \"$clipPreview\"\n")
            append("内容长度: ${clipboardText?.length ?: 0}\n")
            append("距上次变化: ${if (lastClipChangeTime > 0) "${timeSinceClipChange}ms" else "无记录"}\n")
            append("\n=== 分析细节 ===\n")
            reasons.forEachIndexed { i, r -> append("${i + 1}. $r\n") }
            append("\n=== 对比说明 ===\n")
            append("长按菜单粘贴 和 performAction(ACTION_PASTE)\n")
            append("都走 onTextContextMenuItem，参数完全相同，\n")
            append("无法从参数层面区分两者。\n")
            append("而 input keyevent 279 走 dispatchKeyEvent，\n")
            append("有完整 KeyEvent 参数可供检测。")
        }
        tvEventDetails.text = detailText

        val record = buildString {
            append("#$eventCounter [$timestamp] [ContextMenu] $verdict\n")
            append("  menu=$menuName clip=\"${clipPreview.take(20)}\"\n")
            append("  clipAge=${if (lastClipChangeTime > 0) "${timeSinceClipChange}ms" else "N/A"}")
        }
        addHistory(record)
    }

    private fun setupClipboardListener() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.addPrimaryClipChangedListener {
            val now = System.currentTimeMillis()
            val interval = if (lastClipChangeTime > 0) now - lastClipChangeTime else -1L
            lastClipChangeTime = now
            clipChangeCount++

            val clipText = try {
                cm.primaryClip?.getItemAt(0)?.text?.toString()
            } catch (e: Exception) {
                null
            }
            lastClipContent = clipText

            val clipPreview = if (clipText != null && clipText.length > 50) {
                clipText.take(50) + "..."
            } else {
                clipText ?: "(空)"
            }

            val timestamp = timeFormat.format(Date())
            runOnUiThread {
                tvClipboardInfo.text = buildString {
                    append("最近剪贴板变化: $timestamp\n")
                    append("内容: \"$clipPreview\"\n")
                    append("距上次变化: ${if (interval >= 0) "${interval}ms" else "首次"}\n")
                    append("累计变化次数: $clipChangeCount")
                    if (interval in 0..500) {
                        append("\n⚠️ 高频操作 (${interval}ms < 500ms)")
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun addHistory(record: String) {
        historyRecords.add(0, record)
        if (historyRecords.size > 20) {
            historyRecords.removeAt(historyRecords.size - 1)
        }
        tvHistory.text = historyRecords.joinToString("\n\n")
    }

    private fun sourceToString(source: Int): String {
        val names = mutableListOf<String>()
        if (source and InputDevice.SOURCE_KEYBOARD != 0) names.add("KEYBOARD")
        if (source and InputDevice.SOURCE_DPAD != 0) names.add("DPAD")
        if (source and InputDevice.SOURCE_GAMEPAD != 0) names.add("GAMEPAD")
        if (source and InputDevice.SOURCE_TOUCHSCREEN != 0) names.add("TOUCHSCREEN")
        if (source and InputDevice.SOURCE_MOUSE != 0) names.add("MOUSE")
        if (source and InputDevice.SOURCE_TRACKBALL != 0) names.add("TRACKBALL")
        if (source and InputDevice.SOURCE_JOYSTICK != 0) names.add("JOYSTICK")
        if (source == 0) names.add("UNKNOWN(0)")
        return if (names.isEmpty()) "OTHER" else names.joinToString("|")
    }

    private fun flagsToString(flags: Int): String {
        if (flags == 0) return "NONE"
        val names = mutableListOf<String>()
        if (flags and KeyEvent.FLAG_WOKE_HERE != 0) names.add("WOKE_HERE")
        if (flags and KeyEvent.FLAG_SOFT_KEYBOARD != 0) names.add("SOFT_KEYBOARD")
        if (flags and KeyEvent.FLAG_KEEP_TOUCH_MODE != 0) names.add("KEEP_TOUCH_MODE")
        if (flags and KeyEvent.FLAG_FROM_SYSTEM != 0) names.add("FROM_SYSTEM")
        if (flags and KeyEvent.FLAG_EDITOR_ACTION != 0) names.add("EDITOR_ACTION")
        if (flags and KeyEvent.FLAG_CANCELED != 0) names.add("CANCELED")
        if (flags and KeyEvent.FLAG_VIRTUAL_HARD_KEY != 0) names.add("VIRTUAL_HARD_KEY")
        if (flags and KeyEvent.FLAG_LONG_PRESS != 0) names.add("LONG_PRESS")
        if (flags and KeyEvent.FLAG_TRACKING != 0) names.add("TRACKING")
        if (flags and KeyEvent.FLAG_FALLBACK != 0) names.add("FALLBACK")
        return if (names.isEmpty()) "0x${Integer.toHexString(flags)}" else names.joinToString("|")
    }

    private fun metaStateToString(metaState: Int): String {
        if (metaState == 0) return "NONE"
        val names = mutableListOf<String>()
        if (metaState and KeyEvent.META_CTRL_ON != 0) names.add("CTRL")
        if (metaState and KeyEvent.META_SHIFT_ON != 0) names.add("SHIFT")
        if (metaState and KeyEvent.META_ALT_ON != 0) names.add("ALT")
        if (metaState and KeyEvent.META_META_ON != 0) names.add("META")
        if (metaState and KeyEvent.META_CAPS_LOCK_ON != 0) names.add("CAPS_LOCK")
        if (metaState and KeyEvent.META_NUM_LOCK_ON != 0) names.add("NUM_LOCK")
        return if (names.isEmpty()) "0x${Integer.toHexString(metaState)}" else names.joinToString("|")
    }

    companion object {
        private const val INITIAL_HINT = "请在输入框中触发粘贴操作\n\n" +
            "测试方式：\n" +
            "1. input keyevent 279 (注入) → KeyEvent 路径\n" +
            "2. input keyevent KEYCODE_PASTE (注入) → KeyEvent 路径\n" +
            "3. 长按菜单粘贴 (正常) → ContextMenu 路径\n" +
            "4. 物理键盘 Ctrl+V (正常) → KeyEvent 路径\n" +
            "5. performAction(ACTION_PASTE) → ContextMenu 路径"
    }
}
