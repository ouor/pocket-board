package com.ouor.pocketboard.session

import com.ouor.pocketboard.core.hid.HidState
import com.ouor.pocketboard.core.ink.ModelState

/** 쓰기 화면(SC09) 상단 최저 밝기 한 줄. 문구는 UI 가 정한다(screens SC09 표). */
enum class WritingNotice { RECONNECTING, REPAIR_NEEDED, BLUETOOTH_OFF, MODEL_DOWNLOADING }

/** null = 표시 없음(완전 검정). 연결 문제가 모델 상태보다 먼저다. */
fun writingNotice(hid: HidState, model: ModelState): WritingNotice? = when {
    hid is HidState.Reconnecting && hid.repairLikely -> WritingNotice.REPAIR_NEEDED
    hid is HidState.Reconnecting -> WritingNotice.RECONNECTING
    hid is HidState.BluetoothOff -> WritingNotice.BLUETOOTH_OFF
    model is ModelState.Downloading || model is ModelState.Checking -> WritingNotice.MODEL_DOWNLOADING
    else -> null
}
