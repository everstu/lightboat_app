package com.lightboat.app

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/** A ScrollView whose measured height can be capped without hiding its scrollbar. */
internal class BoundedScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ScrollView(context, attrs, defStyleAttr) {
    var maximumHeight: Int = 0
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (maximumHeight <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val available = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.UNSPECIFIED -> maximumHeight
            else -> minOf(maximumHeight, MeasureSpec.getSize(heightMeasureSpec))
        }.takeIf { it > 0 } ?: maximumHeight
        super.onMeasure(widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(available.coerceAtLeast(0), MeasureSpec.AT_MOST))
    }
}
