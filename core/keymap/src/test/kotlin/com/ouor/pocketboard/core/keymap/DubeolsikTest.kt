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

    /** 완성형 11,172자 전부: 두벌식 자판 글자만 쓰고, 자모 2~5키(겹받침·이중모음 분해 포함). */
    @Test
    fun everySyllable_usesOnlyDubeolsikKeys() {
        val allowed = "qwertyuiopasdfghjklzxcvbnmQWERTOP".toSet()
        for (c in '가'..'힣') {
            val k = Dubeolsik.keys(c)
            assertTrue("$c → $k", k.length in 2..5 && k.all { it in allowed })
        }
        assertEquals("rk", Dubeolsik.keys('가'))
        assertEquals("gl", Dubeolsik.keys('히'))
        assertEquals("glg", Dubeolsik.keys('힣'))
        assertEquals("Rhkfa", Dubeolsik.keys('꽒'))
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
