package com.example.testauto

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 自动化监测页：监测输入框的输入来源、文本变化，并在检测到异常时标记并展示关键日志。
 * 对应风险：P1-1 剪贴板高频、P1-2 performAction 降级(setText/paste 等)、P2-1 keyevent 来源等。
 */
class AutomationMonitorActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AutomationMonitor"
        /** 剪贴板变化时间窗口（ms），窗口内超过 1 次视为高频，对应 P1-1 */
        private const val CLIP_CHANGE_WINDOW_MS = 500L
        /** 文本变化记录保留条数 */
        private const val MAX_TEXT_CHANGES = 15
        /** 异常日志保留条数 */
        private const val MAX_ANOMALY_LOGS = 30
        /** 判定为「粘贴」的剪贴板变化时间窗口（ms） */
        private const val PASTE_CLIP_WINDOW_MS = 300L
    }

    private lateinit var editMonitorInput: MonitorableEditText
    private lateinit var tvInputSource: TextView
    private lateinit var tvTextChanges: TextView
    private lateinit var tvAnomalyLog: TextView
    private lateinit var cardAnomalyLog: MaterialCardView

    /** 最近一次输入的来源描述 */
    private var lastInputSourceText: String = "暂无输入记录"

    /** 剪贴板变化时间戳队列，用于检测高频操作（P1-1） */
    private val clipChangeTimestamps = CopyOnWriteArrayList<Long>()

    /** 最近一次剪贴板变化时间，用于判定「粘贴」来源 */
    private var lastClipChangeTime: Long = 0L

    /** 文本变化记录（时间 + 描述） */
    private val textChangeEntries = mutableListOf<String>()

    /** 异常日志条目 */
    private val anomalyEntries = mutableListOf<String>()

    /** 当前一次文本变化是否来自程序 setText（在 onProgrammaticSetText 里置 true，TextWatcher 里消费后置 false） */
    private var nextChangeIsProgrammatic: Boolean = false

    /**
     * 当前一次文本变化是否来自虚拟键盘注入（input text / input keyevent）。
     * 在 onVirtualKeyboardEvent 回调里置 true，afterTextChanged 消费后置 false。
     * 优先级：programmatic > virtualKeyboard > paste > IME
     */
    private var nextChangeIsVirtualKeyboard: Boolean = false

    /** 上一帧文本内容，用于在 afterTextChanged 中计算 old -> new */
    private var lastKnownText: String = ""

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_automation_monitor)

        editMonitorInput = findViewById(R.id.editMonitorInput)
        tvInputSource = findViewById(R.id.tvInputSource)
        tvTextChanges = findViewById(R.id.tvTextChanges)
        tvAnomalyLog = findViewById(R.id.tvAnomalyLog)
        cardAnomalyLog = findViewById(R.id.cardAnomalyLog)

        setupClipboardListener()
        setupEditTextMonitoring()
        setupClearLogsButton()
    }

    /** 清除日志按钮：清空输入的来源、文本的变化、异常日志的展示与数据 */
    private fun setupClearLogsButton() {
        findViewById<Button>(R.id.btnClearLogs).setOnClickListener { clearAllLogs() }
    }

    /** 清除所有日志（输入的来源、文本的变化、异常日志） */
    private fun clearAllLogs() {
        lastInputSourceText = "暂无输入记录"
        synchronized(textChangeEntries) { textChangeEntries.clear() }
        synchronized(anomalyEntries) { anomalyEntries.clear() }
        tvInputSource.text = lastInputSourceText
        tvTextChanges.text = "暂无变化记录"
        tvAnomalyLog.text = "暂无异常"
        tvAnomalyLog.setTextColor(resources.getColor(R.color.on_surface_variant, theme))
        cardAnomalyLog.strokeColor = resources.getColor(R.color.divider, theme)
        cardAnomalyLog.strokeWidth = 1
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.removePrimaryClipChangedListener(clipboardListener)
            } catch (e: Exception) {
                Log.w(TAG, "移除剪贴板监听失败", e)
            }
        }
        super.onDestroy()
    }

    /** 注册剪贴板监听，用于检测 P1-1 剪贴板高频操作 */
    private fun setupClipboardListener() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.addPrimaryClipChangedListener(clipboardListener)
    }

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        val now = System.currentTimeMillis()
        lastClipChangeTime = now
        clipChangeTimestamps.add(now)
        // 移除窗口外的
        while (clipChangeTimestamps.isNotEmpty() && clipChangeTimestamps[0] < now - CLIP_CHANGE_WINDOW_MS) {
            clipChangeTimestamps.removeAt(0)
        }
        // 窗口内 >= 2 次视为高频
        if (clipChangeTimestamps.size >= 2) {
            addAnomaly(
                "P1-1 剪贴板高频操作",
                "在 ${CLIP_CHANGE_WINDOW_MS}ms 内剪贴板变化 ${clipChangeTimestamps.size} 次，疑似自动化逐字输入（typeCharByChar）"
            )
        }
    }

    /** 设置输入框监测：来源 + 文本变化 */
    private fun setupEditTextMonitoring() {
        // P1-2：程序 setText 直接设置文本
        editMonitorInput.onProgrammaticSetText = { text ->
            nextChangeIsProgrammatic = true
            val time = timeFormat.format(Date())
            lastInputSourceText = "程序设置 (setText) — $time\n内容长度: ${text?.length ?: 0}"
            runOnUiThread { tvInputSource.text = lastInputSourceText }
            addAnomaly(
                "P1-2 performAction 降级 (setText)",
                "检测到通过 setText 直接设置文本，未经过 IME。" +
                    "若 app 监控 TextWatcher/InputConnection 可发现非键盘输入。时间: $time"
            )
        }

        // P2-1：虚拟键盘注入（input text / input keyevent，deviceId=-1）
        editMonitorInput.onVirtualKeyboardEvent = { event ->
            nextChangeIsVirtualKeyboard = true
            val time = timeFormat.format(Date())
            val keyName = KeyEvent.keyCodeToString(event.keyCode)
            lastInputSourceText = "⚠️ 虚拟键盘注入 — $time\ndeviceId=${event.deviceId}  keyCode=$keyName(${event.keyCode})"
            runOnUiThread { tvInputSource.text = lastInputSourceText }
            addAnomaly(
                "P2-1 虚拟键盘注入 (input text / input keyevent)",
                "KeyEvent.deviceId=${event.deviceId}（VIRTUAL_KEYBOARD=-1），" +
                    "keyCode=$keyName(${event.keyCode})，source=0x${event.source.toString(16)}。" +
                    "真实物理键盘 deviceId >= 0，IME 输入走 commitText 不产生此事件。时间: $time"
            )
        }

        // 正常 IME 路径：commitText，用于更新来源显示（不触发异常）
        editMonitorInput.onImeCommitText = { text ->
            val time = timeFormat.format(Date())
            lastInputSourceText = "IME 提交 (commitText) — $time\n提交内容: \"$text\""
            runOnUiThread { tvInputSource.text = lastInputSourceText }
        }

        editMonitorInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val newText = s?.toString() ?: ""
                val time = timeFormat.format(Date())

                // 来源判定优先级：programmatic > virtualKeyboard > paste > IME/键盘
                val source = when {
                    nextChangeIsProgrammatic -> {
                        nextChangeIsProgrammatic = false
                        "程序设置"
                    }
                    nextChangeIsVirtualKeyboard -> {
                        nextChangeIsVirtualKeyboard = false
                        "⚠️ 虚拟键盘注入"
                    }
                    else -> {
                        val clipText = getClipboardText()
                        val recentlyChangedClip = System.currentTimeMillis() - lastClipChangeTime < PASTE_CLIP_WINDOW_MS
                        val fromPaste = recentlyChangedClip && clipText != null && (
                            clipText == newText || (newText.endsWith(clipText) && newText.length >= clipText.length)
                        )
                        if (fromPaste) "粘贴" else "IME/键盘"
                    }
                }

                runOnUiThread { tvInputSource.text = lastInputSourceText }
                addTextChangeEntry(time, source, lastKnownText, newText)
                lastKnownText = newText
            }
        })
    }

    private fun getClipboardText(): String? {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).text?.toString()
    }

    private fun addTextChangeEntry(time: String, source: String, oldPart: String, newPart: String) {
        val oldPreview = if (oldPart.length > 20) oldPart.take(20) + "…" else oldPart
        val newPreview = if (newPart.length > 20) newPart.take(20) + "…" else newPart
        val line = "[$time] $source | \"$oldPreview\" → \"$newPreview\""
        synchronized(textChangeEntries) {
            textChangeEntries.add(0, line)
            while (textChangeEntries.size > MAX_TEXT_CHANGES) textChangeEntries.removeAt(textChangeEntries.size - 1)
        }
        runOnUiThread { refreshTextChangesDisplay() }
    }

    private fun addAnomaly(title: String, detail: String) {
        val time = timeFormat.format(Date())
        val line = "【异常】$title\n  $detail\n  时间: $time"
        synchronized(anomalyEntries) {
            anomalyEntries.add(0, line)
            while (anomalyEntries.size > MAX_ANOMALY_LOGS) anomalyEntries.removeAt(anomalyEntries.size - 1)
        }
        Log.w(TAG, line)
        runOnUiThread {
            refreshAnomalyDisplay()
        }
    }

    private fun refreshTextChangesDisplay() {
        val text = if (textChangeEntries.isEmpty()) "暂无变化记录" else textChangeEntries.joinToString("\n")
        tvTextChanges.text = text
    }

    private fun refreshAnomalyDisplay() {
        val hasAnomaly = anomalyEntries.isNotEmpty()
        val text = if (hasAnomaly) anomalyEntries.joinToString("\n\n") else "暂无异常"
        tvAnomalyLog.text = text
        tvAnomalyLog.setTextColor(
            if (hasAnomaly) resources.getColor(R.color.error, theme)
            else resources.getColor(R.color.on_surface_variant, theme)
        )
        // 有异常时卡片描边高亮
        cardAnomalyLog.strokeColor = if (hasAnomaly) resources.getColor(R.color.error, theme) else resources.getColor(R.color.divider, theme)
        cardAnomalyLog.strokeWidth = if (hasAnomaly) 4 else 1
    }
}
