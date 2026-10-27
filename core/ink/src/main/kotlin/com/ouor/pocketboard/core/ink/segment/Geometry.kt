package com.ouor.pocketboard.core.ink.segment

import com.ouor.pocketboard.core.common.ink.InkPoint
import com.ouor.pocketboard.core.common.ink.InkStroke

/** 축 정렬 상자(dp). 1점 획은 넓이 0 상자가 된다. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top

    fun contains(p: InkPoint, margin: Float) =
        p.x >= left - margin && p.x <= right + margin && p.y >= top - margin && p.y <= bottom + margin

    fun union(o: Box) = Box(minOf(left, o.left), minOf(top, o.top), maxOf(right, o.right), maxOf(bottom, o.bottom))
}

fun InkStroke.bounds(): Box = points.fold(Box(points[0].x, points[0].y, points[0].x, points[0].y)) { b, p ->
    Box(minOf(b.left, p.x), minOf(b.top, p.y), maxOf(b.right, p.x), maxOf(b.bottom, p.y))
}

/** 빈 목록은 호출하지 않는다(묶음은 항상 획 1개 이상). */
fun List<InkStroke>.bounds(): Box = map { it.bounds() }.reduce(Box::union)

fun InkStroke.translateX(dx: Float): InkStroke =
    if (dx == 0f) this else InkStroke(points.map { it.copy(x = it.x + dx) })
