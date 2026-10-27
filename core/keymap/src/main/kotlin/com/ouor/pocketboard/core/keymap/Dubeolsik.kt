package com.ouor.pocketboard.core.keymap

/**
 * 한글 음절·호환 자모 → 두벌식 QWERTY 키 문자열.
 *
 * 겹자음·이중모음 분해 표와 자모→키 표는 es-hangul(MIT, https://github.com/toss/es-hangul,
 * commit 0ecc91ee, src/_internal/constants.ts, src/keyboard/convertHangulToQwerty/constants.ts)에서 옮겼다.
 * 경계 병합 판정([merges])은 libhangul(LGPL) 오토마타 동작을 참고해 새로 작성했다(코드 복사 없음).
 */
internal object Dubeolsik {
    private const val BASE = 0xAC00
    private const val LAST = 0xD7A3
    private const val CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
    private const val JUNG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"
    private const val JONG = " ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ" // 0번 = 받침 없음

    private val SPLIT: Map<Char, String> = mapOf(
        'ㄳ' to "ㄱㅅ", 'ㄵ' to "ㄴㅈ", 'ㄶ' to "ㄴㅎ", 'ㄺ' to "ㄹㄱ", 'ㄻ' to "ㄹㅁ", 'ㄼ' to "ㄹㅂ",
        'ㄽ' to "ㄹㅅ", 'ㄾ' to "ㄹㅌ", 'ㄿ' to "ㄹㅍ", 'ㅀ' to "ㄹㅎ", 'ㅄ' to "ㅂㅅ",
        'ㅘ' to "ㅗㅏ", 'ㅙ' to "ㅗㅐ", 'ㅚ' to "ㅗㅣ", 'ㅝ' to "ㅜㅓ", 'ㅞ' to "ㅜㅔ", 'ㅟ' to "ㅜㅣ", 'ㅢ' to "ㅡㅣ",
    )
    private val COMBINE: Set<String> = SPLIT.values.toSet()

    private val KEY: Map<Char, Char> =
        ("ㄱrㄲRㄴsㄷeㄸEㄹfㅁaㅂqㅃQㅅtㅆTㅇdㅈwㅉWㅊcㅋzㅌxㅍvㅎg" +
            "ㅏkㅐoㅑiㅒOㅓjㅔpㅕuㅖPㅗhㅛyㅜnㅠbㅡmㅣl").chunked(2).associate { it[0] to it[1] }

    fun isSyllable(c: Char) = c.code in BASE..LAST
    fun isJamo(c: Char) = c in KEY || c in SPLIT
    fun isHangul(c: Char) = isSyllable(c) || isJamo(c)

    /** (초, 중, 종) 호환 자모. 종성 없으면 null. */
    fun decompose(c: Char): Triple<Char, Char, Char?> {
        val i = c.code - BASE
        val jong = JONG[i % 28]
        return Triple(CHO[i / (21 * 28)], JUNG[i / 28 % 21], jong.takeIf { it != ' ' })
    }

    /** 두벌식 키 문자열. 대문자 = Shift. 예: '닭' → "ekfr", 'ㅘ' → "hk". */
    fun keys(c: Char): String {
        val jamo = if (isSyllable(c)) decompose(c).toList().filterNotNull() else listOf(c)
        return jamo.joinToString("") { j -> (SPLIT[j] ?: j.toString()).map { KEY.getValue(it) }.joinToString("") }
    }

    /**
     * 호스트 IME 가 [prev] 바로 뒤에 온 자모 [next]의 첫 키를 [prev] 쪽에 합쳐 버리는가.
     * 예: 각+ㅏ(받침 이동), ㄱ+ㅅ(ㄳ), 고+ㅏ(과), 가+ㅅ(갓). ㅋ+ㅋ, ㅠ+ㅠ 는 합쳐지지 않는다.
     * 연타 된소리(ㄱ+ㄱ→ㄲ)는 MS IME 기본값에서 꺼져 있다고 가정한다(research/keymap.md Q2).
     */
    fun merges(prev: Char, next: Char): Boolean {
        val first = (SPLIT[next] ?: next.toString())[0]
        val vowel = first in JUNG
        if (isSyllable(prev)) {
            val (_, jung, jong) = decompose(prev)
            return when {
                jong == null -> if (vowel) "$jung$first" in COMBINE else first in JONG
                vowel -> true
                else -> "$jong$first" in COMBINE
            }
        }
        return if (prev in JUNG) vowel && "$prev$first" in COMBINE else vowel || "$prev$first" in COMBINE
    }
}
