package com.example.testauto

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatEditText

/**
 * 可监测的 EditText。
 * 当通过代码调用 setText 时会被记录，用于区分「程序直接设置」与「IME/粘贴」等来源。
 */
class MonitorableEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    /**
     * 当通过 setText(CharSequence, BufferType) 被程序设置文本时回调。
     * 用于标记输入来源为「程序设置」，对应自动化 fallback 如 performAction(ACTION_SET_TEXT)。
     */
    var onProgrammaticSetText: ((CharSequence?) -> Unit)? = null

    override fun setText(text: CharSequence?, type: BufferType?) {
        // 先设置文本，再回调，便于外部读取 getText()
        super.setText(text, type)
        onProgrammaticSetText?.invoke(text)
    }
}
