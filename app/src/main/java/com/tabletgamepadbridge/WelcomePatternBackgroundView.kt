package com.tabletgamepadbridge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renders the electric blue background with scattered Mars ♂ symbols,
 * lightning bolts ⚡, and circular accents matching the controller faceplate.
 */
class WelcomePatternBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val patternPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#15428F")
        strokeWidth = 3f
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#15428F")
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val gradient = LinearGradient(
            0f, 0f, 0f, h,
            Color.parseColor("#0A2C66"), Color.parseColor("#091E47"),
            Shader.TileMode.CLAMP
        )
        bgPaint.shader = gradient
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // Draw scattered Mars symbols across background
        drawMarsSymbol(canvas, w * 0.15f, h * 0.15f, 40f, 45f)
        drawMarsSymbol(canvas, w * 0.85f, h * 0.20f, 50f, 225f)
        drawMarsSymbol(canvas, w * 0.10f, h * 0.80f, 65f, 225f)
        drawMarsSymbol(canvas, w * 0.88f, h * 0.75f, 55f, 45f)
        drawMarsSymbol(canvas, w * 0.50f, h * 0.90f, 35f, 135f)

        // Lightning bolts
        drawLightningBolt(canvas, w * 0.25f, h * 0.45f, 24f)
        drawLightningBolt(canvas, w * 0.75f, h * 0.55f, 28f)

        // Circles
        canvas.drawCircle(w * 0.30f, h * 0.12f, 18f, patternPaint)
        canvas.drawCircle(w * 0.70f, h * 0.18f, 22f, patternPaint)
        canvas.drawCircle(w * 0.20f, h * 0.65f, 28f, patternPaint)
        canvas.drawCircle(w * 0.80f, h * 0.85f, 20f, patternPaint)
    }

    private fun drawMarsSymbol(
        canvas: Canvas, cx: Float, cy: Float, radius: Float, angleDeg: Float
    ) {
        patternPaint.strokeWidth = radius * 0.12f
        canvas.drawCircle(cx, cy, radius, patternPaint)

        val rad = Math.toRadians(angleDeg.toDouble())
        val startX = cx + radius * cos(rad).toFloat()
        val startY = cy + radius * sin(rad).toFloat()
        val lineLen = radius * 0.9f
        val endX = startX + lineLen * cos(rad).toFloat()
        val endY = startY + lineLen * sin(rad).toFloat()

        canvas.drawLine(startX, startY, endX, endY, patternPaint)

        val headLen = radius * 0.45f
        val headAngle1 = rad + Math.toRadians(140.0)
        val headAngle2 = rad - Math.toRadians(140.0)

        val head1X = endX + headLen * cos(headAngle1).toFloat()
        val head1Y = endY + headLen * sin(headAngle1).toFloat()
        val head2X = endX + headLen * cos(headAngle2).toFloat()
        val head2Y = endY + headLen * sin(headAngle2).toFloat()

        canvas.drawLine(endX, endY, head1X, head1Y, patternPaint)
        canvas.drawLine(endX, endY, head2X, head2Y, patternPaint)
    }

    private fun drawLightningBolt(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val boltPath = Path().apply {
            moveTo(cx + size * 0.2f, cy - size * 0.8f)
            lineTo(cx - size * 0.4f, cy + size * 0.05f)
            lineTo(cx - size * 0.05f, cy + size * 0.05f)
            lineTo(cx - size * 0.3f, cy + size * 0.8f)
            lineTo(cx + size * 0.4f, cy - size * 0.05f)
            lineTo(cx + size * 0.05f, cy - size * 0.05f)
            close()
        }
        canvas.drawPath(boltPath, fillPaint)
    }
}
