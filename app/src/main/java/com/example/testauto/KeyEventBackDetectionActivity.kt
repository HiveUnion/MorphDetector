package com.example.testauto

import android.os.Build
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.widget.Button
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * KeyEvent 返回键检测页面
 *
 * 检测维度：
 * 1. source — 注入 keyevent 4 的 source 通常是 SOURCE_KEYBOARD(257)
 * 2. deviceId — 注入事件 deviceId=0（虚拟设备），真实按键有物理设备 ID
 * 3. flags — 检查 FLAG_FROM_SYSTEM 等标记
 * 4. eventTime - downTime — 注入事件两者几乎相同（差值 0ms），真实按键有按压时长
 * 5. scanCode — 注入 scanCode=0，真实物理按键有非零 scanCode
 * 6. 无障碍服务返回 — 不走 KeyEvent，直接触发 onBackPressed
 * 7. 手势导航返回 — 不走 KeyEvent，走 OnBackPressedCallback
 *
 * 设计：不拦截手势返回，手势返回可以正常退出页面。
 * 只拦截 KeyEvent BACK 用于分析，分析完后放行。
 */
class KeyEventBackDetectionActivity : AppCompatActivity() {

    private lateinit var tvVerdict: TextView
    private lateinit var tvEventDetails: TextView
    private lateinit var tvHistory: TextView
    private lateinit var btnClearHistory: Button

    private val historyRecords = mutableListOf<String>()
    private var eventCounter = 0

    // 记录最近一次 DOWN 事件的时间戳，用于配对 UP 事件
    private var lastDownTime = 0L
    private var lastDownEventTime = 0L
    private var lastDownSource = 0
    private var lastDownDeviceId = -1
    private var lastDownScanCode = 0
    private var lastDownFlags = 0

    // 标记：是否有来自 OnBackPressedCallback 的手势/无障碍返回
    private var gestureBackTriggered = false

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_keyevent_back_detection)

        tvVerdict = findViewById(R.id.tvVerdict)
        tvEventDetails = findViewById(R.id.tvEventDetails)
        tvHistory = findViewById(R.id.tvHistory)
        btnClearHistory = findViewById(R.id.btnClearHistory)

        btnClearHistory.setOnClickListener {
            historyRecords.clear()
            eventCounter = 0
            tvHistory.text = "暂无记录"
            tvVerdict.text = "等待返回事件..."
            tvEventDetails.text = "等待事件..."
        }

        // 注册手势返回回调 — 不消费事件，仅做标记
        // 如果是手势导航返回或无障碍 GLOBAL_ACTION_BACK，会走这个回调而不走 dispatchKeyEvent
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 能走到这里说明是手势返回或无障碍 GLOBAL_ACTION_BACK
                // 因为 input keyevent 4 已经在 dispatchKeyEvent 中被完全消费，不会到达这里
                eventCounter++
                val timestamp = timeFormat.format(Date())
                val record = buildString {
                    append("#$eventCounter [$timestamp]\n")
                    append("  类型: 手势导航 / 无障碍服务返回\n")
                    append("  判定: 无 KeyEvent 产生\n")
                    append("  说明: 手势返回走 OnBackPressedCallback\n")
                    append("        无障碍 GLOBAL_ACTION_BACK 同理")
                }

                tvVerdict.text = "手势/无障碍返回 (无 KeyEvent)"
                tvVerdict.setTextColor(getColor(R.color.success))
                tvEventDetails.text = "未产生 KeyEvent\n无法被 dispatchKeyEvent 捕获\n来源: 手势导航 或 AccessibilityService.GLOBAL_ACTION_BACK"
                addHistory(record)

                // 放行 — 让手势返回正常工作，页面正常退出
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })
    }

    /**
     * 拦截 KeyEvent 进行分析
     * 只处理 KEYCODE_BACK，其他按键原样放行
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            analyzeKeyEvent(event)

            // DOWN 事件时记录信息，UP 事件时做最终判定
            if (event.action == KeyEvent.ACTION_DOWN) {
                lastDownTime = System.currentTimeMillis()
                lastDownEventTime = event.eventTime
                lastDownSource = event.source
                lastDownDeviceId = event.deviceId
                lastDownScanCode = event.scanCode
                lastDownFlags = event.flags
                // 消费 DOWN 事件（不触发返回），等 UP 事件再放行
                return true
            }

            if (event.action == KeyEvent.ACTION_UP) {
                // 直接消费 UP 事件，不触发返回，这样页面不会退出
                // 用户可以留在当前页面查看检测结果
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * 分析 KeyEvent 的各项参数，判定是否为注入事件
     */
    private fun analyzeKeyEvent(event: KeyEvent) {
        // 只在 DOWN 事件时做完整分析（避免 DOWN/UP 重复输出）
        if (event.action != KeyEvent.ACTION_DOWN) return

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

        // ===== 各维度评分 =====
        var injectedScore = 0
        var realScore = 0
        val reasons = mutableListOf<String>()

        // 1. source 检测
        val sourceName = sourceToString(source)
        when {
            // input keyevent 4 产生的事件 source = SOURCE_KEYBOARD (0x101 = 257)
            source == InputDevice.SOURCE_KEYBOARD -> {
                // SOURCE_KEYBOARD 既可能是注入也可能是物理键盘，需要结合其他维度
                reasons.add("source=$sourceName($source) → 可能注入或物理键盘")
            }
            source == 0 -> {
                injectedScore += 2
                reasons.add("source=0 → 高度疑似注入(无输入设备)")
            }
            else -> {
                realScore++
                reasons.add("source=$sourceName($source) → 疑似真实设备")
            }
        }

        // 2. deviceId 检测
        when {
            deviceId == 0 -> {
                // deviceId=0 是虚拟键盘，input keyevent 注入时 deviceId 通常为 0 或 -1
                injectedScore++
                reasons.add("deviceId=$deviceId → 疑似注入(虚拟设备)")
            }
            deviceId == -1 -> {
                injectedScore += 2
                reasons.add("deviceId=$deviceId → 高度疑似注入(VIRTUAL_KEYBOARD)")
            }
            else -> {
                realScore++
                reasons.add("deviceId=$deviceId → 疑似真实(物理设备ID)")
            }
        }

        // 3. scanCode 检测
        when {
            scanCode == 0 -> {
                injectedScore++
                reasons.add("scanCode=$scanCode → 疑似注入(无扫描码)")
            }
            else -> {
                realScore++
                reasons.add("scanCode=$scanCode → 疑似真实(有物理扫描码)")
            }
        }

        // 4. pressDuration (eventTime - downTime) 检测
        when {
            pressDuration == 0L -> {
                injectedScore++
                reasons.add("pressDuration=${pressDuration}ms → 疑似注入(DOWN/UP同时)")
            }
            pressDuration in 1..30 -> {
                // 极短按压也可疑
                reasons.add("pressDuration=${pressDuration}ms → 可疑(极短按压)")
            }
            pressDuration in 31..2000 -> {
                realScore++
                reasons.add("pressDuration=${pressDuration}ms → 疑似真实(正常按压)")
            }
            else -> {
                reasons.add("pressDuration=${pressDuration}ms → 异常(过长)")
            }
        }

        // 5. flags 检测
        val flagDetails = flagsToString(flags)
        if (flags and KeyEvent.FLAG_FROM_SYSTEM != 0) {
            reasons.add("flags=$flagDetails → FLAG_FROM_SYSTEM (系统来源)")
        }
        if (flags and KeyEvent.FLAG_VIRTUAL_HARD_KEY != 0) {
            realScore++
            reasons.add("flags=$flagDetails → FLAG_VIRTUAL_HARD_KEY (虚拟导航栏按键)")
        }
        if (flags == 0) {
            reasons.add("flags=0 → 无特殊标记")
        }

        // 6. repeatCount 检测
        if (repeatCount > 0) {
            reasons.add("repeatCount=$repeatCount → 长按重复事件")
        }

        // ===== 综合判定 =====
        val verdict = when {
            injectedScore >= 3 -> "input keyevent 4 注入"
            injectedScore >= 2 && realScore == 0 -> "高度疑似 keyevent 注入"
            realScore >= 3 -> "真实物理/导航栏按键"
            realScore >= 2 && injectedScore == 0 -> "疑似真实按键"
            else -> "无法明确判断 (注入:$injectedScore 真实:$realScore)"
        }

        val verdictColor = when {
            injectedScore >= 2 && realScore <= 1 -> getColor(R.color.error)
            realScore >= 2 && injectedScore <= 1 -> getColor(R.color.success)
            else -> getColor(R.color.warning)
        }

        // ===== 更新 UI =====
        tvVerdict.text = verdict
        tvVerdict.setTextColor(verdictColor)

        val detailText = buildString {
            append("=== KeyEvent BACK 详细参数 ===\n")
            append("action       : ${if (event.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP"}\n")
            append("source       : $sourceName ($source / 0x${Integer.toHexString(source)})\n")
            append("deviceId     : $deviceId\n")
            append("scanCode     : $scanCode\n")
            append("flags        : $flagDetails ($flags / 0x${Integer.toHexString(flags)})\n")
            append("downTime     : $downTime\n")
            append("eventTime    : $eventTime\n")
            append("pressDuration: ${pressDuration}ms\n")
            append("repeatCount  : $repeatCount\n")
            append("\n=== 评分 ===\n")
            append("注入得分: $injectedScore\n")
            append("真实得分: $realScore\n")
            append("\n=== 分析细节 ===\n")
            reasons.forEachIndexed { i, r -> append("${i + 1}. $r\n") }
        }
        tvEventDetails.text = detailText

        // 构建历史记录
        val record = buildString {
            append("#$eventCounter [$timestamp] $verdict\n")
            append("  src=$sourceName dev=$deviceId scan=$scanCode\n")
            append("  press=${pressDuration}ms flags=$flagDetails")
        }
        addHistory(record)
    }

    private fun addHistory(record: String) {
        historyRecords.add(0, record) // 最新的在最前面
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
}
