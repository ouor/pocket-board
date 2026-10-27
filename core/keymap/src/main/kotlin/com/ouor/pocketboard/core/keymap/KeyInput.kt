package com.ouor.pocketboard.core.keymap

import com.ouor.pocketboard.core.common.key.KeyUnit
import com.ouor.pocketboard.core.common.key.LedState
import kotlinx.coroutines.flow.StateFlow

/**
 * 텍스트·편집 동작 → 키 단위 변환과, 그 결과로 바뀌는 호스트 상태(한/영 추정, 덩어리 이력)의 주인.
 *
 * 순수 Kotlin. 스레드 안전하지 않다: :app 의 전송 직렬화 지점 한 곳에서만 부른다.
 * 사용 순서: plan 을 만든다([text] 등) → HID 로 보낸다 → 보낸 단위 수로 [confirm].
 * plan 을 만드는 것만으로는 상태가 바뀌지 않는다. [confirm]하지 않은 plan 은 버려도 된다.
 */
interface KeyInput {
    val imeState: StateFlow<HostImeState>

    /** 인식 결과 한 덩어리. 변환 불가 문자는 건너뛰고 [KeyPlan.skipped]에 남는다(E12). */
    fun text(text: String): KeyPlan

    fun space(): KeyPlan

    /** 엔터는 덩어리의 경계다(A22): 확정 후 이력을 비운다. */
    fun enter(): KeyPlan

    fun backspace(): KeyPlan

    /** 맨 위 덩어리의 남은 글자 수만큼 백스페이스. 이력이 없으면 빈 plan(E11). */
    fun chunkDelete(): KeyPlan

    /** [plan]의 앞에서부터 [unitsSent]개가 실제로 나갔다. 한/영 상태와 이력을 그만큼만 반영한다. */
    fun confirm(plan: KeyPlan, unitsSent: Int)

    /** 재동기화(F06, D3): 추정 한/영 상태만 뒤집는다. 아무것도 보내지 않는다. */
    fun resync()

    /** :core:hid 의 LED 리포트. null = 모름. */
    fun onLeds(leds: LedState?)

    /** 새 연결(재연결 포함). 이력을 비우고 한/영 추정을 초기값으로 되돌린다. */
    fun onHostConnected()

    /** 인식기 preContext(A19). 이번 연결에서 보낸 텍스트 중 화면에 남아 있다고 추정되는 마지막 [max]자. */
    fun preContext(max: Int = 20): String

    fun updateSettings(settings: KeymapSettings)
}

/** 보낼 키 단위 목록. 구현은 [KeyInput.confirm]에 필요한 정보를 더 들고 있다. */
interface KeyPlan {
    val units: List<KeyUnit>

    /** 키로 바꿀 수 없어 건너뛴 문자들. */
    val skipped: String

    /** 이 plan 을 만든 동안 결론을 낸 keymap 규칙 id(F30 기록용). */
    val ruleIds: List<String>
}

enum class ImeMode { HANGUL, LATIN }

/** [capsLock] null = LED 리포트를 아직 못 받음. */
data class HostImeState(val assumedMode: ImeMode, val capsLock: Boolean?)

/**
 * 호스트 OS·IME 별로 바뀌는 선택(A21). 기본값은 keymap 담당이 정하고, 실기기 판정 결과에 따라 바꾼다.
 * :app 이 설정(개발자 옵션)에 저장하므로 각 값은 고정 문자열 [id]를 가진다.
 */
data class KeymapSettings(
    val os: HostOs = HostOs.WINDOWS,
    val imeToggle: ImeToggleKey = ImeToggleKey.LANG1,
    val commit: CommitMethod = CommitMethod.TOGGLE_TWICE,
    val latin: LatinMethod = LatinMethod.CAPS_LOCK,
)

enum class HostOs(val id: String) { WINDOWS("windows") }

/** 한/영 토글 키. */
enum class ImeToggleKey(val id: String) { LANG1("lang1"), RIGHT_ALT("right_alt"), SHIFT_SPACE("shift_space") }

/** 조합 확정 방식(A21). */
enum class CommitMethod(val id: String) { TOGGLE_TWICE("toggle_twice"), SPACE_BACKSPACE("space_backspace") }

/** 영문 입력 방식. MODE_SWITCH = 추적 상태 기준으로 영문 모드로 바꾼 뒤 입력(Caps Lock 대체). */
enum class LatinMethod(val id: String) { CAPS_LOCK("caps_lock"), MODE_SWITCH("mode_switch") }
