package com.lightboat.app

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityManager
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
    private var collapsed = true
    private var startedCollapsed = false
    private var touching = false
    private var gestureCancelled = false
    private var downTime = 0L
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val accessibility = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    private val settle = Runnable {
        if (isShown && !touching) dock(true)
    }
    private val explorationChanged = AccessibilityManager.TouchExplorationStateChangeListener { enabled ->
        if (enabled) wake() else dock()
    }
    private val parentResize = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        if (!touching) { animate().cancel(); alignToEdge(); scheduleDock() }
    }

    fun restoreAnchor(left: Boolean, fraction: Float) {
        leftEdge = left
        heightFraction = fraction.coerceIn(0f, 1f)
        post { dock() }
    }

    private fun alignToEdge() {
        val area = parent as? View ?: return
        if (area.width == 0 || width == 0) return
        val point = MenuPlacement.anchor(leftEdge, heightFraction, area.width, area.height, width)
        val hiddenWidth = (width - 12f * resources.displayMetrics.density).coerceAtLeast(0f)
        x = point.x + if (collapsed) { if (leftEdge) -hiddenWidth else hiddenWidth } else 0f
        y = point.y
    }

    private fun scheduleDock() {
        removeCallbacks(settle)
        if (!collapsed && !touching && !accessibility.isTouchExplorationEnabled) postDelayed(settle, 2500)
    }

    fun wake() {
        animate().cancel()
        removeCallbacks(settle)
        collapsed = false
        setBackgroundResource(R.drawable.bg_menu_button)
        imageAlpha = 255
        alpha = 1f
        elevation = 4f * resources.displayMetrics.density
        contentDescription = context.getString(R.string.browser_menu)
        tooltipText = context.getString(R.string.browser_menu_expanded_hint)
        alignToEdge()
        scheduleDock()
    }

    fun dock(animated: Boolean = false) {
        if (accessibility.isTouchExplorationEnabled) { wake(); return }
        animate().cancel()
        removeCallbacks(settle)
        val previousX = x
        collapsed = true
        isPressed = false
        setBackgroundResource(R.drawable.bg_menu_handle)
        imageAlpha = 0
        elevation = 0f
        contentDescription = context.getString(R.string.browser_menu_collapsed)
        tooltipText = context.getString(R.string.browser_menu_collapsed)
        alignToEdge()
        val targetX = x
        if (animated) {
            x = previousX
            animate().x(targetX).alpha(0.6f).setDuration(180).start()
        } else alpha = 0.6f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        (parent as? View)?.addOnLayoutChangeListener(parentResize)
        accessibility.addTouchExplorationStateChangeListener(explorationChanged)
        post { dock() }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(settle)
        animate().cancel()
        (parent as? View)?.removeOnLayoutChangeListener(parentResize)
        accessibility.removeTouchExplorationStateChangeListener(explorationChanged)
        touching = false
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val area = parent as? View ?: return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = true
                startedCollapsed = collapsed
                gestureCancelled = false
                downTime = event.eventTime
                animate().cancel()
                removeCallbacks(settle)
                downX = event.rawX; downY = event.rawY
                startX = x; startY = y
                dragging = false
                isPressed = !collapsed
                parent.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                // A multi-touch gesture must never turn into a menu click.
                gestureCancelled = true
                isPressed = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (gestureCancelled) return true
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                    dragging = true
                    if (collapsed) {
                        wake()
                        startX = x
                    }
                }
                if (dragging) {
                    isPressed = false
                    val point = MenuPlacement.clamp(startX + dx, startY + dy, area.width, area.height, width)
                    x = point.x; y = point.y
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                isPressed = false
                parent.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP && dragging && !gestureCancelled) {
                    leftEdge = x + width / 2f < area.width / 2f
                    heightFraction = if (area.height > height) y / (area.height - height) else 0f
                }
                if (event.actionMasked == MotionEvent.ACTION_UP && !dragging && !gestureCancelled &&
                    event.eventTime - downTime < ViewConfiguration.getLongPressTimeout() &&
                    event.x in 0f..width.toFloat() && event.y in 0f..height.toFloat()) {
                    // Revealing the handle and opening the menu require separate taps.
                    if (startedCollapsed) wake() else performClick()
                } else if (startedCollapsed) dock()
                dragging = false
                alignToEdge()
                scheduleDock()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        wake()
        super.performClick()
        return true
    }
}
