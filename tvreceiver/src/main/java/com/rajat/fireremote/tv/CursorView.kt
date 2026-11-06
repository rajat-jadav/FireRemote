package com.rajat.fireremote.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** A simple white arrow with a black outline. The tip is at the top-left corner. */
class CursorView(context: Context) : View(context) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeJoin = Paint.Join.ROUND
    }
    private val arrow = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = width.toFloat() - 6f
        val o = 2f
        arrow.reset()
        arrow.moveTo(o, o)
        arrow.lineTo(o, o + s * 0.80f)
        arrow.lineTo(o + s * 0.20f, o + s * 0.62f)
        arrow.lineTo(o + s * 0.34f, o + s * 0.95f)
        arrow.lineTo(o + s * 0.46f, o + s * 0.90f)
        arrow.lineTo(o + s * 0.32f, o + s * 0.58f)
        arrow.lineTo(o + s * 0.58f, o + s * 0.58f)
        arrow.close()
        canvas.drawPath(arrow, fill)
        canvas.drawPath(arrow, outline)
    }
}
