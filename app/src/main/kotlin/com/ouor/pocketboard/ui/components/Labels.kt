package com.ouor.pocketboard.ui.components

import com.ouor.pocketboard.core.gesture.Action
import com.ouor.pocketboard.core.gesture.Gesture
import com.ouor.pocketboard.core.gesture.GestureMapping
import com.ouor.pocketboard.core.gesture.HapticPattern
import com.ouor.pocketboard.core.gesture.SwipeDirection
import com.ouor.pocketboard.core.hid.HidState
import com.ouor.pocketboard.core.ink.ModelState

// 사용자에게 보이는 이름(design-system §9 문구 톤). 앱은 한국어 전용이라 리소스 대신 여기 모은다.

private fun fingers(n: Int) = when (n) { 1 -> "한"; 2 -> "두"; 3 -> "세"; else -> "$n" }
private fun times(n: Int) = when (n) { 1 -> "한"; 2 -> "두"; 3 -> "세"; 4 -> "네"; else -> "$n" }

val Gesture.label: String
    get() = when (this) {
        is Gesture.Tap -> if (count == 1) "${fingers(fingers)} 손가락 톡" else "${fingers(fingers)} 손가락 ${times(count)} 번 톡"
        is Gesture.Swipe -> "${fingers(fingers)} 손가락 ${direction.label} 밀기"
    }

private val SwipeDirection.label get() = when (this) {
    SwipeDirection.LEFT -> "왼쪽으로"
    SwipeDirection.RIGHT -> "오른쪽으로"
    SwipeDirection.UP -> "위로"
    SwipeDirection.DOWN -> "아래로"
}

/** 한 손가락 트리플·4번 탭: 매핑하면 더블 탭 확정이 늦어진다(A2, A29). */
val Gesture.delaysSend: Boolean get() = this is Gesture.Tap && fingers == 1 && count >= 3

val Action.label: String
    get() = when (this) {
        Action.SEND -> "보내기"
        Action.ENTER -> "엔터"
        Action.BACKSPACE -> "한 글자 지우기"
        Action.SPACE -> "띄어쓰기"
        Action.CHUNK_DELETE -> "덩어리 지우기"
        Action.RESYNC -> "한/영 맞추기"
    }

val Action.description: String
    get() = when (this) {
        Action.SEND -> "쓴 글씨를 PC로 보내요"
        Action.ENTER -> "줄을 바꾸거나 입력을 마쳐요"
        Action.BACKSPACE -> "한 글자를 지워요. 보내기 전이면 마지막 획만 지워요"
        Action.SPACE -> "빈칸을 하나 넣어요"
        Action.CHUNK_DELETE -> "방금 보낸 덩어리를 통째로 지워요"
        Action.RESYNC -> "한글이 영어로 나오면 한/영 상태를 맞춰요"
    }

val Action.haptic: HapticPattern
    get() = when (this) {
        Action.SEND -> HapticPattern.SENT
        Action.ENTER -> HapticPattern.ENTER
        Action.BACKSPACE -> HapticPattern.BACKSPACE
        Action.SPACE -> HapticPattern.SPACE
        Action.CHUNK_DELETE -> HapticPattern.CHUNK_DELETE
        Action.RESYNC -> HapticPattern.RESYNC
    }

/** 화면 낭독기용 햅틱 표기. */
val HapticPattern.spoken: String
    get() = when (this) {
        HapticPattern.SENT -> "짧게 한 번"
        HapticPattern.ENTER -> "짧게 두 번"
        HapticPattern.SPACE -> "아주 짧게 한 번"
        HapticPattern.BACKSPACE -> "아주 짧게 두 번"
        HapticPattern.CHUNK_DELETE -> "길게 한 번"
        HapticPattern.RESYNC -> "짧게 세 번"
        HapticPattern.RECOGNITION_FAILED -> "짧게 한 번, 길게 한 번"
        HapticPattern.CONNECTED -> "길게 한 번, 짧게 한 번"
        HapticPattern.DISCONNECTED, HapticPattern.NOT_CONNECTED -> "길게 두 번"
    }

/** 햅틱 표기(design-system §7.3). 동작 기준이라 재매핑해도 같다. */
val HapticPattern.notation: String
    get() = when (this) {
        HapticPattern.SENT -> "·"
        HapticPattern.ENTER -> "··"
        HapticPattern.SPACE -> "˙"
        HapticPattern.BACKSPACE -> "˙˙"
        HapticPattern.CHUNK_DELETE -> "—"
        HapticPattern.RESYNC -> "···"
        HapticPattern.RECOGNITION_FAILED -> "·—"
        HapticPattern.CONNECTED -> "—·"
        HapticPattern.DISCONNECTED, HapticPattern.NOT_CONNECTED -> "— —"
    }

fun GestureMapping.gesturesFor(action: Action): List<Gesture> =
    Gesture.MAPPABLE.filter { actions[it] == action }

/** "두 번 톡 · 네 번 톡"처럼 한 줄로(A3). 없으면 "(없음)". */
fun GestureMapping.gestureNames(action: Action): String =
    gesturesFor(action).joinToString(" · ") { it.label }.ifEmpty { "(없음)" }

fun ModelState.progressLabel(): String? = when (this) {
    ModelState.Checking -> "인식 모델 확인 중"
    is ModelState.Downloading -> "인식 모델 받는 중 · $completed/$total 완료"
    else -> null
}

/** E16: 기기 종류로 보아 Windows PC 가 아닐 수 있음. 모름(UNKNOWN)이면 안내하지 않는다(hid.md §3.4). */
val com.ouor.pocketboard.core.hid.HostDevice.mayNotBeWindows: Boolean
    get() = kind == com.ouor.pocketboard.core.hid.HostKind.PHONE ||
        kind == com.ouor.pocketboard.core.hid.HostKind.AUDIO_VIDEO || kind == com.ouor.pocketboard.core.hid.HostKind.OTHER

const val NOT_WINDOWS_NOTE = "Windows 외 기기일 수 있어 동작이 보장되지 않아요"

/** 연결 상태 배지(C1). [label]은 배지·타일·알림이 같은 단어를 쓴다(design-system §9). */
enum class BadgeKind { Connected, Connecting, Retrying, Idle, Problem, NotSetUp }

data class Badge(val kind: BadgeKind, val label: String)

fun HidState.badge(hasHosts: Boolean): Badge = when (this) {
    is HidState.Connected -> Badge(BadgeKind.Connected, "연결됨")
    is HidState.Connecting, HidState.Registering -> Badge(BadgeKind.Connecting, "연결 중…")
    is HidState.Reconnecting ->
        if (repairLikely) Badge(BadgeKind.Problem, "다시 페어링 필요") else Badge(BadgeKind.Retrying, "다시 연결하는 중")
    HidState.BluetoothOff -> Badge(BadgeKind.Problem, "폰 블루투스 꺼짐")
    is HidState.Unavailable -> Badge(BadgeKind.Problem, if (why == HidState.Why.NO_PERMISSION) "권한 필요" else "연결할 수 없음")
    HidState.Ready, HidState.Stopped -> if (hasHosts) Badge(BadgeKind.Idle, "연결 안 됨") else Badge(BadgeKind.NotSetUp, "설정 필요")
}

/** 홈·페어링에 쓰는 한 줄 이유(integration §4.1). */
fun HidState.reason(): String? = when (this) {
    is HidState.Reconnecting ->
        if (repairLikely) "다시 페어링이 필요할 수 있어요" else "PC 블루투스가 켜져 있는지 확인해 주세요"
    HidState.BluetoothOff -> "폰 블루투스가 꺼져 있어요"
    is HidState.Unavailable -> when (why) {
        HidState.Why.NO_PERMISSION -> "근처 기기 권한이 필요해요"
        HidState.Why.NO_HID_SUPPORT -> "이 폰은 블루투스 키보드 역할을 쓸 수 없어요"
        HidState.Why.OCCUPIED_BY_OTHER_APP -> "다른 블루투스 키보드 앱을 종료해 주세요"
        HidState.Why.STACK_NOT_ANSWERING -> "폰 블루투스를 껐다 켜 보세요"
    }
    else -> null
}

/** 마지막 연결 시각: 오늘이면 시각, 어제면 "어제 시각", 그 전이면 날짜. */
fun android.content.Context.whenLabel(ms: Long): String {
    val time = android.text.format.DateUtils.formatDateTime(this, ms, android.text.format.DateUtils.FORMAT_SHOW_TIME)
    return when {
        android.text.format.DateUtils.isToday(ms) -> time
        android.text.format.DateUtils.isToday(ms + android.text.format.DateUtils.DAY_IN_MILLIS) -> "어제 $time"
        else -> android.text.format.DateUtils.formatDateTime(this, ms, android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_NO_YEAR)
    }
}
