package com.example.testauto

import android.content.Context
import android.util.AttributeSet
import android.view.DragEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.appcompat.widget.AppCompatEditText

/**
 * 可监测粘贴行为的 EditText，覆盖所有粘贴路径：
 *
 * 1. onTextContextMenuItem(android.R.id.paste) — 长按菜单「粘贴」
 * 2. dispatchKeyEvent(KEYCODE_PASTE)           — input keyevent 279 注入
 * 3. dispatchKeyEvent(KEYCODE_V + Ctrl)        — 物理键盘 Ctrl+V 或注入
 * 4. InputConnection.commitText                — 输入法提交（非粘贴，但记录用于对比）
 * 5. performAction(ACTION_PASTE)               — 无障碍服务粘贴，最终走 onTextContextMenuItem
 */
class PasteMonitorEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    /**
     * 长按菜单粘贴回调
     * @param menuItemId 菜单项 ID (android.R.id.paste / android.R.id.pasteAsPlainText)
     * @param clipboardText 当前剪贴板内容
     */
    var onContextMenuPaste: ((menuItemId: Int, clipboardText: String?) -> Unit)? = null

    /**
     * KeyEvent 粘贴回调 (KEYCODE_PASTE 或 Ctrl+V)
     * @param event 原始 KeyEvent，包含所有参数
     */
    var onKeyEventPaste: ((event: KeyEvent) -> Unit)? = null

    /**
     * InputConnection.commitText 回调（IME正常输入，用于对比）
     */
    var onImeCommitText: ((text: CharSequence?) -> Unit)? = null

    /** IME sendKeyEvent 路径标记，避免误报 */
    private var isInImeSendKeyEvent = false

    /**
     * 拦截长按菜单的粘贴操作
     * 这是 TextView 内部处理粘贴的入口，覆盖了：
     * - 长按菜单「粘贴」
     * - 长按菜单「以纯文本粘贴」
     * - performAction(ACTION_PASTE) 最终也走到这里
     */
    override fun onTextContextMenuItem(id: Int): Boolean {
        if (id == android.R.id.paste || id == android.R.id.pasteAsPlainText) {
            val clipText = getClipboardText()
            onContextMenuPaste?.invoke(id, clipText)
        }
        return super.onTextContextMenuItem(id)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && !isInImeSendKeyEvent) {
            if (event.keyCode == KeyEvent.KEYCODE_PASTE ||
                (event.keyCode == KeyEvent.KEYCODE_V && event.isCtrlPressed)) {
                onKeyEventPaste?.invoke(event)
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        return PasteMonitoringInputConnection(base, true)
    }

    private fun getClipboardText(): String? {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            cm?.primaryClip?.getItemAt(0)?.text?.toString()
        } catch (e: Exception) {
            null
        }
    }

    private inner class PasteMonitoringInputConnection(
        base: InputConnection,
        mutable: Boolean
    ) : InputConnectionWrapper(base, mutable) {

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            onImeCommitText?.invoke(text)
            return super.commitText(text, newCursorPosition)
        }

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
