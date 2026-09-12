package com.tabletgamepadbridge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * Custom Canvas view rendering an interactive Xbox-style controller matching
 * the blue electric aesthetic and custom Mars/Lightning pattern in the user's design.
 */
class GamepadVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var state = GamepadState()

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gripPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val patternPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#0d53ba")
        strokeWidth = 4f
    }
    private val patternFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#0d53ba")
    }
    private val buttonBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val activeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val stickWellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#344c5f")
    }
    private val stickCapPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dpadPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun updateState(newState: GamepadState) {
        this.state = newState
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val cx = w / 2f
        val cy = h / 2f + 10f
        val scale = (w / 700f).coerceAtMost(h / 450f)

        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(scale, scale)

        drawControllerBody(canvas)
        drawFaceplatePatterns(canvas)
        drawGlossOverlay(canvas)
        drawBumpersAndTriggers(canvas)
        drawGuideAndViewMenu(canvas)
        drawDPad(canvas)
        drawABXYButtons(canvas)
        drawThumbsticks(canvas)

        canvas.restore()
    }

    private fun drawControllerBody(canvas: Canvas) {
        val bodyPath = Path().apply {
            moveTo(-260f, -110f)
            cubicTo(-180f, -150f, 180f, -150f, 260f, -110f)
            cubicTo(310f, -80f, 330f, 20f, 310f, 120f)
            cubicTo(290f, 200f, 220f, 220f, 170f, 210f)
            cubicTo(120f, 200f, 80f, 80f, 0f, 80f)
            cubicTo(-80f, 80f, -120f, 200f, -170f, 210f)
            cubicTo(-220f, 220f, -290f, 200f, -310f, 120f)
            cubicTo(-330f, 20f, -310f, -80f, -260f, -110f)
            close()
        }

        val bodyGradient = LinearGradient(
            0f, -150f, 0f, 220f,
            Color.parseColor("#1d80fd"), Color.parseColor("#0a46ab"),
            Shader.TileMode.CLAMP
        )
        bodyPaint.shader = bodyGradient
        canvas.drawPath(bodyPath, bodyPaint)

        // White lower trim
        val trimPath = Path().apply {
            moveTo(-280f, 120f)
            cubicTo(-240f, 190f, -180f, 225f, -140f, 190f)
            cubicTo(-90f, 150f, -60f, 65f, 0f, 65f)
            cubicTo(60f, 65f, 90f, 150f, 140f, 190f)
            cubicTo(180f, 225f, 240f, 190f, 280f, 120f)
            cubicTo(250f, 195f, 170f, 240f, 130f, 195f)
            cubicTo(80f, 140f, 50f, 80f, 0f, 80f)
            cubicTo(-50f, 80f, -80f, 140f, -130f, 195f)
            cubicTo(-170f, 240f, -250f, 195f, -280f, 120f)
            close()
        }
        gripPaint.color = Color.WHITE
        canvas.drawPath(trimPath, gripPaint)
    }

    private fun drawFaceplatePatterns(canvas: Canvas) {
        // Large Mars symbol on bottom left grip
        drawMarsSymbol(canvas, -210f, 100f, 52f, 225f, 8f)

        // Large Mars symbol on right grip
        drawMarsSymbol(canvas, 215f, 85f, 48f, 45f, 7f)

        // Medium Mars symbol (top left)
        drawMarsSymbol(canvas, -235f, -65f, 20f, 45f, 4f)

        // Medium Mars symbol (below D-pad)
        drawMarsSymbol(canvas, -35f, 80f, 18f, 225f, 3.5f)

        // Small Mars symbol (near right menu button)
        drawMarsSymbol(canvas, 235f, -65f, 15f, 45f, 3f)

        // Lightning bolt symbol between D-pad and Home button
        drawLightningBolt(canvas, -32f, -15f, 18f)

        // Hollow decorative circles
        patternPaint.strokeWidth = 3f
        canvas.drawCircle(-205f, -25f, 16f, patternPaint)
        canvas.drawCircle(-125f, -75f, 18f, patternPaint)
        canvas.drawCircle(-120f, 20f, 11f, patternPaint)
        canvas.drawCircle(10f, -85f, 13f, patternPaint)
        canvas.drawCircle(115f, -75f, 15f, patternPaint)
        canvas.drawCircle(155f, 70f, 16f, patternPaint)
        canvas.drawCircle(115f, 130f, 13f, patternPaint)
    }

    private fun drawMarsSymbol(
        canvas: Canvas, cx: Float, cy: Float, radius: Float, angleDeg: Float, strokeW: Float
    ) {
        patternPaint.strokeWidth = strokeW
        canvas.drawCircle(cx, cy, radius, patternPaint)

        val rad = Math.toRadians(angleDeg.toDouble())
        val startX = cx + radius * cos(rad).toFloat()
        val startY = cy + radius * sin(rad).toFloat()
        val lineLen = radius * 0.9f
        val endX = startX + lineLen * cos(rad).toFloat()
        val endY = startY + lineLen * sin(rad).toFloat()

        canvas.drawLine(startX, startY, endX, endY, patternPaint)

        // Arrowhead lines
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
        canvas.drawPath(boltPath, patternFillPaint)
    }

    private fun drawGlossOverlay(canvas: Canvas) {
        // Glossy reflection arc on top-left quadrant
        val glossPath = Path().apply {
            moveTo(-270f, -80f)
            cubicTo(-210f, -140f, -50f, -150f, 0f, -145f)
            cubicTo(-100f, -120f, -180f, -80f, -260f, -20f)
            close()
        }
        val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#28ffffff")
            style = Paint.Style.FILL
        }
        canvas.drawPath(glossPath, glossPaint)
    }

    private fun drawBumpersAndTriggers(canvas: Canvas) {
        val ltActive = state.leftTrigger > 500
        val lbActive = state.leftBumper
        val rtActive = state.rightTrigger > 500
        val rbActive = state.rightBumper

        val topLeftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (lbActive || ltActive) Color.parseColor("#00e6ff") else Color.parseColor("#154d80")
        }
        canvas.drawRoundRect(RectF(-230f, -155f, -90f, -130f), 10f, 10f, topLeftPaint)

        val topRightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (rbActive || rtActive) Color.parseColor("#00e6ff") else Color.parseColor("#154d80")
        }
        canvas.drawRoundRect(RectF(90f, -155f, 230f, -130f), 10f, 10f, topRightPaint)
    }

    private fun drawGuideAndViewMenu(canvas: Canvas) {
        val guideActive = state.guide

        // Guide Button
        val guideX = 0f
        val guideY = -70f

        if (guideActive) {
            val glow = RadialGradient(guideX, guideY, 45f, Color.parseColor("#a0e0ff"), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            activeGlowPaint.shader = glow
            canvas.drawCircle(guideX, guideY, 45f, activeGlowPaint)
        }

        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (guideActive) Color.parseColor("#00e5ff") else Color.parseColor("#ffffff")
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
        canvas.drawCircle(guideX, guideY, 28f, ringPaint)

        val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
        }
        canvas.drawCircle(guideX, guideY, 18f, centerPaint)

        // Draw Home Icon inside Guide
        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0b429d")
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        val homePath = Path().apply {
            moveTo(guideX - 8f, guideY + 2f)
            lineTo(guideX, guideY - 6f)
            lineTo(guideX + 8f, guideY + 2f)
            lineTo(guideX + 5f, guideY + 2f)
            lineTo(guideX + 5f, guideY + 8f)
            lineTo(guideX - 5f, guideY + 8f)
            lineTo(guideX - 5f, guideY + 2f)
            close()
        }
        canvas.drawPath(homePath, iconPaint)

        // View (Back) & Menu (Start)
        val backActive = state.back
        val startActive = state.start

        val viewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (backActive) Color.parseColor("#00e5ff") else Color.parseColor("#2a5f8a")
        }
        canvas.drawCircle(-55f, -60f, 14f, viewPaint)

        val menuPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (startActive) Color.parseColor("#00e5ff") else Color.parseColor("#2a5f8a")
        }
        canvas.drawCircle(55f, -60f, 14f, menuPaint)
    }

    private fun drawDPad(canvas: Canvas) {
        val dX = -80f
        val dY = 35f
        val armSize = 22f
        val armLen = 50f

        val up = state.dpadUp
        val down = state.dpadDown
        val left = state.dpadLeft
        val right = state.dpadRight

        fun armColor(active: Boolean) = if (active) Color.parseColor("#00e5ff") else Color.parseColor("#ffffff")

        // Up
        dpadPaint.color = armColor(up)
        canvas.drawRoundRect(RectF(dX - armSize/2, dY - armLen, dX + armSize/2, dY), 6f, 6f, dpadPaint)
        // Down
        dpadPaint.color = armColor(down)
        canvas.drawRoundRect(RectF(dX - armSize/2, dY, dX + armSize/2, dY + armLen), 6f, 6f, dpadPaint)
        // Left
        dpadPaint.color = armColor(left)
        canvas.drawRoundRect(RectF(dX - armLen, dY - armSize/2, dX, dY + armSize/2), 6f, 6f, dpadPaint)
        // Right
        dpadPaint.color = armColor(right)
        canvas.drawRoundRect(RectF(dX, dY - armSize/2, dX + armLen, dY + armSize/2), 6f, 6f, dpadPaint)
    }

    private fun drawABXYButtons(canvas: Canvas) {
        val bX = 175f
        val bY = -35f
        val radius = 22f
        val spacing = 38f

        // Y (Top - Yellow)
        drawSingleButton(canvas, bX, bY - spacing, radius, "Y", Color.parseColor("#e6b800"), Color.parseColor("#24210f"), state.y)
        // X (Left - Blue)
        drawSingleButton(canvas, bX - spacing, bY, radius, "X", Color.parseColor("#2a82fa"), Color.parseColor("#121f33"), state.x)
        // B (Right - Red)
        drawSingleButton(canvas, bX + spacing, bY, radius, "B", Color.parseColor("#e63939"), Color.parseColor("#291212"), state.b)
        // A (Bottom - Green)
        drawSingleButton(canvas, bX, bY + spacing, radius, "A", Color.parseColor("#33cd56"), Color.parseColor("#122618"), state.a)
    }

    private fun drawSingleButton(
        canvas: Canvas, x: Float, y: Float, r: Float, label: String,
        activeColor: Int, bgColor: Int, isPressed: Boolean
    ) {
        if (isPressed) {
            val glow = RadialGradient(x, y, r * 1.8f, activeColor, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            activeGlowPaint.shader = glow
            canvas.drawCircle(x, y, r * 1.8f, activeGlowPaint)
            buttonBgPaint.color = activeColor
            textPaint.color = Color.BLACK
        } else {
            buttonBgPaint.color = bgColor
            textPaint.color = activeColor
        }

        buttonBgPaint.style = Paint.Style.FILL
        canvas.drawCircle(x, y, r, buttonBgPaint)

        buttonBgPaint.style = Paint.Style.STROKE
        buttonBgPaint.strokeWidth = 3f
        buttonBgPaint.color = activeColor
        canvas.drawCircle(x, y, r, buttonBgPaint)

        textPaint.textSize = r * 1.1f
        val fontMetrics = textPaint.fontMetrics
        val textY = y - (fontMetrics.ascent + fontMetrics.descent) / 2f
        canvas.drawText(label, x, textY, textPaint)
    }

    private fun drawThumbsticks(canvas: Canvas) {
        // Left Stick (Top Left)
        val lWellX = -175f
        val lWellY = -35f
        val stickRadius = 38f
        val maxOffset = 18f

        canvas.drawCircle(lWellX, lWellY, stickRadius, stickWellPaint)

        val lOffsetX = (state.leftStickX / 32767f) * maxOffset
        val lOffsetY = (state.leftStickY / 32767f) * maxOffset

        val lCapX = lWellX + lOffsetX
        val lCapY = lWellY + lOffsetY

        stickCapPaint.color = if (state.leftStickClick) Color.parseColor("#00e5ff") else Color.parseColor("#4f6d85")
        canvas.drawCircle(lCapX, lCapY, stickRadius - 6f, stickCapPaint)

        // Right Stick (Bottom Right)
        val rWellX = 80f
        val rWellY = 35f

        canvas.drawCircle(rWellX, rWellY, stickRadius, stickWellPaint)

        val rOffsetX = (state.rightStickX / 32767f) * maxOffset
        val rOffsetY = (state.rightStickY / 32767f) * maxOffset

        val rCapX = rWellX + rOffsetX
        val rCapY = rWellY + rOffsetY

        stickCapPaint.color = if (state.rightStickClick) Color.parseColor("#00e5ff") else Color.parseColor("#4f6d85")
        canvas.drawCircle(rCapX, rCapY, stickRadius - 6f, stickCapPaint)
    }
}
