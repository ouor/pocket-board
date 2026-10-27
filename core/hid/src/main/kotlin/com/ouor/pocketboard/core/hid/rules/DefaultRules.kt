package com.ouor.pocketboard.core.hid.rules

import com.ouor.pocketboard.core.common.rule.Rule

/** E10 판정 입력: 재연결 대상 호스트의 실패 이력. */
internal data class RepairContext(val bondLost: Boolean, val consecutiveFailures: Int)

/** 폰 쪽 bond 가 풀렸으면 다시 페어링해야 한다. */
internal class BondNoneRule : Rule<RepairContext, Boolean> {
    override val id = "repair/bond-none"
    override fun apply(input: RepairContext) = if (input.bondLost) true else null
}

/** 호스트 꺼짐(E6)과 호스트에서 페어링 삭제(E10)는 콜백으로 구분되지 않는다. 연속 실패가 많으면 후자로 본다(A26). */
internal class ConsecutiveFailuresRule(private val threshold: Int) : Rule<RepairContext, Boolean> {
    override val id = "repair/consecutive-failures"
    override fun apply(input: RepairContext) = if (input.consecutiveFailures >= threshold) true else null
}

/**
 * hid 규칙 체인(architecture §9.2). 판정형, 기본값 false(재페어링 필요 없음).
 *
 * | id | 조건 | 결론 | 근거 |
 * |---|---|---|---|
 * | repair/bond-none | 대상 호스트의 bond 가 BOND_NONE 이 됨 | repairLikely | hid.md §2.5 |
 * | repair/consecutive-failures | 연속 연결 실패 ≥ N(기본 5) | repairLikely | A26 |
 */
internal object DefaultRules {
    fun repair(failureThreshold: Int): List<Rule<RepairContext, Boolean>> =
        listOf(BondNoneRule(), ConsecutiveFailuresRule(failureThreshold))
}
