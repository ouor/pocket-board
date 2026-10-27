package com.ouor.pocketboard.core.ink.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SingleSyllableMergeRuleTest {
    @Test
    fun singleSyllable_merges() = assertEquals(true, SingleSyllableMergeRule.apply(MergeInput(listOf("안", "앙"))))

    @Test
    fun twoChars_orJamo_orNothing_pass() {
        assertNull(SingleSyllableMergeRule.apply(MergeInput(listOf("아ㄴ"))))
        assertNull(SingleSyllableMergeRule.apply(MergeInput(listOf("ㅇ"))))
        assertNull(SingleSyllableMergeRule.apply(MergeInput(emptyList())))
    }
}
