package com.ouor.pocketboard.core.ink.segment

import com.ouor.pocketboard.core.common.ink.InkStroke

/**
 * 가상 재배치(ink.md §4.2): 묶음을 왼쪽→오른쪽 한 줄로 편다. "겹칠 때만 민다":
 * 묶음 왼쪽이 (앞 묶음 오른쪽 + 간격)보다 왼쪽이면 그만큼 오른쪽으로 평행 이동, 아니면 제자리.
 * 그래서 이어 쓰기만 한 입력은 그대로 남는다. y·타임스탬프·획 순서는 바꾸지 않는다(ink.md §2.2).
 *
 * @param gapRatio 묶음 사이 간격 = 앞 묶음 높이(최소 [minCharSize]) × gapRatio
 */
fun relayout(clusters: List<List<InkStroke>>, gapRatio: Float, minCharSize: Float): List<InkStroke> {
    val out = ArrayList<InkStroke>()
    var prevRight = Float.NEGATIVE_INFINITY
    var prevSize = 0f
    for (cluster in clusters) {
        val box = cluster.bounds()
        val minLeft = prevRight + maxOf(prevSize, minCharSize) * gapRatio
        val dx = if (box.left < minLeft) minLeft - box.left else 0f
        cluster.mapTo(out) { it.translateX(dx) }
        prevRight = box.right + dx
        prevSize = box.height
    }
    return out
}
