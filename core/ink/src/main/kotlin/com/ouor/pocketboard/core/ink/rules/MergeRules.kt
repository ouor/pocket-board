package com.ouor.pocketboard.core.ink.rules

import com.ouor.pocketboard.core.common.rule.Rule

/** 단계 C: 합쳐 인식한 결과가 완성 음절 1개면 원래 한 글자였다고 본다 → 합친다. 기본은 끊은 그대로(false). */
object SingleSyllableMergeRule : Rule<MergeInput, Boolean> {
    override val id = "merge/single-syllable"
    override fun apply(input: MergeInput): Boolean? {
        val top = input.ko.firstOrNull()?.trim() ?: return null
        return if (top.length == 1 && top[0].isHangulSyllable()) true else null
    }
}
