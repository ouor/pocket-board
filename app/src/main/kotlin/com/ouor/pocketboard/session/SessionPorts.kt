package com.ouor.pocketboard.session

import com.ouor.pocketboard.core.common.ink.InkItem
import com.ouor.pocketboard.core.gesture.ContactRecord
import com.ouor.pocketboard.core.gesture.HapticPattern
import com.ouor.pocketboard.core.ink.InkReport

/** 오판정 기록(F30) 출구. 구현은 `data.DevLog`(꺼져 있으면 아무것도 안 함). */
interface SessionLog {
    fun contact(record: ContactRecord)
    /** [report] 가 있으면 보낸 [items] 와 함께 잉크 기록(ink 줄 + SVG)도 남긴다. */
    fun send(text: String, ruleIds: List<String>, keyRuleIds: List<String>, result: String, items: List<InkItem> = emptyList(), report: InkReport? = null)
    fun haptic(pattern: HapticPattern)

    companion object {
        val NONE = object : SessionLog {
            override fun contact(record: ContactRecord) = Unit
            override fun send(text: String, ruleIds: List<String>, keyRuleIds: List<String>, result: String, items: List<InkItem>, report: InkReport?) = Unit
            override fun haptic(pattern: HapticPattern) = Unit
        }
    }
}

/**
 * 연결 시작. 구현(`service.ConnectionController`)은 포그라운드 서비스를 띄운 뒤 `hid.start()` 한다.
 * 포그라운드 화면(터치 중)에서만 부른다(Android 12+ 백그라운드 FGS 시작 제한).
 */
fun interface ConnectionStarter {
    fun ensureStarted()
}
