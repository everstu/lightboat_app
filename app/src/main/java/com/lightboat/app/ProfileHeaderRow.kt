package com.lightboat.app

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import kotlin.math.ceil
import kotlin.math.max

/** Right-aligned actions with a floating status label above the overflow icon. */
internal class ProfileHeaderRow @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {
    private var nameTop = 0
    private var actionsTop = 0
    private val labelGap get() = ceil(resources.displayMetrics.density).toInt()
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val available = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val name = getChildAt(0) as TextView
        val actions = getChildAt(1) as ViewGroup
        val status = getChildAt(2) as TextView
        // This non-interactive marker shares the toolbar's fixed icon area. Keep its
        // visual size compact; accessibility still exposes the complete status text.
        val compactStatusSize = 11 * resources.displayMetrics.density
        if (status.textSize > compactStatusSize) status.setTextSize(TypedValue.COMPLEX_UNIT_PX, compactStatusSize)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) status.setFallbackLineSpacing(false)
        val freeHeight = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        actions.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST), freeHeight)
        if (status.isVisible) status.measure(
            MeasureSpec.makeMeasureSpec(actions.measuredWidth, MeasureSpec.AT_MOST), freeHeight)
        val nameWidth = (available - actions.measuredWidth).coerceAtLeast(0)
        name.measure(MeasureSpec.makeMeasureSpec(nameWidth, MeasureSpec.EXACTLY), freeHeight)
        val rowHeight = max(name.measuredHeight, actions.measuredHeight)
        nameTop = (rowHeight - name.measuredHeight) / 2
        actionsTop = (rowHeight - actions.measuredHeight) / 2
        val height = max(nameTop + name.measuredHeight, actionsTop + actions.measuredHeight)
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val name = getChildAt(0)
        val actions = getChildAt(1)
        name.layout(paddingLeft, nameTop, paddingLeft + name.measuredWidth, nameTop + name.measuredHeight)
        val end = width - paddingRight
        actions.layout(end - actions.measuredWidth, actionsTop, end, actionsTop + actions.measuredHeight)
        // The label uses the existing button area; it never increases the header height.
        val status = getChildAt(2)
        if (status.isVisible) status.layout(end - status.measuredWidth, actionsTop + labelGap,
            end, actionsTop + labelGap + status.measuredHeight)
    }
}
