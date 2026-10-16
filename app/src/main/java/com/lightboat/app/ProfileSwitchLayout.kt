package com.lightboat.app

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout

internal class ProfileSwitchLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    var maximumHeight: Int = Int.MAX_VALUE
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        val available = if (mode == MeasureSpec.UNSPECIFIED) maximumHeight
            else minOf(MeasureSpec.getSize(heightMeasureSpec), maximumHeight)
        val boundedMode = if (mode == MeasureSpec.UNSPECIFIED) MeasureSpec.AT_MOST else mode
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(available, boundedMode))
    }
}
