package com.ouor.pocketboard.core.gesture.android

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.ouor.pocketboard.core.gesture.TouchAction
import com.ouor.pocketboard.core.gesture.TouchSample

/**
 * 입력 표면(A5). 그리지 않는다(검은 화면). MotionEvent 를 [TouchSample] 목록으로 펼쳐 [listener]에 넘긴다.
 * :app 이 AndroidView 로 전체 화면에 넣고 리스너에서 GestureRecognizer.onTouchAll 을 부른다.
 */
class TouchSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    fun interface Listener {
        fun onSamples(samples: List<TouchSample>)
    }

    var listener: Listener? = null

    // 판정은 GestureRecognizer 가 한다. 접근성 클릭은 쓰기 화면 범위 밖(A9).
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        listener?.onSamples(MotionEventMapper.toSamples(event, resources.displayMetrics.density))
        return true
    }
}

/** MotionEvent → TouchSample(px→dp). 포인터는 ID 로, ACTION_MOVE 는 getHistorical* 샘플까지 모두. */
object MotionEventMapper {
    fun toSamples(e: MotionEvent, density: Float): List<TouchSample> {
        val out = ArrayList<TouchSample>()
        fun add(i: Int, action: TouchAction, x: Float, y: Float, t: Long, major: Float, canceled: Boolean = false) {
            out += TouchSample(e.getPointerId(i), action, x / density, y / density, t, major / density, canceled)
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                add(i, TouchAction.DOWN, e.getX(i), e.getY(i), e.eventTime, e.getTouchMajor(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (h in 0 until e.historySize) {
                    for (i in 0 until e.pointerCount) {
                        add(i, TouchAction.MOVE, e.getHistoricalX(i, h), e.getHistoricalY(i, h),
                            e.getHistoricalEventTime(h), e.getHistoricalTouchMajor(i, h))
                    }
                }
                for (i in 0 until e.pointerCount) {
                    add(i, TouchAction.MOVE, e.getX(i), e.getY(i), e.eventTime, e.getTouchMajor(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                // API 33+: 시스템이 이 포인터를 손바닥으로 판정해 취소함.
                val canceled = Build.VERSION.SDK_INT >= 33 && (e.flags and MotionEvent.FLAG_CANCELED) != 0
                add(i, TouchAction.UP, e.getX(i), e.getY(i), e.eventTime, e.getTouchMajor(i), canceled)
            }
            MotionEvent.ACTION_CANCEL -> {
                for (i in 0 until e.pointerCount) {
                    add(i, TouchAction.CANCEL, e.getX(i), e.getY(i), e.eventTime, e.getTouchMajor(i), true)
                }
            }
        }
        return out
    }
}
