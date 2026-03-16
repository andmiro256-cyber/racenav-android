package com.andreykoff.racenav

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

class CompassView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Mode { FREE, NORTH, COURSE }
    
    var mode: Mode = Mode.FREE
        set(value) { field = value; invalidate() }

    private val paintRing = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintTick = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintSymbol = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintN = Paint(Paint.ANTI_ALIAS_FLAG)
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(cx, cy) * 0.9f
        
        // Colors based on mode
        val ringColor = when (mode) {
            Mode.FREE -> Color.parseColor("#666666")       // gray
            Mode.NORTH -> Color.parseColor("#1565C0")      // blue
            Mode.COURSE -> Color.parseColor("#2E7D32")     // green
        }
        val fillColor = when (mode) {
            Mode.FREE -> Color.parseColor("#222222")
            Mode.NORTH -> Color.parseColor("#0D3B66")
            Mode.COURSE -> Color.parseColor("#1B4332")
        }
        
        // Background circle (dark fill)
        paintFill.color = fillColor
        paintFill.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, radius, paintFill)
        
        // Ring (colored border)
        paintRing.color = ringColor
        paintRing.style = Paint.Style.STROKE
        paintRing.strokeWidth = radius * 0.12f
        canvas.drawCircle(cx, cy, radius - paintRing.strokeWidth / 2, paintRing)
        
        // Tick marks (N, E, S, W = long ticks, 4 intermediate = short)
        paintTick.color = Color.parseColor("#888888")
        paintTick.strokeWidth = radius * 0.04f
        paintTick.strokeCap = Paint.Cap.ROUND
        for (i in 0 until 8) {
            val angle = Math.toRadians((i * 45.0) - 90)  // 0=N (top)
            val isCardinal = i % 2 == 0
            val innerR = radius * (if (isCardinal) 0.70f else 0.80f)
            val outerR = radius * 0.88f
            // N tick is red
            if (i == 0) {
                paintTick.color = Color.parseColor("#FF3333")
                paintTick.strokeWidth = radius * 0.06f
            } else {
                paintTick.color = Color.parseColor("#888888")
                paintTick.strokeWidth = radius * 0.04f
            }
            canvas.drawLine(
                cx + (innerR * cos(angle)).toFloat(),
                cy + (innerR * sin(angle)).toFloat(),
                cx + (outerR * cos(angle)).toFloat(),
                cy + (outerR * sin(angle)).toFloat(),
                paintTick
            )
        }
        
        // Red "N" marker on the ring at top
        paintN.color = Color.parseColor("#FF3333")
        paintN.textSize = radius * 0.28f
        paintN.textAlign = Paint.Align.CENTER
        paintN.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("N", cx, cy - radius * 0.48f, paintN)
        
        // Center symbol based on mode
        paintSymbol.textAlign = Paint.Align.CENTER
        paintSymbol.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        
        when (mode) {
            Mode.FREE -> {
                // Circle/dot
                paintSymbol.color = Color.WHITE
                paintSymbol.style = Paint.Style.STROKE
                paintSymbol.strokeWidth = radius * 0.06f
                canvas.drawCircle(cx, cy, radius * 0.18f, paintSymbol)
                paintSymbol.style = Paint.Style.FILL
                canvas.drawCircle(cx, cy, radius * 0.06f, paintSymbol)
            }
            Mode.NORTH -> {
                // "N" letter
                paintSymbol.color = Color.parseColor("#64B5F6")  // light blue
                paintSymbol.textSize = radius * 0.7f
                paintSymbol.style = Paint.Style.FILL
                canvas.drawText("N", cx, cy + radius * 0.25f, paintSymbol)
            }
            Mode.COURSE -> {
                // Arrow pointing up
                paintSymbol.color = Color.parseColor("#81C784")  // light green
                paintSymbol.style = Paint.Style.FILL
                val arrowPath = Path().apply {
                    moveTo(cx, cy - radius * 0.35f)  // tip
                    lineTo(cx + radius * 0.22f, cy + radius * 0.25f)  // right
                    lineTo(cx, cy + radius * 0.12f)  // notch
                    lineTo(cx - radius * 0.22f, cy + radius * 0.25f)  // left
                    close()
                }
                canvas.drawPath(arrowPath, paintSymbol)
            }
        }
    }
    
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val size = MeasureSpec.getSize(widthSpec).coerceAtMost(MeasureSpec.getSize(heightSpec))
        setMeasuredDimension(size, size)
    }
}
