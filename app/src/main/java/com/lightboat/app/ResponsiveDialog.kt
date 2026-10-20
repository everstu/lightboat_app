package com.lightboat.app

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.Window

// MainActivity handles orientation changes without recreation, so its visible dialogs resize explicitly.
internal interface ResponsiveDialog {
    fun updateLayout()
}

/** Shared sizing for ResponsiveDialog windows: capped width, measured height, one layout pass. */
internal object DialogLayout {
    fun width(context: Context, maxWidthDp: Int = 600, marginDp: Int = 24): Int {
        val density = context.resources.displayMetrics.density
        return minOf((maxWidthDp * density).toInt(), context.resources.displayMetrics.widthPixels - (marginDp * density).toInt())
    }

    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    fun visibleHeight(window: Window?): Int {
        val visible = Rect()
        window?.decorView?.getWindowVisibleDisplayFrame(visible)
        return visible.height()
    }

    /** Measure [root] at [width] capped by [maxHeightPx], then resize the window once. */
    fun apply(window: Window?, root: View, width: Int, maxHeightPx: Int) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxHeightPx.coerceAtLeast(1), View.MeasureSpec.AT_MOST))
        val height = root.measuredHeight
        if (window != null && (window.attributes.width != width || window.attributes.height != height)) {
            window.setLayout(width, height)
        }
    }
}
