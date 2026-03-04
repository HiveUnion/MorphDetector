package com.example.testauto

import android.os.Bundle
import android.view.MotionEvent
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * MotionEvent 触摸检测页
 * 实时检测 pressure、size、touchMajor、touchMinor、orientation 区分注入与真实手指
 * 每次触摸事件立即更新
 *
 * 注入特征：pressure=1.0(固定)、size=0、touchMajor/Minor=0、orientation=0
 * 真实手指：pressure=0.1–0.9、size=0.1–0.3、touchMajor/Minor=40–100px、orientation 有旋转
 */
class MotionEventDetectionActivity : AppCompatActivity() {

    private lateinit var tvDetectionResult: TextView
    private lateinit var tvEventDetails: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_motion_event_detection)

        tvDetectionResult = findViewById(R.id.tvDetectionResult)
        tvEventDetails = findViewById(R.id.tvEventDetails)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.pointerCount > 0) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_UP -> {
                    val (result, details) = analyzeMotionEvent(
                        pressure = ev.getPressure(0),
                        size = ev.getSize(0),
                        touchMajor = ev.getTouchMajor(0),
                        touchMinor = ev.getTouchMinor(0),
                        orientation = ev.getOrientation(0)
                    )
                    tvDetectionResult.text = result
                    tvEventDetails.text = details
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * 分析 MotionEvent 参数，判断疑似注入还是真实手指
     */
    private fun analyzeMotionEvent(
        pressure: Float,
        size: Float,
        touchMajor: Float,
        touchMinor: Float,
        orientation: Float
    ): Pair<String, String> {
        var injectedScore = 0
        var realScore = 0
        val reasons = mutableListOf<String>()

        // 1. pressure: 注入=1.0(固定) | 真实=0.1–0.9
        when {
            pressure >= 0.99f && pressure <= 1.01f -> {
                injectedScore++
                reasons.add("pressure=$pressure → 疑似注入(固定1.0)")
            }
            pressure in 0.1f..0.9f -> {
                realScore++
                reasons.add("pressure=$pressure → 疑似真实(0.1-0.9)")
            }
            else -> reasons.add("pressure=$pressure → 待定")
        }

        // 2. size: 注入=0 | 真实=0.1–0.3
        when {
            size <= 0.01f -> {
                injectedScore++
                reasons.add("size=$size → 疑似注入(为0)")
            }
            size in 0.1f..0.3f -> {
                realScore++
                reasons.add("size=$size → 疑似真实(0.1-0.3)")
            }
            else -> reasons.add("size=$size → 待定")
        }

        // 3. touchMajor/touchMinor: 注入=0 | 真实=40–100px
        val touchSize = maxOf(touchMajor, touchMinor)
        when {
            touchSize <= 1f -> {
                injectedScore++
                reasons.add("touchMajor=$touchMajor, touchMinor=$touchMinor → 疑似注入(为0)")
            }
            touchSize in 40f..100f -> {
                realScore++
                reasons.add("touchMajor=$touchMajor, touchMinor=$touchMinor → 疑似真实(40-100px)")
            }
            touchSize > 100f -> {
                realScore++
                reasons.add("touchMajor=$touchMajor, touchMinor=$touchMinor → 疑似真实(>100px)")
            }
            else -> reasons.add("touchMajor=$touchMajor, touchMinor=$touchMinor → 待定")
        }

        // 4. orientation: 注入=0 | 真实=有旋转角度
        val absOrientation = kotlin.math.abs(orientation)
        when {
            absOrientation <= 0.01f -> {
                injectedScore++
                reasons.add("orientation=$orientation → 疑似注入(无旋转)")
            }
            absOrientation > 0.01f -> {
                realScore++
                reasons.add("orientation=$orientation → 疑似真实(有旋转)")
            }
            else -> reasons.add("orientation=$orientation → 待定")
        }

        val result = when {
            injectedScore >= 3 && realScore <= 1 -> "⚠️ 疑似注入 (模拟触摸)"
            realScore >= 3 && injectedScore <= 1 -> "✅ 疑似真实手指"
            else -> "❓ 无法明确判断"
        }

        val details = reasons.joinToString("\n")
        return Pair(result, details)
    }
}
