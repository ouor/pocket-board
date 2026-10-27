package com.ouor.pocketboard.core.ink

import com.ouor.pocketboard.core.common.ink.InkItem
import com.ouor.pocketboard.core.common.ink.InkStroke
import kotlinx.coroutines.flow.StateFlow

/**
 * ML Kit Digital Ink 모델(ko, en) 관리와 인식기 생성. 프로세스에 하나.
 * ML Kit 타입은 이 모듈 밖으로 나가지 않는다. 초기화 실패(비GMS 등)는 예외가 아니라 [ModelState.Unavailable].
 */
interface InkModelManager {
    val state: StateFlow<ModelState>

    /** 없으면 받는다(조건 없음, 와이파이 강제 안 함). 실패 시 내부 재시도 후 [ModelState.Failed]. 여러 번 불러도 된다(재시도 버튼). */
    fun ensureDownloaded()

    /** 설정의 재다운로드: 지우고 다시 받는다. */
    suspend fun redownload()

    /** 쓰기·연습 화면 수명 동안 쓸 인식기. 화면을 떠날 때 [HandwritingRecognizer.close]. */
    fun openRecognizer(): HandwritingRecognizer
}

sealed interface ModelState {
    data object Checking : ModelState
    /** 진행률 API 가 없어 모델 단위 완료 수만 준다(A17). */
    data class Downloading(val completed: Int, val total: Int) : ModelState
    data object Ready : ModelState
    data class Failed(val reason: Reason) : ModelState
    /** Google Play 서비스 없음 등 인식 불가(E17, A20). */
    data object Unavailable : ModelState

    enum class Reason { NETWORK, OTHER }
}

interface HandwritingRecognizer : AutoCloseable {
    /** 첫 인식 지연(모델 압축 해제)을 화면 진입 때 미리 치른다. */
    suspend fun warmUp()

    /**
     * 전송 1회 분량을 인식한다. 메인 스레드를 막지 않는다.
     * [preContext]는 :core:keymap 의 전송 이력(A19)에서 온다.
     */
    suspend fun recognize(items: List<InkItem>, preContext: String): RecognitionOutcome
}

sealed interface RecognitionOutcome {
    /** [ruleIds]: 분할·후보 선택·후처리에서 결론을 낸 규칙 id(F30 기록용). */
    data class Text(val text: String, val ruleIds: List<String> = emptyList()) : RecognitionOutcome
    /** D9: 호출 측이 획을 버리고 실패 진동. */
    data object Empty : RecognitionOutcome
    /** E2: 모델이 준비되지 않음. */
    data object ModelNotReady : RecognitionOutcome
}

/**
 * 겹쳐 쓰기·이어 쓰기 글자 분할(확장 지점, ink 내부 계약). 1차 구현은 SegmentRule 체인.
 * 나중에 학습형 분할기로 바꿔도 재배치·인식 단계는 그대로다.
 */
fun interface Segmenter {
    /** 한 단어의 획들을 글자 묶음으로 나눈다. 획 순서는 유지한다. */
    fun segment(strokes: List<InkStroke>): List<List<InkStroke>>
}
