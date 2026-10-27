package com.ouor.pocketboard.core.ink.segment

import com.ouor.pocketboard.core.ink.box
import org.junit.Assert.assertEquals
import org.junit.Test

class RelayoutTest {
    @Test
    fun overlapped_clustersAreShiftedRightInOrder_timesKept() {
        // 같은 자리(0..100)에 세 글자를 겹쳐 씀
        val a = box(0f, 0f, 100f, 0)
        val b = box(0f, 0f, 100f, 1000)
        val c = box(0f, 0f, 100f, 2000)
        val out = relayout(listOf(listOf(a), listOf(b), listOf(c)), gapRatio = 0.2f, minCharSize = 24f)

        assertEquals(listOf(0f, 120f, 240f), out.map { it.bounds().left })
        assertEquals(listOf(a, b, c).map { s -> s.points.map { it.t } }, out.map { s -> s.points.map { it.t } })
        assertEquals(listOf(a, b, c).map { s -> s.points.map { it.y } }, out.map { s -> s.points.map { it.y } })
    }

    @Test
    fun continuation_alreadyApart_staysInPlace() {
        val a = box(0f, 0f, 100f, 0)
        val b = box(300f, 0f, 100f, 500)
        assertEquals(listOf(a, b), relayout(listOf(listOf(a), listOf(b)), 0.2f, 24f))
    }
}
