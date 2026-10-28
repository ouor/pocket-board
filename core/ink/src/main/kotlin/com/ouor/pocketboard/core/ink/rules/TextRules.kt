package com.ouor.pocketboard.core.ink.rules

import com.ouor.pocketboard.core.common.rule.Rule

/**
 * T1: 인식기가 단어 앞에 붙인 공백을 지운다. 현장: 앞 문맥 때문에 ko 1순위가 " 테스트", " 요즘에 주식시장" 처럼 와서
 * 호스트에 공백이 하나 더 갔다. 사용자가 넣은 스페이스(InkItem.Space)는 단어 밖이라 영향 없고, 단어 안 공백은 그대로 둔다.
 */
object TrimLeadingRule : Rule<TextInput, TextInput> {
    override val id = "text/trim-leading"
    override fun apply(input: TextInput) =
        if (input.text.firstOrNull()?.isWhitespace() == true) input.copy(text = input.text.trimStart()) else null
}
