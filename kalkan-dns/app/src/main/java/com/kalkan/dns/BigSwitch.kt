package com.kalkan.dns

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/** Büyük, animasyonlu açma/kapama düğmesi. */
class BigSwitch @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    var onToggle: ((Boolean) -> Unit)? = null
    var isOn = false
        private set
    private var pos = 0f // 0 = kapalı, 1 = açık
    private var anim: ValueAnimator? = null

    private val offColor = 0xFF3A3F4B.toInt()
    private val onColor = 0xFFF6821F.toInt()
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000 }
    private val rect = RectF()

    init {
        isClickable = true
        setOnClickListener { onToggle?.invoke(!isOn) }
    }

    fun setOn(on: Boolean, animate: Boolean = true) {
        if (on == isOn && anim == null) { pos = if (on) 1f else 0f; invalidate(); return }
        isOn = on
        anim?.cancel()
        val target = if (on) 1f else 0f
        if (!animate) { pos = target; invalidate(); return }
        anim = ValueAnimator.ofFloat(pos, target).apply {
            duration = 280
            interpolator = DecelerateInterpolator()
            addUpdateListener { pos = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { anim = null }
            })
            start()
        }
    }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        setMeasuredDimension(width, (width * 0.5f).toInt())
    }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        track.color = blend(offColor, onColor, pos)
        rect.set(0f, 0f, w, h)
        c.drawRoundRect(rect, h / 2, h / 2, track)
        val r = h / 2 - h * 0.08f
        val cx = h / 2 + (w - h) * pos
        c.drawCircle(cx, h / 2 + h * 0.03f, r, shadow)
        c.drawCircle(cx, h / 2, r, knob)
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - t) + ((b shr s) and 0xFF) * t).toInt() shl s
        return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
    }
}
