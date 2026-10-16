package com.lightboat.app

import android.graphics.Paint
import android.text.InputFilter
import android.text.Spanned

/** Measure the rendered label, so narrow Latin letters and wide glyphs use their actual space. */
internal class CategoryNameInputFilter(
    private val paint: Paint,
    private val maximumWidth: () -> Float,
    private val existingNames: () -> Collection<String>
) : InputFilter {
    fun fits(value: String): Boolean = existingNames().any { it.equals(value.trim(), ignoreCase = true) } ||
        paint.measureText(value.trim()) <= maximumWidth()

    override fun filter(source: CharSequence, start: Int, end: Int, dest: Spanned, dstart: Int, dend: Int): CharSequence? {
        // Always allow deletion, including shortening a legacy category name.
        if (start == end) return null
        val before = dest.subSequence(0, dstart).toString()
        val after = dest.subSequence(dend, dest.length).toString()
        if (fits(before + source.subSequence(start, end) + after)) return null
        var acceptedEnd = end
        while (acceptedEnd > start) {
            acceptedEnd = Character.offsetByCodePoints(source, acceptedEnd, -1)
            if (fits(before + source.subSequence(start, acceptedEnd) + after)) {
                return source.subSequence(start, acceptedEnd)
            }
        }
        // Preserve a selected range when an insertion cannot fit.
        return dest.subSequence(dstart, dend)
    }
}
