package com.lightboat.app

import android.content.Context
import android.content.res.ColorStateList
import android.animation.ValueAnimator
import android.graphics.Rect
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.ViewCompat
import androidx.core.view.children

/** Small visual footprint, with a separate 48 dp touch target and an explicit reveal tap. */
class ProfileFloatingActionButton @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {
    private val accessibility = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    private val surface = FrameLayout(context)
    private val icon = AppCompatImageView(context)
    private val label = AppCompatTextView(context)
    private var resize: ValueAnimator? = null
    private val settle = Runnable { if (!isPressed && isShown && !accessibility.isTouchExplorationEnabled) collapse(true) }
    private val explorationChanged = AccessibilityManager.TouchExplorationStateChangeListener { enabled ->
        if (enabled) expand(false) else collapse(false)
    }

    init {
        val attributes = context.obtainStyledAttributes(attrs, R.styleable.ProfileFloatingActionButton)
        val title = attributes.getText(R.styleable.ProfileFloatingActionButton_actionLabel) ?: ""
        val caption = attributes.getText(R.styleable.ProfileFloatingActionButton_actionCaption) ?: title
        icon.setImageDrawable(attributes.getDrawable(R.styleable.ProfileFloatingActionButton_actionIcon))
        surface.background = attributes.getDrawable(R.styleable.ProfileFloatingActionButton_actionBackground)
        attributes.recycle()
        contentDescription = title
        tooltipText = title
        isClickable = true
        isFocusable = true
        // The 78 dp surface is scaled into the smaller touch container at rest.
        clipChildren = false
        clipToPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        surface.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        surface.elevation = dp(2).toFloat()
        icon.imageTintList = ColorStateList.valueOf(context.getColor(R.color.white))
        label.apply {
            text = caption
            textSize = 12f
            setTextColor(context.getColor(R.color.white))
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setSingleLine()
        }
        surface.addView(icon, LayoutParams(dp(32), dp(32), Gravity.CENTER))
        surface.addView(label, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        label.translationY = dp(18).toFloat()
        addView(surface, LayoutParams(dp(78), dp(78), Gravity.END or Gravity.CENTER_VERTICAL))
        surface.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            surface.pivotX = surface.width.toFloat()
            surface.pivotY = surface.height / 2f
        }
        collapse(false)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun showExpanded(expanded: Boolean, animated: Boolean) {
        removeCallbacks(settle)
        resize?.cancel()
        isSelected = expanded
        label.visibility = if (expanded) View.VISIBLE else View.GONE
        icon.translationY = if (expanded) -dp(10).toFloat() else 0f
        val scale = if (expanded) 1f else 0.5f
        fun applyScale(value: Float) {
            surface.scaleX = value
            surface.scaleY = value
            // Pack the circles closely while retaining at least a 48 dp touch target.
            val targetHeight = maxOf(dp(48), (dp(78) * value + 0.5f).toInt())
            layoutParams?.takeIf { it.height != targetHeight }?.let { it.height = targetHeight; layoutParams = it }
        }
        if (animated) resize = ValueAnimator.ofFloat(surface.scaleX, scale).apply {
            duration = 160
            addUpdateListener { applyScale(it.animatedValue as Float) }
            start()
        } else applyScale(scale)
        ViewCompat.setStateDescription(this, context.getString(if (expanded) R.string.floating_action_expanded else R.string.floating_action_collapsed))
        if (expanded && !accessibility.isTouchExplorationEnabled) postDelayed(settle, 3000)
    }

    private fun expand(animated: Boolean) {
        (parent as? ViewGroup)?.children?.filterIsInstance<ProfileFloatingActionButton>()
            ?.filter { it !== this }?.forEach { it.collapse(animated) }
        showExpanded(true, animated)
    }

    private fun collapse(animated: Boolean) = showExpanded(false, animated)

    internal fun collapseIfOutside(rawX: Float, rawY: Float) {
        if (!isSelected) return
        val bounds = Rect()
        if (!getGlobalVisibleRect(bounds) || !bounds.contains(rawX.toInt(), rawY.toInt())) collapse(true)
    }

    override fun performClick(): Boolean {
        if (!isEnabled) return false
        if (!isSelected && !accessibility.isTouchExplorationEnabled) { expand(true); return true }
        collapse(false)
        return super.performClick()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Unused space beside the resting icon remains available to the list beneath.
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !isSelected && event.x < width - dp(48)) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) removeCallbacks(settle)
        val handled = super.onTouchEvent(event)
        if ((event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) && isSelected) {
            removeCallbacks(settle)
            postDelayed(settle, 3000)
        }
        return handled
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (gainFocus) expand(false)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = Button::class.java.name
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        accessibility.addTouchExplorationStateChangeListener(explorationChanged)
        if (accessibility.isTouchExplorationEnabled) expand(false) else collapse(false)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != View.VISIBLE && isAttachedToWindow) collapse(false)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(settle)
        resize?.cancel()
        accessibility.removeTouchExplorationStateChangeListener(explorationChanged)
        super.onDetachedFromWindow()
    }
}
