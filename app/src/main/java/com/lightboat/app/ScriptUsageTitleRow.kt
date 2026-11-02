package com.lightboat.app

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kotlin.math.ceil
import kotlin.math.max

/** Width test for the script usage badge, kept pure so narrow and 2x-font layouts are testable. */
internal object ScriptUsagePlacement {
    /** A name shorter than this many rendered CJK glyphs is no longer recognizable. */
    const val MIN_NAME_GLYPHS = 4

    /** Whether the name keeps [MIN_NAME_GLYPHS] glyphs while the whole badge shares its row. */
    fun fitsInline(availableWidth: Int, actionWidth: Int, badgeWidth: Int, minimumNameWidth: Int, gap: Int): Boolean =
        availableWidth - actionWidth - gap - badgeWidth >= minimumNameWidth
}

/**
 * Title row of a script library entry: name (child 0), saved-profile usage badge (child 1) and
 * overflow action (child 2). The badge follows the name while both fit; otherwise the entire badge
 * moves below the title row and uses the entry width without reserving space for the action.
 * Measuring here re-decides on every width change, so rotation needs no re-binding of recycled rows.
 */
internal class ScriptUsageTitleRow @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {
    private val density = resources.displayMetrics.density
    private val badgeGap = (8 * density).toInt()
    private val rowGap = (4 * density).toInt()
    private val actionSize = (48 * density).toInt()

    /** Set by the latest measure pass, used by layout and by UI tests. */
    var badgeWrapped = false
        private set

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.UNSPECIFIED -> resources.displayMetrics.widthPixels
            else -> MeasureSpec.getSize(widthMeasureSpec)
        }
        val heightLimit = MeasureSpec.getSize(heightMeasureSpec)
        val childHeightSpec = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED)
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        else MeasureSpec.makeMeasureSpec(heightLimit, MeasureSpec.AT_MOST)
        val name = getChildAt(0)
        val badge = getChildAt(1)
        val action = getChildAt(2)
        if (name == null || badge == null || action == null) {
            setMeasuredDimension(available, resolveSize(0, heightMeasureSpec))
            return
        }
        action.measure(MeasureSpec.makeMeasureSpec(actionSize, MeasureSpec.EXACTLY), childHeightSpec)
        // Natural badge width, clamped only when the entry itself is narrower than the badge.
        badge.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST), childHeightSpec)
        badgeWrapped = !ScriptUsagePlacement.fitsInline(available, actionSize, badge.measuredWidth,
            minimumNameWidth(name), badgeGap)
        val nameLimit = if (badgeWrapped) available - actionSize
            else available - actionSize - badgeGap - badge.measuredWidth
        name.measure(MeasureSpec.makeMeasureSpec(nameLimit.coerceAtLeast(0), MeasureSpec.AT_MOST), childHeightSpec)
        val titleHeight = max(name.measuredHeight, action.measuredHeight)
        val total = if (badgeWrapped) titleHeight + rowGap + badge.measuredHeight
            else max(titleHeight, badge.measuredHeight)
        setMeasuredDimension(available, resolveSize(total, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val name = getChildAt(0) ?: return
        val badge = getChildAt(1) ?: return
        val action = getChildAt(2) ?: return
        val width = r - l
        val titleHeight = max(name.measuredHeight, action.measuredHeight)
        action.layout(width - action.measuredWidth, (titleHeight - action.measuredHeight) / 2,
            width, (titleHeight + action.measuredHeight) / 2)
        val nameTop = (titleHeight - name.measuredHeight) / 2
        name.layout(0, nameTop, name.measuredWidth, nameTop + name.measuredHeight)
        if (badgeWrapped) {
            badge.layout(0, titleHeight + rowGap, badge.measuredWidth, titleHeight + rowGap + badge.measuredHeight)
        } else {
            val left = name.measuredWidth + badgeGap
            val top = (titleHeight - badge.measuredHeight) / 2
            badge.layout(left, top, left + badge.measuredWidth, top + badge.measuredHeight)
        }
    }

    /** Text width plus the checkbox and padding a selecting row draws before the name. */
    private fun minimumNameWidth(name: View): Int {
        val text = name as? TextView ?: return actionSize
        val glyphs = text.paint.measureText("汉".repeat(ScriptUsagePlacement.MIN_NAME_GLYPHS))
        return ceil(glyphs.toDouble()).toInt() + text.compoundPaddingLeft + text.compoundPaddingRight
    }
}
