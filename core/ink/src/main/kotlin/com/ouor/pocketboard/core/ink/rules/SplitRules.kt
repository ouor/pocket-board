package com.ouor.pocketboard.core.ink.rules

import com.ouor.pocketboard.core.common.rule.Rule

// 출력 = 묶음을 다시 나눌 목표 글자(앞에서부터 이 글자로 읽히는 조각으로 자른다). 기본 "" = 그대로.

/**
 * P1(겹쳐 쓴 반복 글자): 묶음 획 수 ≥ 읽힌 한글 글자의 표준 획 수 × [InkConfig.splitStrokeRatio].
 * 같은 자리에 빨리 겹쳐 쓴 "ㅋㅋㅋㅋ"(8획)를 인식기가 "ㅋ"(2획) 하나로 읽는 현장 사례. 쉼·위치로는 글자 사이와
 * 글자 안 획 사이를 못 가르므로, 인식 결과의 획 수가 말이 안 되는 것을 신호로 쓴다.
 */
class StrokeExcessSplitRule(private val config: InkConfig) : Rule<SplitInput, String> {
    override val id = "split/stroke-excess"
    override fun apply(input: SplitInput): String? {
        val need = hangulStrokes(input.char) ?: return null
        return input.char.takeIf { input.strokes.size >= need * config.splitStrokeRatio }
    }
}

/** 한글 글자 하나(호환 자모 또는 완성 음절)의 표준 획 수. 그 밖은 null. 사람마다 다른 곳은 큰 쪽(ㅈ 3, ㅊ 4). */
fun hangulStrokes(s: String): Int? {
    if (s.length != 1) return null
    val c = s[0]
    if (c.isHangulSyllable()) {
        val i = c - '가'
        return JAMO_STROKES.getValue(INITIALS[i / 588]) + JAMO_STROKES.getValue('ㅏ' + i % 588 / 28) +
            (FINALS[i % 28].takeIf { it != ' ' }?.let(JAMO_STROKES::getValue) ?: 0)
    }
    return JAMO_STROKES[c]
}

private const val INITIALS = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
private const val FINALS = " ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ"

/** 호환 자모 51자(ㄱ~ㅣ) 전부. */
internal val JAMO_STROKES: Map<Char, Int> = listOf(
    "ㄱㄴㅇㅡㅣ" to 1,
    "ㄲㄷㅅㅋㅏㅓㅗㅜㅢ" to 2,
    "ㄳㄵㄹㅁㅈㅌㅎㅐㅑㅔㅕㅚㅛㅟㅠ" to 3,
    "ㄶㄸㄺㅂㅆㅊㅍㅒㅖㅘㅝ" to 4,
    "ㄽㅙㅞ" to 5,
    "ㄻㄾㅀㅄㅉ" to 6,
    "ㄼㄿ" to 7,
    "ㅃ" to 8,
).flatMap { (cs, n) -> cs.map { it to n } }.toMap()
