package com.ouor.pocketboard.core.keymap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// 기대값 일부는 es-hangul(MIT) convertHangulToQwerty.spec.ts 의 케이스.
class DubeolsikTest {
    private fun keys(s: String) = s.map { Dubeolsik.keys(it) }.joinToString("")

    @Test
    fun syllables_doubleConsonants_compoundVowelsAndBatchim() {
        assertEquals("dkssudgktpdy", keys("안녕하세요"))
        assertEquals("QkWkEkRkTk", keys("빠짜따까싸"))
        assertEquals("rhkrnjrhlrnlrml", keys("과궈괴귀긔"))
        assertEquals("ekfr", keys("닭")) // ㄺ → ㄹㄱ
        assertEquals("djqt", keys("없")) // ㅄ → ㅂㅅ
    }

    @Test
    fun standaloneJamo() {
        assertEquals("zz", keys("ㅋㅋ"))
        assertEquals("rt", keys("ㄳ"))
        assertEquals("hk", keys("ㅘ"))
    }

    @Test
    fun merges_onlyWhenHostImeWouldCombine() {
        assertTrue(Dubeolsik.merges('각', 'ㅏ'))
        assertTrue(Dubeolsik.merges('ㄱ', 'ㅅ'))
        assertTrue(Dubeolsik.merges('고', 'ㅏ'))
        assertTrue(Dubeolsik.merges('가', 'ㅅ'))
        assertFalse(Dubeolsik.merges('ㅋ', 'ㅋ'))
        assertFalse(Dubeolsik.merges('ㅠ', 'ㅠ'))
        assertFalse(Dubeolsik.merges('가', 'ㄸ')) // ㄸ 은 받침이 될 수 없다
        assertFalse(Dubeolsik.merges('닭', 'ㄱ'))
    }
}
