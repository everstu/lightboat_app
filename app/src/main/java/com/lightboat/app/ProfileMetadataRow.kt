package com.lightboat.app

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.TextView
import kotlin.math.ceil
import kotlin.math.max

/** Keep the script count on the right; wrap the whole badge when the tags need more room. */
internal class ProfileMetadataRow @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {
    private val gap = (8 * resources.displayMetrics.density).toInt()
    private var wrapped = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val tags = getChildAt(0)
        val scripts = getChildAt(1)
        val freeHeight = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        scripts.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), freeHeight)
        tags.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), freeHeight)
        val platform = findViewById<TextView>(R.id.profileBadge)
        val category = findViewById<TextView>(R.id.profileCategory)
        val minimumCategory = ceil(category.paint.measureText(category.text.take(2).toString()).toDouble()).toInt() +
            category.compoundPaddingLeft + category.compoundPaddingRight
        val categoryMargin = (category.layoutParams as MarginLayoutParams).marginStart
        val minimumTags = platform.measuredWidth + categoryMargin + minimumCategory
        wrapped = minimumTags + gap + scripts.measuredWidth > width
        val tagsWidth = if (wrapped) width else (width - gap - scripts.measuredWidth).coerceAtLeast(0)
        tags.measure(MeasureSpec.makeMeasureSpec(tagsWidth, MeasureSpec.AT_MOST), freeHeight)
        val height = if (wrapped) tags.measuredHeight + gap / 2 + scripts.measuredHeight
            else max(tags.measuredHeight, scripts.measuredHeight)
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val tags = getChildAt(0)
        val scripts = getChildAt(1)
        val tagsTop = if (wrapped) 0 else (height - tags.measuredHeight) / 2
        tags.layout(0, tagsTop, tags.measuredWidth, tagsTop + tags.measuredHeight)
        val scriptTop = if (wrapped) tags.measuredHeight + gap / 2 else (height - scripts.measuredHeight) / 2
        scripts.layout(width - scripts.measuredWidth, scriptTop, width, scriptTop + scripts.measuredHeight)
    }
}
