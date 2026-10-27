package com.ouor.pocketboard.core.keymap

import com.ouor.pocketboard.core.common.key.KeyStep

/**
 * 호스트 OS 별 키 전략(확장 지점). 1차 구현은 Windows 하나.
 * keymap 내부 계약이다: 다른 모듈은 이 타입을 모른다. 시그니처는 keymap 담당이 구현하며 다듬어도 된다.
 */
interface OsKeyStrategy {
    val os: HostOs

    fun toggleIme(settings: KeymapSettings): List<KeyStep>

    fun commitComposition(settings: KeymapSettings): List<KeyStep>

    /** 영문자 하나. 필요한 토글(Caps Lock 등)과 바뀐 상태를 함께 돌려준다. */
    fun latinLetter(c: Char, state: HostImeState, settings: KeymapSettings): Pair<List<KeyStep>, HostImeState>

    /** 숫자·기호·공백·엔터 등 IME 상태와 무관한 키. null = 이 OS 에서 변환 불가. */
    fun symbol(c: Char): List<KeyStep>?
}
