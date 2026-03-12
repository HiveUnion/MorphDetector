package com.example.testauto

import android.os.Bundle
import android.view.InputDevice
import android.view.MotionEvent
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MotionEvent 触摸检测页
 *
 * 检测维度（完整）：
 * 1. deviceId     — 注入=-1(VIRTUAL_KEYBOARD)或0, 真实=物理触摸设备ID(>0)
 * 2. source       — 注入/真实都是 SOURCE_TOUCHSCREEN, 但配合 deviceId 判断
 * 3. pressure     — 注入=0.0或1.0(固定), 真实=0.1–0.9(浮动)
 * 4. size         — 注入=0, 真实=0.05–0.3
 * 5. touchMajor   — 注入=0, 真实=40–100px
 * 6. touchMinor   — 注入=0, 真实=30–80px
 * 7. orientation  — 注入=0, 真实=有旋转角度
 * 8. toolType     — 注入=FINGER或UNKNOWN, 配合 deviceId 判断
 * 9. eventTime-downTime — DOWN事件两者相同, UP事件差值=按压时长
 *
 * input motionevent DOWN/UP 注入特征：
 * - deviceId = -1 (最可靠的判断依据)
 * - pressure = 0.0 或 1.0
 * - size/touchMajor/touchMinor = 0
 * - orientation = 0
 */
class MotionEventDetectionActivity : AppCompatActivity() {

    private lateinit var tvDetectionResult: TextView
    private lateinit var tvEventDetails: TextView
    private lateinit var tvHistory: TextView
    private lateinit var btnClearHistory: Button

    private val historyRecords = mutableListOf<String>()
    private var eventCounter = 0

    // 记录 DOWN 事件时间，用于计算按压时长
    private var lastDownEventTime = 0L
    private var lastDownSystemTime = 0L

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_motion_event_detection)

        tvDetectionResult = findViewById(R.id.tvDetectionResult)
        tvEventDetails = findViewById(R.id.tvEventDetails)
        tvHistory = findViewById(R.id.tvHistory)
        btnClearHistory = findViewById(R.id.btnClearHistory)

        btnClearHistory.setOnClickListener {
            historyRecords.clear()
            eventCounter = 0
            tvHistory.text = "暂无记录"
            tvDetectionResult.text = "等待触摸..."
            tvEventDetails.text = ""
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.pointerCount > 0) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastDownEventTime = ev.eventTime
                    lastDownSystemTime = System.currentTimeMillis()
                    analyzeMotionEvent(ev, "DOWN")
                }
                MotionEvent.ACTION_UP -> {
                    analyzeMotionEvent(ev, "UP")
                }
                // MOVE 事件太频繁，不记录历史，只更新实时显示
                MotionEvent.ACTION_MOVE -> {
                    updateRealtimeDisplay(ev)
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun updateRealtimeDisplay(ev: MotionEvent) {
        val details = buildEventDetails(ev, "MOVE", null)
        tvEventDetails.text = details
    }

    private fun analyzeMotionEvent(ev: MotionEvent, action: String) {
        eventCounter++
        val timestamp = timeFormat.format(Date())

        val deviceId = ev.deviceId
        val source = ev.source
        val pressure = ev.getPressure(0)
        val size = ev.getSize(0)
        val touchMajor = ev.getTouchMajor(0)
        val touchMinor = ev.getTouchMinor(0)
        val orientation = ev.getOrientation(0)
        val toolType = ev.getToolType(0)
        val downTime = ev.downTime
        val eventTime = ev.eventTime
        val pressDuration = eventTime - downTime
        val x = ev.getX(0)
        val y = ev.getY(0)

        // ===== 各维度评分 =====
        var injectedScore = 0
        var realScore = 0
        val reasons = mutableListOf<String>()

        // 1. deviceId (最关键)
        when {
            deviceId == -1 -> {
                injectedScore += 3
                reasons.add("deviceId=$deviceId -> 注入(VIRTUAL_KEYBOARD)")
            }
            deviceId == 0 -> {
                injectedScore += 2
                reasons.add("deviceId=$deviceId -> 疑似注入(虚拟设备)")
            }
            deviceId > 0 -> {
                realScore += 2
                reasons.add("deviceId=$deviceId -> 真实(物理触摸设备)")
            }
        }

        // 2. source
        val sourceName = sourceToString(source)
        when {
            source and InputDevice.SOURCE_TOUCHSCREEN != 0 -> {
                // 注入和真实都是 TOUCHSCREEN，需要配合 deviceId
                reasons.add("source=$sourceName(0x${Integer.toHexString(source)}) -> TOUCHSCREEN")
            }
            else -> {
                injectedScore++
                reasons.add("source=$sourceName(0x${Integer.toHexString(source)}) -> 非TOUCHSCREEN，异常")
            }
        }

        // 3. pressure
        when {
            pressure >= 0.99f && pressure <= 1.01f -> {
                injectedScore++
                reasons.add("pressure=${"%.4f".format(pressure)} -> 疑似注入(固定1.0)")
            }
            pressure <= 0.01f -> {
                injectedScore++
                reasons.add("pressure=${"%.4f".format(pressure)} -> 疑似注入(为0)")
            }
            pressure in 0.05f..0.95f -> {
                realScore++
                reasons.add("pressure=${"%.4f".format(pressure)} -> 疑似真实(浮动值)")
            }
            else -> reasons.add("pressure=${"%.4f".format(pressure)} -> 待定")
        }

        // 4. size
        when {
            size <= 0.001f -> {
                injectedScore++
                reasons.add("size=${"%.4f".format(size)} -> 疑似注入(为0)")
            }
            size in 0.01f..0.5f -> {
                realScore++
                reasons.add("size=${"%.4f".format(size)} -> 疑似真实")
            }
            else -> reasons.add("size=${"%.4f".format(size)} -> 待定")
        }

        // 5. touchMajor/touchMinor
        val touchSize = maxOf(touchMajor, touchMinor)
        when {
            touchSize <= 1f -> {
                injectedScore++
                reasons.add("touchMajor=${"%.1f".format(touchMajor)} touchMinor=${"%.1f".format(touchMinor)} -> 疑似注入(为0)")
            }
            touchSize > 1f -> {
                realScore++
                reasons.add("touchMajor=${"%.1f".format(touchMajor)} touchMinor=${"%.1f".format(touchMinor)} -> 疑似真实")
            }
        }

        // 6. orientation
        val absOrientation = kotlin.math.abs(orientation)
        when {
            absOrientation <= 0.01f -> {
                injectedScore++
                reasons.add("orientation=${"%.4f".format(orientation)} -> 疑似注入(无旋转)")
            }
            absOrientation > 0.01f -> {
                realScore++
                reasons.add("orientation=${"%.4f".format(orientation)} -> 疑似真实(有旋转)")
            }
        }

        // 7. toolType
        val toolTypeName = toolTypeToString(toolType)
        when (toolType) {
            MotionEvent.TOOL_TYPE_FINGER -> {
                // 注入和真实都可能是 FINGER
                reasons.add("toolType=$toolTypeName($toolType) -> FINGER")
            }
            MotionEvent.TOOL_TYPE_UNKNOWN -> {
                injectedScore++
                reasons.add("toolType=$toolTypeName($toolType) -> 疑似注入(UNKNOWN)")
            }
            else -> {
                reasons.add("toolType=$toolTypeName($toolType)")
            }
        }

        // 8. pressDuration (仅 UP 事件有意义)
        if (action == "UP") {
            when {
                pressDuration == 0L -> {
                    injectedScore++
                    reasons.add("pressDuration=${pressDuration}ms -> 疑似注入(DOWN/UP同时)")
                }
                pressDuration in 1..20 -> {
                    reasons.add("pressDuration=${pressDuration}ms -> 可疑(极短)")
                }
                pressDuration in 21..3000 -> {
                    realScore++
                    reasons.add("pressDuration=${pressDuration}ms -> 正常按压时长")
                }
                else -> {
                    reasons.add("pressDuration=${pressDuration}ms -> 长按")
                }
            }
        }

        // ===== 综合判定 =====
        val verdict = when {
            injectedScore >= 4 -> "input motionevent 注入"
            injectedScore >= 3 && realScore <= 1 -> "高度疑似注入"
            realScore >= 4 -> "真实手指触摸"
            realScore >= 3 && injectedScore <= 1 -> "疑似真实手指"
            else -> "无法明确判断 (注入:$injectedScore 真实:$realScore)"
        }

        val verdictColor = when {
            injectedScore >= 3 && realScore <= 1 -> getColor(R.color.error)
            realScore >= 3 && injectedScore <= 1 -> getColor(R.color.success)
            else -> getColor(R.color.warning)
        }

        // ===== 更新 UI =====
        tvDetectionResult.text = verdict
        tvDetectionResult.setTextColor(verdictColor)

        val details = buildEventDetails(ev, action, reasons)
        tvEventDetails.text = details

        // 添加历史记录
        val record = buildString {
            append("#$eventCounter [$timestamp] [$action] $verdict\n")
            append("  dev=$deviceId pres=${"%.2f".format(pressure)} size=${"%.3f".format(size)}")
            append(" major=${"%.0f".format(touchMajor)} orient=${"%.3f".format(orientation)}")
            if (action == "UP") append(" dur=${pressDuration}ms")
            append("\n  pos=(${x.toInt()},${y.toInt()}) tool=$toolTypeName")
        }
        addHistory(record)
    }

    private fun buildEventDetails(ev: MotionEvent, action: String, reasons: List<String>?): String {
        val deviceId = ev.deviceId
        val source = ev.source
        val pressure = ev.getPressure(0)
        val size = ev.getSize(0)
        val touchMajor = ev.getTouchMajor(0)
        val touchMinor = ev.getTouchMinor(0)
        val orientation = ev.getOrientation(0)
        val toolType = ev.getToolType(0)
        val downTime = ev.downTime
        val eventTime = ev.eventTime
        val pressDuration = eventTime - downTime
        val x = ev.getX(0)
        val y = ev.getY(0)
        val rawX = ev.rawX
        val rawY = ev.rawY

        return buildString {
            append("=== MotionEvent $action 详细参数 ===\n")
            append("deviceId     : $deviceId\n")
            append("source       : ${sourceToString(source)} (0x${Integer.toHexString(source)})\n")
            append("toolType     : ${toolTypeToString(toolType)} ($toolType)\n")
            append("pressure     : ${"%.6f".format(pressure)}\n")
            append("size         : ${"%.6f".format(size)}\n")
            append("touchMajor   : ${"%.2f".format(touchMajor)}\n")
            append("touchMinor   : ${"%.2f".format(touchMinor)}\n")
            append("orientation  : ${"%.6f".format(orientation)}\n")
            append("x, y         : (${"%.1f".format(x)}, ${"%.1f".format(y)})\n")
            append("rawX, rawY   : (${"%.1f".format(rawX)}, ${"%.1f".format(rawY)})\n")
            append("downTime     : $downTime\n")
            append("eventTime    : $eventTime\n")
            append("pressDuration: ${pressDuration}ms\n")
            append("pointerCount : ${ev.pointerCount}\n")
            if (reasons != null) {
                append("\n=== 分析细节 ===\n")
                reasons.forEachIndexed { i, r -> append("${i + 1}. $r\n") }
            }
        }
    }

    private fun addHistory(record: String) {
        historyRecords.add(0, record)
        if (historyRecords.size > 30) {
            historyRecords.removeAt(historyRecords.size - 1)
        }
        tvHistory.text = historyRecords.joinToString("\n\n")
    }

    private fun sourceToString(source: Int): String {
        val names = mutableListOf<String>()
        if (source and InputDevice.SOURCE_TOUCHSCREEN != 0) names.add("TOUCHSCREEN")
        if (source and InputDevice.SOURCE_MOUSE != 0) names.add("MOUSE")
        if (source and InputDevice.SOURCE_STYLUS != 0) names.add("STYLUS")
        if (source and InputDevice.SOURCE_TRACKBALL != 0) names.add("TRACKBALL")
        if (source and InputDevice.SOURCE_TOUCHPAD != 0) names.add("TOUCHPAD")
        if (source and InputDevice.SOURCE_KEYBOARD != 0) names.add("KEYBOARD")
        if (source == 0) names.add("UNKNOWN(0)")
        return if (names.isEmpty()) "OTHER(0x${Integer.toHexString(source)})" else names.joinToString("|")
    }

    private fun toolTypeToString(toolType: Int): String {
        return when (toolType) {
            MotionEvent.TOOL_TYPE_UNKNOWN -> "UNKNOWN"
            MotionEvent.TOOL_TYPE_FINGER -> "FINGER"
            MotionEvent.TOOL_TYPE_STYLUS -> "STYLUS"
            MotionEvent.TOOL_TYPE_MOUSE -> "MOUSE"
            MotionEvent.TOOL_TYPE_ERASER -> "ERASER"
            else -> "OTHER($toolType)"
        }
    }
}
