package com.ouor.pocketboard.core.keymap.rules

import com.ouor.pocketboard.core.common.rule.Rule
import com.ouor.pocketboard.core.keymap.Dubeolsik

/** 토큰화 결과. 규칙 체인(Token 단계, 변환형)의 입력이자 출력. */
internal sealed interface Token {
    /** 한글 음절 또는 호환 자모 단독. */
    data class Hangul(val c: Char) : Token
    data class Latin(val c: Char) : Token
    /** 숫자·기호·공백: IME 상태와 무관한 키. */
    data class Symbol(val c: Char) : Token
    data class Unmappable(val c: Char) : Token
    /** 조합 확정 동작(KeymapSettings.commit). */
    data object Commit : Token
    /** Caps Lock 을 덩어리 시작 때 상태로 되돌린다. */
    data object RestoreCaps : Token
}

internal typealias TokenRule = Rule<List<Token>, List<Token>>

/** 변환 불가 문자는 그 글자만 건너뛴다(D12, E12). 앞에 두어 뒤 규칙이 건너뛴 뒤의 이웃을 보게 한다. */
internal object SkipUnmappableRule : TokenRule {
    override val id = "keymap/skip-unmappable"
    override fun apply(input: List<Token>) =
        input.filterNot { it is Token.Unmappable }.takeIf { it.size != input.size }
}

/** 자모 단독이 바로 앞 한글과 합쳐질 수 있으면 사이에 확정(각ㅏ, ㄱㅅ, 고ㅏ). ㅋㅋ 는 그대로. */
internal object JamoBoundaryCommitRule : TokenRule {
    override val id = "keymap/jamo-boundary-commit"
    override fun apply(input: List<Token>): List<Token>? {
        val out = ArrayList<Token>(input.size)
        var changed = false
        for (t in input) {
            val prev = out.lastOrNull()
            if (t is Token.Hangul && !Dubeolsik.isSyllable(t.c) && prev is Token.Hangul && Dubeolsik.merges(prev.c, t.c)) {
                out += Token.Commit
                changed = true
            }
            out += t
        }
        return out.takeIf { changed }
    }
}

/** 덩어리가 한글로 끝나면 확정. 조합 중인 음절이 남으면 백스페이스가 자모만 지운다(research §2.6). */
internal object ChunkEndCommitRule : TokenRule {
    override val id = "keymap/chunk-end-commit"
    override fun apply(input: List<Token>) = (input + Token.Commit).takeIf { input.lastOrNull() is Token.Hangul }
}

/** 한글·영문은 Caps Lock 을 바꿀 수 있으므로 끝에서 사용자 원래 상태로 되돌린다(E14). 바뀌지 않았으면 실행 때 아무것도 안 보낸다. */
internal object CapsRestoreRule : TokenRule {
    override val id = "keymap/caps-restore"
    override fun apply(input: List<Token>) =
        (input + Token.RestoreCaps).takeIf { input.any { it is Token.Hangul || it is Token.Latin } }
}
