package com.ouor.pocketboard.core.ink.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrimLeadingRuleTest {
    private fun apply(text: String) = TrimLeadingRule.apply(TextInput(text, emptyList()))?.text

    @Test
    fun leadingSpaceFromRecognizer_isTrimmed() {
        assertEquals("테스트", apply(" 테스트"))
        assertEquals("요즘에 주식시장", apply(" 요즘에 주식시장"))   // 단어 안 공백은 인식기 몫이라 그대로
    }

    @Test
    fun noLeadingSpace_passes() {
        assertNull(apply("요즘에 주식시장"))
        assertNull(apply(""))
    }
}
