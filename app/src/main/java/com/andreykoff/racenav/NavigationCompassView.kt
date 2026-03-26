package com.andreykoff.racenav

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Navigation compass widget that points towards the next waypoint.
 * Drawn entirely with Canvas — no bitmaps — for crisp rendering at any size.
 */
class NavigationCompassView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Public properties ────────────────────────────────────────────

    /** Arrow direction: 0 = straight ahead, clockwise degrees. */
    var relativeBearing: Float = 0f
        set(value) { field = value; invalidate() }

    /** Device heading for rotating the cardinal ring so N points geographic north. */
    var mapBearing: Float = 0f
        set(value) { field = value; invalidate() }

    /** Distance string shown below center (e.g. "1.2 km"). */
    var distanceText: String = ""
        set(value) { field = value; invalidate() }

    /** When false the arrow and ring are grayed out. */
    var isNavActive: Boolean = false
        set(value) { field = value; invalidate() }

    /** Overall transparency of the compass (0..1). */
    var compassAlpha: Float = 0.7f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }

    // ── Pre-allocated drawing objects ────────────────────────────────

    private val paintBgFill    = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintRing      = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintTick      = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintNTriangle = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintArrow     = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintArrowOutline = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintCenter    = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintText      = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintCardinal  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val boldTypeface   = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

    private val arrowPath = Path()
    private val nTriPath  = Path()

    private companion object {
        const val COLOR_ORANGE   = 0xFFFF8F00.toInt()  // brighter orange
        const val COLOR_GRAY     = 0xFF666666.toInt()
        const val COLOR_BG       = 0xBF111111.toInt()  // #111111 at 75% alpha
        const val COLOR_RED      = 0xFFF44336.toInt()
        const val COLOR_BLUE     = 0xFF2196F3.toInt()
        const val COLOR_TICK     = 0xFFFFFFFF.toInt()  // white ticks
        const val COLOR_WHITE    = 0xFFFFFFFF.toInt()
        const val COLOR_GREEN    = 0xFF4CAF50.toInt()
        const val LERP_SPEED     = 30f   // degrees per frame
    }

    /** Currently displayed arrow angle — lerped toward [relativeBearing]. */
    private var displayedAngle: Float = 0f

    /** Current ring color — animated during flash. */
    private var currentRingColor: Int = COLOR_ORANGE

    /** Flash animator reference for cleanup. */
    private var flashAnimator: ValueAnimator? = null

    override fun onDetachedFromWindow() {
        flashAnimator?.cancel()
        flashAnimator = null
        super.onDetachedFromWindow()
    }

    // ── Measure: force square ────────────────────────────────────────

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val h = MeasureSpec.getSize(heightSpec)
        val size = w.coerceAtMost(h)
        setMeasuredDimension(size, size)
    }

    // ── Flash animation on waypoint reached ──────────────────────────

    /**
     * Briefly flashes the ring border bright green for 600ms, then fades back
     * to the normal orange accent color.
     */
    fun flashOnWaypointReached() {
        flashAnimator?.cancel()
        val animator = ValueAnimator.ofObject(ArgbEvaluator(), COLOR_GREEN, COLOR_ORANGE)
        animator.duration = 600L
        animator.addUpdateListener { anim ->
            currentRingColor = anim.animatedValue as Int
            invalidate()
        }
        animator.start()
        flashAnimator = animator
    }

    // ── Draw ─────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(cx, cy) * 0.9f

        // Apply overall transparency
        val alphaInt = (compassAlpha * 255).toInt()

        val accentColor = if (isNavActive) COLOR_ORANGE else COLOR_WHITE
        val ringColor = if (isNavActive && flashAnimator?.isRunning == true) currentRingColor else accentColor

        // ─ 1. Background circle ─────────────────────────────────────
        paintBgFill.color = COLOR_BG
        paintBgFill.style = Paint.Style.FILL
        paintBgFill.alpha = alphaInt
        canvas.drawCircle(cx, cy, radius, paintBgFill)

        // Ring border (thicker: ~2.5dp)
        val ringWidth = radius * 0.04f
        paintRing.color = ringColor
        paintRing.style = Paint.Style.STROKE
        paintRing.strokeWidth = ringWidth
        paintRing.alpha = alphaInt
        canvas.drawCircle(cx, cy, radius - ringWidth / 2f, paintRing)

        // ─ 2. Cardinal ticks + N/S letters (rotate with mapBearing) ──
        canvas.save()
        canvas.rotate(-mapBearing, cx, cy)

        paintTick.strokeCap = Paint.Cap.ROUND

        for (i in 0 until 12) {
            val angleDeg = i * 30.0
            val angleRad = Math.toRadians(angleDeg - 90.0) // 0° = top

            val isCardinal = i % 3 == 0           // 0,90,180,270
            val isMajor    = isCardinal
            val innerR: Float
            val outerR: Float
            if (isMajor) {
                innerR = radius * 0.78f
                outerR = radius * 0.92f
                paintTick.strokeWidth = radius * 0.04f
                paintTick.color = COLOR_TICK
            } else {
                innerR = radius * 0.84f
                outerR = radius * 0.92f
                paintTick.strokeWidth = radius * 0.02f
                paintTick.color = COLOR_TICK
            }
            paintTick.alpha = alphaInt

            val cosA = cos(angleRad).toFloat()
            val sinA = sin(angleRad).toFloat()
            canvas.drawLine(
                cx + innerR * cosA, cy + innerR * sinA,
                cx + outerR * cosA, cy + outerR * sinA,
                paintTick
            )
        }

        // Small red triangle for N (at top of ring)
        val triSize = radius * 0.08f
        val triCenter = cy - radius * 0.95f
        nTriPath.reset()
        nTriPath.moveTo(cx, triCenter - triSize)         // tip
        nTriPath.lineTo(cx - triSize * 0.7f, triCenter + triSize * 0.5f)
        nTriPath.lineTo(cx + triSize * 0.7f, triCenter + triSize * 0.5f)
        nTriPath.close()
        paintNTriangle.color = COLOR_BLUE
        paintNTriangle.style = Paint.Style.FILL
        paintNTriangle.alpha = alphaInt
        canvas.drawPath(nTriPath, paintNTriangle)

        // "N" letter at north (blue, bold)
        paintCardinal.color = COLOR_BLUE
        paintCardinal.textSize = radius * 0.18f
        paintCardinal.textAlign = Paint.Align.CENTER
        paintCardinal.typeface = boldTypeface
        paintCardinal.alpha = alphaInt
        canvas.drawText("N", cx, cy - radius * 0.60f, paintCardinal)

        // "S" letter at south (red, bold)
        paintCardinal.color = COLOR_RED
        canvas.drawText("S", cx, cy + radius * 0.72f, paintCardinal)

        // "E" letter at east (white)
        paintCardinal.color = COLOR_WHITE
        paintCardinal.textSize = radius * 0.14f
        canvas.drawText("E", cx + radius * 0.66f, cy + radius * 0.06f, paintCardinal)

        // "W" letter at west (white)
        canvas.drawText("W", cx - radius * 0.66f, cy + radius * 0.06f, paintCardinal)

        canvas.restore()

        // ─ 3. Smooth arrow animation ────────────────────────────────
        val target = relativeBearing
        val delta  = ((target - displayedAngle + 540f) % 360f) - 180f
        if (abs(delta) > 0.5f) {
            displayedAngle += delta.coerceIn(-LERP_SPEED, LERP_SPEED)
            displayedAngle = ((displayedAngle % 360f) + 360f) % 360f
            postInvalidateOnAnimation()
        } else {
            displayedAngle = target
        }

        // ─ 4. Main arrow (chevron / triangle) ───────────────────────
        canvas.save()
        canvas.rotate(displayedAngle, cx, cy)

        val arrowLen = radius * 0.50f        // bigger: 50% of radius
        val arrowHalfW = radius * 0.20f
        val notchDepth = arrowLen * 0.30f

        arrowPath.reset()
        arrowPath.moveTo(cx, cy - arrowLen)                        // tip
        arrowPath.lineTo(cx + arrowHalfW, cy + arrowLen * 0.25f)  // right
        arrowPath.lineTo(cx, cy + arrowLen * 0.25f - notchDepth)  // notch
        arrowPath.lineTo(cx - arrowHalfW, cy + arrowLen * 0.25f)  // left
        arrowPath.close()

        // White outline/shadow for contrast on any background
        paintArrowOutline.color = COLOR_WHITE
        paintArrowOutline.style = Paint.Style.STROKE
        paintArrowOutline.strokeWidth = radius * 0.025f
        paintArrowOutline.strokeJoin = Paint.Join.ROUND
        paintArrowOutline.alpha = alphaInt
        canvas.drawPath(arrowPath, paintArrowOutline)

        // Fill
        paintArrow.color = accentColor
        paintArrow.style = Paint.Style.FILL
        paintArrow.alpha = alphaInt
        canvas.drawPath(arrowPath, paintArrow)

        canvas.restore()

        // ─ 5. Center dot ────────────────────────────────────────────
        paintCenter.color = COLOR_WHITE
        paintCenter.style = Paint.Style.FILL
        paintCenter.alpha = alphaInt
        canvas.drawCircle(cx, cy, radius * 0.045f, paintCenter)

        // ─ 6. Distance text ─────────────────────────────────────────
        if (distanceText.isNotEmpty()) {
            paintText.color = COLOR_WHITE
            paintText.textSize = radius * 0.22f
            paintText.textAlign = Paint.Align.CENTER
            paintText.typeface = boldTypeface
            paintText.alpha = alphaInt
            canvas.drawText(distanceText, cx, cy + radius * 0.35f, paintText)
        }
    }
}
