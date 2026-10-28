package com.ouor.pocketboard.core.ink.rules

import com.ouor.pocketboard.core.ink.box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StrokeExcessSplitRuleTest {
    private val rule = StrokeExcessSplitRule(InkConfig())
    private fun strokes(n: Int) = List(n) { box(0f, 0f, 100f, it * 300L) }

    @Test
    fun table_coversAllCompatJamo() = assertEquals(('ㄱ'..'ㅣ').toSet(), JAMO_STROKES.keys)

    @Test
    fun syllableStrokes() {
        assertEquals(3, hangulStrokes("가"))
        assertEquals(6, hangulStrokes("한"))
        assertEquals(13, hangulStrokes("뷁"))
        assertNull(hangulStrokes("a"))
        assertNull(hangulStrokes("ㅋㅋ"))
    }

    @Test
    fun kOverlappedTwice_splits() = assertEquals("ㅋ", rule.apply(SplitInput(strokes(4), "ㅋ")))

    @Test
    fun kWrittenInThreeStrokes_passes() = assertNull(rule.apply(SplitInput(strokes(3), "ㅋ")))

    @Test
    fun latin_passes() = assertNull(rule.apply(SplitInput(strokes(8), "l")))
}
