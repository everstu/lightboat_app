package com.lightboat.app

import kotlin.math.max

/** Geometry shared by drag bounds, edge docking and resize handling. */
object MenuPlacement {
    data class Point(val x: Float, val y: Float)

    fun clamp(x: Float, y: Float, areaWidth: Int, areaHeight: Int, size: Int): Point = Point(
        x.coerceIn(0f, max(0, areaWidth - size).toFloat()),
        y.coerceIn(0f, max(0, areaHeight - size).toFloat())
    )

    fun anchor(left: Boolean, fraction: Float, areaWidth: Int, areaHeight: Int, size: Int): Point = Point(
        if (left) 0f else max(0, areaWidth - size).toFloat(),
        fraction.coerceIn(0f, 1f) * max(0, areaHeight - size)
    )
}
