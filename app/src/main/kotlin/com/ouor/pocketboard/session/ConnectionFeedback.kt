package com.ouor.pocketboard.session

import com.ouor.pocketboard.core.gesture.HapticPattern
import com.ouor.pocketboard.core.gesture.Haptics
import com.ouor.pocketboard.core.hid.HidEvent
import com.ouor.pocketboard.core.hid.HidState
import com.ouor.pocketboard.core.keymap.KeyInput

/**
 * HID 상태·이벤트 → 진동과 keymap 갱신(architecture §5.1, hid 명세 §3.2, R8). 메인 스레드에서만 부른다.
 *
 * 끊김 진동 `— —` 은 "끊긴 한 번(에피소드)"에 한 번만 울린다: [HidEvent.LostUnexpectedly] 와 재연결 시작
 * ([HidState.Reconnecting] 진입) 중 먼저 온 쪽이 울리고, 두 흐름의 도착 순서와 무관하다. 재시도마다 반복하지 않는다(SC09 E6).
 * 재연결 중 `repairLikely` 가 처음 켜지면 한 번 더 울린다(SC09 E10).
 */
class ConnectionFeedback(private val haptics: Haptics, private val keyInput: KeyInput) {
    private var lossAlerted = false
    private var repairAlerted = false

    fun onEvent(e: HidEvent) {
        when (e) {
            is HidEvent.Connected -> {
                keyInput.onHostConnected()
                haptics.play(HapticPattern.CONNECTED)
            }
            HidEvent.LostUnexpectedly -> alertLoss()
            HidEvent.IdleDisconnected -> Unit // D7: 진동 없음
        }
    }

    fun onState(s: HidState) {
        when (s) {
            is HidState.Reconnecting -> {
                alertLoss()
                if (s.repairLikely && !repairAlerted) {
                    repairAlerted = true
                    haptics.play(HapticPattern.DISCONNECTED)
                }
            }
            is HidState.Connected, HidState.Stopped -> {
                lossAlerted = false
                repairAlerted = false
            }
            else -> Unit
        }
    }

    private fun alertLoss() {
        if (lossAlerted) return
        lossAlerted = true
        haptics.play(HapticPattern.DISCONNECTED)
    }
}
