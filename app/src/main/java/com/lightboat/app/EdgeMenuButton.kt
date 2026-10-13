package com.lightboat.app

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.appcompat.widget.AppCompatImageButton
import kotlin.math.abs

class EdgeMenuButton @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    AppCompatImageButton(context, attrs) {
    var leftEdge = false
        private set
    var heightFraction = 0.68f
        private set
    private var downX = 0f
    private var downY = 0f
    private var startX = 0f
    private var startY = 0f
    private var dragging = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val settle = Runnable {
        if (isShown) {
            alignToEdge()
            // Only 8 dp tucks away: a 56 dp button retains a 48 dp touch target.
            val inset = 8f * resources.displayMetrics.density
            animate().translationX(x - left + if (leftEdge) -inset else inset).alpha(0.5f).setDuration(200).start()
        }
    }
    private val parentResize = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        if (!dragging) { alignToEdge(); scheduleDock() }
    }

    fun restoreAnchor(left: Boolean, fraction: Float) {
        leftEdge = left
        heightFraction = fraction.coerceIn(0f, 1f)
        post { alignToEdge(); scheduleDock() }
    }

    private fun alignToEdge() {
        val area = parent as? View ?: return
        if (area.width == 0 || width == 0) return
        val point = MenuPlacement.anchor(leftEdge, heightFraction, area.width, area.height, width)
        x = point.x
        y = point.y
    }

    private fun scheduleDock() {
        removeCallbacks(settle)
        postDelayed(settle, 3000)
    }

    fun wake() {
        animate().cancel()
        removeCallbacks(settle)
        alpha = 1f
        alignToEdge()
        scheduleDock()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        (parent as? View)?.addOnLayoutChangeListener(parentResize)
        post { alignToEdge(); scheduleDock() }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(settle)
        animate().cancel()
        (parent as? View)?.removeOnLayoutChangeListener(parentResize)
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val area = parent as? View ?: return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                wake()
                removeCallbacks(settle)
                downX = event.rawX; downY = event.rawY
                startX = x; startY = y
                dragging = false
                isPressed = true
                parent.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (abs(dx) > slop || abs(dy) > slop) dragging = true
                if (dragging) {
                    val point = MenuPlacement.clamp(startX + dx, startY + dy, area.width, area.height, width)
                    x = point.x; y = point.y
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                parent.requestDisallowInterceptTouchEvent(false)
                if (dragging) {
                    leftEdge = x + width / 2f < area.width / 2f
                    heightFraction = if (area.height > height) y / (area.height - height) else 0f
                } else if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                dragging = false
                alignToEdge()
                scheduleDock()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
