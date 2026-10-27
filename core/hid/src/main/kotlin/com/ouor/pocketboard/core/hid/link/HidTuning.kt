package com.ouor.pocketboard.core.hid.link

/**
 * 실기기에서만 정할 수 있는 타이밍·재시도 수치(A27). 근거는 docs/research/hid.md §2.8, §2.3.
 * 값은 모두 초안이다. 실측 후 이 파일만 고친다.
 */
internal data class HidTuning(
    // 키 하나 = 다운 리포트 → keyDownMs → 전체 해제 리포트 → keyGapMs
    val keyDownMs: Long = 10,
    val keyGapMs: Long = 10,
    // 연결 직후 호스트가 리포트를 버리는 시간(whisper-hid CONNECT_SETTLE_MS). 이 뒤에 Connected(A24)
    val settleMs: Long = 1_500,
    // 링크 깨우기용 빈 리포트 간격(settle·유휴 웨이크 중)
    val pulseIntervalMs: Long = 100,
    // 마지막 리포트 뒤 이만큼 조용했으면 첫 키 전에 깨운다
    val idleWakeAfterMs: Long = 5_000,
    val idleWakeMs: Long = 1_000,
    val longIdleAfterMs: Long = 60_000,
    val longIdleWakeMs: Long = 2_500,
    // warmUp() 뒤 이 시간 동안 keepAwakePulseMs 마다 빈 리포트를 보내 링크를 깨워 둔다(쓰는 중)
    val keepAwakeMs: Long = 10_000,
    val keepAwakePulseMs: Long = 2_000,
    // sendReport 가 false(스택 큐 가득)일 때
    val sendRetries: Int = 4,
    val sendRetryBackoffMs: Long = 20,
    // 해제 리포트는 놓치면 키가 무한 반복되므로 더 끈질기게(barcode #564)
    val releaseRetries: Int = 10,
    val releaseRetryBackoffMs: Long = 50,
    // 등록: 거절·무응답이 이만큼 반복되면 Unavailable
    val registerAttempts: Int = 3,
    val registerRetryBaseMs: Long = 300,
    val registerTimeoutMs: Long = 5_000,
    // connect() 뒤 콜백이 안 오면 실패로 본다
    val connectTimeoutMs: Long = 15_000,
    // 재연결 간격: 앞의 목록을 차례로, 그 뒤는 마지막 값. slowAfterMs 가 지나면 slowDelayMs
    val reconnectDelaysMs: List<Long> = listOf(2_000, 4_000, 8_000, 16_000, 30_000),
    val reconnectSlowAfterMs: Long = 5 * 60_000,
    val reconnectSlowDelayMs: Long = 60_000,
    // E10: 연속 실패가 이만큼이면 "다시 페어링이 필요할 수 있음"(A26)
    val repairFailureThreshold: Int = 5,
    // checkSupport() 가 등록 결과를 기다리는 최대 시간
    val supportProbeMs: Long = 20_000,
) {
    /** [attempt]번째(1부터) 재연결 시도 전 대기. [elapsedMs] = 첫 실패 후 지난 시간. */
    fun reconnectDelayMs(attempt: Int, elapsedMs: Long): Long = when {
        elapsedMs >= reconnectSlowAfterMs -> reconnectSlowDelayMs
        else -> reconnectDelaysMs[(attempt - 1).coerceIn(0, reconnectDelaysMs.lastIndex)]
    }

    /**
     * 첫 키 전에 링크를 깨워야 하는 시간(0 = 바로 보냄). whisper-hid 의 관찰을 따른다:
     * 연결 직후 settle 동안, 그리고 오래 조용했던 링크의 첫 리포트는 호스트에서 사라진다.
     */
    fun wakeBeforeSendMs(nowMs: Long, connectedAtMs: Long, lastReportAtMs: Long?): Long {
        val sinceConnect = nowMs - connectedAtMs
        if (sinceConnect < settleMs) return settleMs - sinceConnect
        val sinceReport = lastReportAtMs?.let { nowMs - it } ?: return longIdleWakeMs
        return when {
            sinceReport > longIdleAfterMs -> longIdleWakeMs
            sinceReport > idleWakeAfterMs -> idleWakeMs
            else -> 0
        }
    }
}
