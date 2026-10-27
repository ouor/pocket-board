package com.ouor.pocketboard.core.common.rule

/**
 * 케이스 규칙 하나(F28, A19). "어떤 조건이면 어떻게 한다"를 클래스 하나로 표현한다.
 *
 * - [apply]는 조건이 맞지 않으면 null 을 돌려준다(판정형: 다음 규칙으로 / 변환형: 바꾸지 않음).
 * - 규칙은 다른 규칙의 결과를 알지 않는다. 의존하는 것은 목록 안의 순서뿐이다.
 * - 임계값은 생성자로 받는다(모듈의 Config 값 객체). 전역 상태를 읽지 않는다.
 * - [id]는 기록(F30)과 테스트에 남는 고정 문자열이다. 바꾸지 않는다. 형식: "<단계>/<이름>", 예 "classify/single-tap".
 */
interface Rule<in I, out O : Any> {
    val id: String
    fun apply(input: I): O?
}

/** 판정형 결과. [ruleId]가 null 이면 어떤 규칙도 결론을 내지 않아 기본값을 쓴 것이다. */
data class Verdict<out O>(val value: O, val ruleId: String?)

/** 변환형 결과. [ruleIds]는 실제로 값을 바꾼 규칙들(순서대로). */
data class Transformed<out T>(val value: T, val ruleIds: List<String>)

/** 판정형 체인: 처음 결론을 낸 규칙을 따른다. 아무도 결론을 내지 않으면 [default]. */
inline fun <I, O : Any> List<Rule<I, O>>.firstMatch(input: I, default: (I) -> O): Verdict<O> {
    for (rule in this) rule.apply(input)?.let { return Verdict(it, rule.id) }
    return Verdict(default(input), null)
}

/** 변환형 체인: 모든 규칙을 순서대로 적용한다. 각 규칙은 앞 규칙의 출력을 입력으로 받는다. */
fun <T : Any> List<Rule<T, T>>.transform(input: T): Transformed<T> {
    var value = input
    val applied = ArrayList<String>()
    for (rule in this) rule.apply(value)?.let { value = it; applied += rule.id }
    return Transformed(value, applied)
}
