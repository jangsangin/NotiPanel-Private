package com.leseraphim.notipanel

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
import kotlin.math.abs
import kotlin.math.roundToInt

/** ColorOS 설정 화면 색 */
class OsPalette(ctx: Context) {
    val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    val bg = if (night) Color.BLACK else Color.parseColor("#F0F1F5")
    val card = if (night) Color.parseColor("#1C1C1E") else Color.WHITE
    val text = if (night) Color.parseColor("#EBFFFFFF") else Color.parseColor("#E6000000")
    val sub = if (night) Color.parseColor("#8CFFFFFF") else Color.parseColor("#8C000000")
    val divider = if (night) Color.parseColor("#1FFFFFFF") else Color.parseColor("#12000000")
    val blue = Color.parseColor("#0A6CFF")
    val off = if (night) Color.parseColor("#3A3A3C") else Color.parseColor("#DCDDE1")
    val pressed = if (night) Color.parseColor("#14FFFFFF") else Color.parseColor("#0D000000")
}

private val ease = PathInterpolator(0.3f, 0f, 0.1f, 1f)

private fun mix(a: Int, b: Int, t: Float): Int {
    fun ch(s: Int) = ((a shr s and 0xFF) + ((b shr s and 0xFF) - (a shr s and 0xFF)) * t).roundToInt()
    return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/** ColorOS 스위치: 꺼짐 회색, 켜짐 파랑 + 흰 동그라미 */
class OsSwitch(ctx: Context, private val p: OsPalette) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(2f * d, 0f, 0.5f * d, Color.parseColor("#33000000"))
    }
    private val r = RectF()
    private var pos = 0f
    private var anim: ValueAnimator? = null
    var onChange: ((Boolean) -> Unit)? = null
    var isChecked = false
        private set

    init { setOnClickListener { toggle() } }

    fun toggle() {
        if (!isEnabled) return
        setChecked(!isChecked, true)
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        onChange?.invoke(isChecked)
    }

    fun setChecked(v: Boolean, animate: Boolean = false) {
        isChecked = v
        anim?.cancel()
        val to = if (v) 1f else 0f
        if (!animate) { pos = to; invalidate(); return }
        anim = ValueAnimator.ofFloat(pos, to).apply {
            duration = 220; interpolator = ease
            addUpdateListener { pos = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled); alpha = if (enabled) 1f else 0.35f
    }

    override fun onMeasure(w: Int, h: Int) =
        setMeasuredDimension((52 * d).toInt(), (30 * d).toInt())

    override fun onDraw(c: Canvas) {
        val h = height.toFloat(); val w = width.toFloat()
        val pad = 1f * d
        r.set(pad, pad, w - pad, h - pad)
        paint.color = mix(p.off, p.blue, pos)
        c.drawRoundRect(r, h / 2, h / 2, paint)
        val tr = h / 2 - 4f * d
        val cx = (pad + 3f * d + tr) + (w - 2 * (pad + 3f * d + tr)) * pos
        c.drawCircle(cx, h / 2, tr, thumb)
    }
}

/** ColorOS 슬라이드바: 두꺼운 파란 막대 + 끝에 흰 동그라미 */
class OsSlider(
    ctx: Context, private val p: OsPalette,
    private val from: Int, private val to: Int, start: Int
) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val r = RectF()
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f; private var downY = 0f
    private var dragging = false
    private var shown = 0f                // 그리는 위치(0~1), 탭하면 부드럽게 따라감
    private var anim: ValueAnimator? = null
    var onChange: ((Int) -> Unit)? = null
    var value = start.coerceIn(from, to)
        private set

    init { shown = frac(value) }

    private fun frac(v: Int) = if (to == from) 0f else (v - from).toFloat() / (to - from)
    private val trackH get() = 22f * d
    private val inset get() = 4f * d

    override fun onMeasure(w: Int, h: Int) =
        setMeasuredDimension(MeasureSpec.getSize(w), (40 * d).toInt())

    override fun onDraw(c: Canvas) {
        val th = trackH
        val top = (height - th) / 2
        r.set(0f, top, width.toFloat(), top + th)
        paint.color = p.off
        c.drawRoundRect(r, th / 2, th / 2, paint)
        val tr = th / 2 - inset
        val minX = inset + tr
        val maxX = width - inset - tr
        val cx = minX + (maxX - minX) * shown
        r.set(0f, top, cx + tr + inset, top + th)
        paint.color = if (isEnabled) p.blue else p.sub
        c.drawRoundRect(r, th / 2, th / 2, paint)
        c.drawCircle(cx, top + th / 2, tr, thumb)
    }

    private fun valueAt(x: Float): Int {
        val th = trackH; val tr = th / 2 - inset
        val minX = inset + tr; val maxX = width - inset - tr
        val f = ((x - minX) / (maxX - minX)).coerceIn(0f, 1f)
        return (from + f * (to - from)).roundToInt()
    }

    private fun set(v: Int, animate: Boolean) {
        val nv = v.coerceIn(from, to)
        anim?.cancel()
        if (animate) {
            anim = ValueAnimator.ofFloat(shown, frac(nv)).apply {
                duration = 200; interpolator = ease
                addUpdateListener { shown = it.animatedValue as Float; invalidate() }
                start()
            }
        } else { shown = frac(nv); invalidate() }
        if (nv != value) {
            value = nv
            if (nv == from || nv == to) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onChange?.invoke(nv)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; dragging = false; return true }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) {
                    val dx = abs(e.x - downX); val dy = abs(e.y - downY)
                    if (dy > slop && dy > dx) { parent?.requestDisallowInterceptTouchEvent(false); return false }
                    if (dx > slop) { dragging = true; parent?.requestDisallowInterceptTouchEvent(true) }
                }
                if (dragging) set(valueAt(e.x), false)
            }
            MotionEvent.ACTION_UP -> { if (!dragging) set(valueAt(e.x), true); dragging = false }
            MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }
}
