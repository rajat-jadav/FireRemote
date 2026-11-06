package com.rajat.fireremote.phone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Touchpad gestures:
 *  - one finger drag     -> move pointer (relative, with speed-based acceleration)
 *  - quick tap           -> click
 *  - hold without moving -> long press
 *  - two-finger drag     -> swipe / scroll (sent when the fingers lift)
 *  - two-finger tap      -> back
 */
class TouchpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Callback {
        fun onMove(dx: Int, dy: Int)
        fun onTap()
        fun onLongPress()
        fun onSwipe(dxPermille: Int, dyPermille: Int)
        fun onTwoFingerTap()
    }

    var callback: Callback? = null

    /** Multiplier applied to finger movement. Set from the Speed slider. */
    var sensitivity = 1.8f

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressMs = 550L
    private val tapMaxMs = 350L

    private var downTime = 0L
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var longFired = false

    private var twoFinger = false
    private var twoStartTime = 0L
    private var twoStartX = 0f
    private var twoStartY = 0f
    private var twoLastX = 0f
    private var twoLastY = 0f

    /** After a two-finger gesture ends, ignore the remaining finger until it lifts. */
    private var ignoreUntilUp = false

    private var accX = 0f
    private var accY = 0f
    private var lastSend = 0L

    private val longPressRunnable = Runnable {
        if (!moved && !twoFinger && !ignoreUntilUp) {
            longFired = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            callback?.onLongPress()
        }
    }

    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics
        )
    }
    private val hints = arrayOf(
        "Touchpad",
        "Tap: click    Hold: long press",
        "Two fingers: scroll    Two-finger tap: back"
    )

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val lineHeight = hintPaint.textSize * 1.6f
        var y = height / 2f - lineHeight * (hints.size - 1) / 2f
        for (text in hints) {
            canvas.drawText(text, width / 2f, y, hintPaint)
            y += lineHeight
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downTime = e.eventTime
                startX = e.x
                startY = e.y
                lastX = e.x
                lastY = e.y
                moved = false
                longFired = false
                twoFinger = false
                ignoreUntilUp = false
                accX = 0f
                accY = 0f
                postDelayed(longPressRunnable, longPressMs)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount == 2 && !ignoreUntilUp) {
                    removeCallbacks(longPressRunnable)
                    twoFinger = true
                    twoStartTime = e.eventTime
                    twoStartX = (e.getX(0) + e.getX(1)) / 2f
                    twoStartY = (e.getY(0) + e.getY(1)) / 2f
                    twoLastX = twoStartX
                    twoLastY = twoStartY
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (twoFinger && e.pointerCount >= 2) {
                    twoLastX = (e.getX(0) + e.getX(1)) / 2f
                    twoLastY = (e.getY(0) + e.getY(1)) / 2f
                } else if (!twoFinger && !ignoreUntilUp) {
                    var dx = e.x - lastX
                    var dy = e.y - lastY
                    lastX = e.x
                    lastY = e.y
                    if (!moved) {
                        if (hypot(e.x - startX, e.y - startY) <= slop) return true
                        moved = true
                        removeCallbacks(longPressRunnable)
                        // Include the distance covered while we were still deciding.
                        dx = e.x - startX
                        dy = e.y - startY
                    }
                    if (!longFired) pushMove(dx, dy, e.eventTime)
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (twoFinger && e.pointerCount == 2) {
                    finishTwoFinger(e.eventTime)
                    twoFinger = false
                    ignoreUntilUp = true
                }
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                if (!ignoreUntilUp && !twoFinger) {
                    if (!moved && !longFired && e.eventTime - downTime < tapMaxMs) {
                        performClick()
                        callback?.onTap()
                    } else {
                        flush(e.eventTime, force = true)
                    }
                }
                twoFinger = false
                ignoreUntilUp = false
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                twoFinger = false
                ignoreUntilUp = false
            }
        }
        return true
    }

    private fun pushMove(dx: Float, dy: Float, time: Long) {
        // Faster finger movement travels further: gain 1.0 .. 2.5 on top of the speed setting.
        val speed = hypot(dx, dy)
        val gain = sensitivity * (1f + min(speed / 30f, 1.5f))
        accX += dx * gain
        accY += dy * gain
        flush(time, force = false)
    }

    private fun flush(time: Long, force: Boolean) {
        if (!force && time - lastSend < 12) return
        val ix = accX.toInt()
        val iy = accY.toInt()
        if (ix == 0 && iy == 0) return
        accX -= ix
        accY -= iy
        lastSend = time
        callback?.onMove(ix, iy)
    }

    private fun finishTwoFinger(time: Long) {
        val dx = twoLastX - twoStartX
        val dy = twoLastY - twoStartY
        val distance = hypot(dx, dy)
        if (distance < slop * 2) {
            if (time - twoStartTime < tapMaxMs) callback?.onTwoFingerTap()
            return
        }
        if (width == 0 || height == 0) return
        // Express the movement as a fraction of the pad, so it maps to the same fraction of the TV screen.
        val px = (dx / width * 1000f).toInt()
        val py = (dy / height * 1000f).toInt()
        if (abs(px) > 0 || abs(py) > 0) callback?.onSwipe(px, py)
    }
}
