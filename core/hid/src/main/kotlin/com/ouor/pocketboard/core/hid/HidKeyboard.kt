package com.ouor.pocketboard.core.hid

import com.ouor.pocketboard.core.common.key.KeyUnit
import com.ouor.pocketboard.core.common.key.LedState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

/**
 * 블루투스 HID 키보드(+마우스 컬렉션, B1). 프로세스에 하나. 수명은 :app 의 포그라운드 서비스가 쥔다.
 *
 * 스레딩: 모든 BT 콜백과 전송은 내부 단일 스레드에서 직렬 처리한다. 메서드는 어느 스레드에서 불러도 된다.
 * 오류: 예외를 밖으로 던지지 않는다. 상태([state])와 결과([SendResult])로만 알린다.
 * 진동은 하지 않는다. [events]를 :app 이 진동 패턴으로 바꾼다.
 */
interface HidKeyboard {
    val state: StateFlow<HidState>

    /** null = 연결 후 호스트가 아직 LED 리포트를 보내지 않음(모름). */
    val leds: StateFlow<LedState?>

    /** 일회성 알림(진동·안내용). 구독자가 없을 때 생긴 것은 버려도 된다. */
    val events: Flow<HidEvent>

    /** pocketboard 로 연결한 적 있는 호스트(F23), 마지막 연결 순. 첫 항목이 자동 재연결 대상(F02). */
    val hosts: StateFlow<List<HostDevice>>

    /** 호스트 목록에 보이는 폰의 블루투스 이름(A23). 권한이 없으면 null. */
    fun phoneName(): String?

    /** 첫 실행 SC02 분기(E5). 등록을 시도해 볼 수 있으므로 suspend. */
    suspend fun checkSupport(): HidSupport

    /** 등록 + 마지막 호스트 자동 재연결. 이미 시작했으면 아무것도 안 함. 포그라운드에서 부른다. */
    fun start()

    /** [연결 끊기]·유휴 해제와 같은 정리: 연결 해제 + 등록 해제. 진동 이벤트 없음. */
    fun stop()

    /** 다른 호스트와 연결돼 있으면 끊고 [host]로 연결(F23). */
    fun connect(host: HostDevice)

    /** 앱 목록에서만 지운다(A26). 폰의 bond 는 건드리지 않는다. */
    fun forget(host: HostDevice)

    /**
     * 호스트 별칭(F31, P2). 이후 [hosts]·[HidState]의 [HostDevice.name]은 별칭을 쓴다.
     * 빈 문자열이면 별칭을 지우고 기기 이름으로 돌아간다. 기본 구현은 아무것도 하지 않는다(하위 호환).
     */
    fun rename(host: HostDevice, name: String) {}

    /** 무입력 자동 해제 시간(F15). null = 끄지 않음. */
    fun setIdleTimeout(timeout: Duration?)

    /** 쓰기 시작(첫 획) 신호. 끊겨 있으면 재연결을 시작하고(A13), 연결돼 있으면 링크를 깨워 둔다(hid.md §2.8). */
    fun warmUp()

    /** 전송 외의 활동(터치). 유휴 타이머를 리셋한다. */
    fun noteActivity()

    /**
     * 직렬 큐로 보낸다. 앞선 send 가 끝날 때까지 기다린다.
     * 미연결이면 아무것도 보내지 않고 즉시 [SendResult.NotConnected].
     * 끊김·취소 시 남은 단위는 버린다(재연결 후 엉뚱한 창에 들어가는 것 방지).
     */
    suspend fun send(units: List<KeyUnit>): SendResult

    /** 진행 중·대기 중 전송을 모두 취소한다. */
    fun cancelSending()
}

data class HostDevice(
    val address: String,
    /** 별칭이 있으면 별칭(F31), 없으면 호스트의 블루투스 이름. */
    val name: String,
    /** epoch ms. 연결한 적 없으면 null. */
    val lastConnectedAt: Long?,
    /** 블루투스 기기 클래스로 본 종류(E16). OS 는 알 수 없다(맥도 COMPUTER). */
    val kind: HostKind = HostKind.UNKNOWN,
)

/** 블루투스 major device class 기준. COMPUTER 가 아니면 "Windows 외 기기일 수 있음" 안내 대상. */
enum class HostKind { COMPUTER, PHONE, AUDIO_VIDEO, OTHER, UNKNOWN }

enum class HidSupport { SUPPORTED, UNSUPPORTED, UNKNOWN }

sealed interface HidState {
    /** 진행 불가 또는 사용자 조치가 필요한 상태. */
    data class Unavailable(val why: Why) : HidState
    data object BluetoothOff : HidState
    data object Registering : HidState
    /** 등록됨, 연결된 호스트 없음. 페어링 대기도 이 상태. */
    data object Ready : HidState
    data class Connecting(val host: HostDevice) : HidState
    /** 입력 가능(연결 후 안정화 시간이 지난 뒤). */
    data class Connected(val host: HostDevice) : HidState
    /** [repairLikely] = 폰 bond 가 풀렸거나 연속 실패가 규칙 기준을 넘음(E10). */
    data class Reconnecting(val host: HostDevice, val attempt: Int, val repairLikely: Boolean) : HidState
    /** 등록 해제됨(시작 전, [HidKeyboard.stop] 뒤, 유휴 해제 뒤). */
    data object Stopped : HidState

    enum class Why { NO_HID_SUPPORT, NO_PERMISSION, OCCUPIED_BY_OTHER_APP, STACK_NOT_ANSWERING }
}

sealed interface HidEvent {
    /** 입력 가능해진 시점(A24). 연결 진동 대상. */
    data class Connected(val host: HostDevice) : HidEvent
    /** 앱이 의도하지 않은 끊김(F16). 끊김 진동 대상. */
    data object LostUnexpectedly : HidEvent
    /** 유휴 자동 해제(F15). 진동 없음. 서비스 종료 신호. */
    data object IdleDisconnected : HidEvent
}

sealed interface SendResult {
    data class Done(val unitsSent: Int) : SendResult
    data class Aborted(val unitsSent: Int, val reason: Reason) : SendResult
    /** E1: 아무것도 보내지 않음. */
    data object NotConnected : SendResult

    enum class Reason { DISCONNECTED, CONGESTED, CANCELLED }
}
