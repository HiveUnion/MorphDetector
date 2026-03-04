package com.example.testauto

import android.content.Context
import android.util.AttributeSet
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.appcompat.widget.AppCompatEditText

/**
 * 可监测的 EditText，覆盖三条输入路径：
 * 1. setText()          — 程序直接设置（对应 performAction(ACTION_SET_TEXT) 降级）
 * 2. dispatchKeyEvent() — 系统输入派发链上的 KeyEvent，deviceId=-1 代表虚拟键盘注入
 *                         （input text / input keyevent 均走此路径）
 * 3. InputConnection    — IME 通过 commitText 提交文本；sendKeyEvent 发送特殊按键
 *
 * 区分要点：InputConnection.sendKeyEvent 最终也会调回 dispatchKeyEvent，
 * 用 isInImeSendKeyEvent 标志隔离，避免把 IME 的退格/回车误报为注入。
 */
class MonitorableEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    /** 程序调用 setText() 时回调，对应 performAction(ACTION_SET_TEXT) 降级 */
    var onProgrammaticSetText: ((CharSequence?) -> Unit)? = null

    /**
     * 检测到 deviceId = KeyCharacterMap.VIRTUAL_KEYBOARD (-1) 的注入 KeyEvent 时回调。
     * 触发条件：不在 IME sendKeyEvent 路径内 + ACTION_DOWN + deviceId=-1
     * 对应：adb input text / adb input keyevent 命令注入
     */
    var onVirtualKeyboardEvent: ((KeyEvent) -> Unit)? = null

    /**
     * IME 通过 InputConnection.commitText 正常提交文本时回调。
     * 对应：搜狗/讯飞/Gboard 等输入法正常输入，不属于异常。
     */
    var onImeCommitText: ((CharSequence?) -> Unit)? = null

    /**
     * 当前是否处于 InputConnection.sendKeyEvent() 的调用栈内。
     * IME 的 sendKeyEvent 会间接触发 dispatchKeyEvent，
     * 用此标志防止误报，主线程单线程操作无竞态风险。
     */
    private var isInImeSendKeyEvent = false

    override fun setText(text: CharSequence?, type: BufferType?) {
        super.setText(text, type)
        onProgrammaticSetText?.invoke(text)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 只在 ACTION_DOWN 触发一次，且排除 IME sendKeyEvent 路径
        if (!isInImeSendKeyEvent
            && event.action == KeyEvent.ACTION_DOWN
            && event.deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD
        ) {
            onVirtualKeyboardEvent?.invoke(event)
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        return MonitoringInputConnection(base, true)
    }

    private inner class MonitoringInputConnection(
        base: InputConnection,
        mutable: Boolean
    ) : InputConnectionWrapper(base, mutable) {

        /** IME 正常输入路径（中文/候选词/整词提交） */
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            onImeCommitText?.invoke(text)
            return super.commitText(text, newCursorPosition)
        }

        /**
         * IME 特殊按键路径（退格、回车等）。
         * 设置 isInImeSendKeyEvent 标志，让 dispatchKeyEvent 跳过对这批事件的检测。
         */
        override fun sendKeyEvent(event: KeyEvent?): Boolean {
            isInImeSendKeyEvent = true
            return try {
                super.sendKeyEvent(event)
            } finally {
                isInImeSendKeyEvent = false
            }
        }
    }
}
