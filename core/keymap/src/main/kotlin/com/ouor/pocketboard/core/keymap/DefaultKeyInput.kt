package com.ouor.pocketboard.core.keymap

import com.ouor.pocketboard.core.common.key.KeyStep
import com.ouor.pocketboard.core.common.key.KeyUnit
import com.ouor.pocketboard.core.common.key.LedState
import com.ouor.pocketboard.core.common.rule.transform
import com.ouor.pocketboard.core.keymap.rules.DefaultRules
import com.ouor.pocketboard.core.keymap.rules.Token
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.Normalizer

/**
 * [KeyInput] 기본 구현(공개 진입점).
 *
 * - 연결 직후 한/영 추정은 [ImeMode.LATIN](Windows 새 창 기본 "A"). 틀리면 사용자가 재동기화한다(F06).
 * - Caps Lock 을 모를 때(LED 미수신)는 꺼져 있다고 본다.
 * - 덩어리: [text] 한 번 = 덩어리 하나. [space]는 맨 위 덩어리에 붙는다(덩어리 지우기가 "단어+공백"을 지움).
 *   [enter]는 경계(A22): 이력을 비운다.
 * - plan 은 만든 순서대로 하나씩 [confirm]한다. 각 단위 뒤의 상태를 미리 계산해 두고 confirm 은 그 값을 고를 뿐이다.
 */
class DefaultKeyInput(settings: KeymapSettings = KeymapSettings()) : KeyInput {
    private var settings = settings
    private var strategy: KeyStrategy = strategyFor(settings.os)
    private var state = Snapshot(INITIAL_MODE, caps = null, chunks = emptyList(), screen = "")
    private var ledKnown = false

    private val _imeState = MutableStateFlow(state.ime)
    override val imeState: StateFlow<HostImeState> = _imeState.asStateFlow()

    override fun text(text: String): KeyPlan {
        val tokens = Normalizer.normalize(text, Normalizer.Form.NFC).map(::tokenize)
        val ruled = DefaultRules.token.transform(tokens)
        val b = Builder(state)
        val origCaps = state.caps ?: false
        var first = true
        for (t in ruled.value) when (t) {
            is Token.Hangul -> {
                if (b.s.caps == true) b.add(strategy.setCapsLock(false), false, b.s.copy(caps = false))
                if (b.s.mode != ImeMode.HANGUL) b.add(strategy.toggleIme(settings), false, b.s.copy(mode = ImeMode.HANGUL))
                b.add(Dubeolsik.keys(t.c).map { strategy.letterKey(it) }, true, b.s.typed(t.c, first))
                first = false
            }
            is Token.Latin -> {
                val (steps, ime) = strategy.latinLetter(t.c, b.s.ime, settings)
                b.add(steps, true, b.s.copy(mode = ime.assumedMode, caps = ime.capsLock).typed(t.c, first))
                first = false
            }
            is Token.Symbol -> {
                b.add(strategy.symbol(t.c)!!, true, b.s.typed(t.c, first))
                first = false
            }
            Token.Commit -> b.add(strategy.commitComposition(settings), false, b.s)
            Token.RestoreCaps -> if ((b.s.caps ?: false) != origCaps) {
                b.add(strategy.setCapsLock(origCaps), false, b.s.copy(caps = origCaps))
            }
            is Token.Unmappable -> Unit // 규칙이 빠졌을 때도 보내지 않는다
        }
        val skipped = tokens.filterIsInstance<Token.Unmappable>().joinToString("") { it.c.toString() }
        return b.build(skipped, ruled.ruleIds)
    }

    override fun space(): KeyPlan = Builder(state).apply { add(strategy.symbol(' ')!!, true, s.typed(' ', newChunk = false)) }.build()

    override fun enter(): KeyPlan =
        Builder(state).apply { add(listOf(KeyStep.Press(ENTER)), true, s.typed('\n', newChunk = false).copy(chunks = emptyList())) }.build()

    override fun backspace(): KeyPlan = Builder(state).apply { add(listOf(KeyStep.Press(BACKSPACE)), false, s.erased()) }.build()

    override fun chunkDelete(): KeyPlan = Builder(state).apply {
        repeat(state.chunks.lastOrNull() ?: 0) { add(listOf(KeyStep.Press(BACKSPACE)), false, s.erased()) }
    }.build()

    override fun confirm(plan: KeyPlan, unitsSent: Int) {
        require(plan is Plan) { "plan 은 이 KeyInput 이 만든 것이어야 한다" }
        if (unitsSent <= 0 || plan.after.isEmpty()) return
        val next = plan.after[minOf(unitsSent, plan.after.size) - 1]
        // LED 를 받은 뒤에는 Caps Lock 은 LED 값이 우선한다.
        set(if (ledKnown) next.copy(caps = state.caps) else next)
    }

    override fun resync() = set(state.copy(mode = if (state.mode == ImeMode.HANGUL) ImeMode.LATIN else ImeMode.HANGUL))

    override fun onLeds(leds: LedState?) {
        ledKnown = leds != null
        set(state.copy(caps = leds?.capsLock))
    }

    override fun onHostConnected() {
        ledKnown = false
        set(Snapshot(INITIAL_MODE, caps = null, chunks = emptyList(), screen = ""))
    }

    override fun preContext(max: Int): String = state.screen.substringAfterLast('\n').takeLast(max)

    override fun updateSettings(settings: KeymapSettings) {
        this.settings = settings
        strategy = strategyFor(settings.os)
    }

    private fun set(s: Snapshot) {
        state = s
        _imeState.value = s.ime
    }

    private fun tokenize(c: Char): Token = when {
        Dubeolsik.isHangul(c) -> Token.Hangul(c)
        c in 'a'..'z' || c in 'A'..'Z' -> Token.Latin(c)
        strategy.symbol(c) != null -> Token.Symbol(c)
        else -> Token.Unmappable(c)
    }

    private companion object {
        val INITIAL_MODE = ImeMode.LATIN
        const val ENTER = 0x28
        const val BACKSPACE = 0x2A

        fun strategyFor(os: HostOs): KeyStrategy = when (os) {
            HostOs.WINDOWS -> WindowsKeyStrategy()
        }
    }
}

/** 한/영 추정 + 덩어리 이력(맨 위 = 마지막, 값 = 남은 글자 수) + 화면 추정 텍스트(preContext 용). */
internal data class Snapshot(val mode: ImeMode, val caps: Boolean?, val chunks: List<Int>, val screen: String) {
    val ime get() = HostImeState(mode, caps)

    fun typed(c: Char, newChunk: Boolean) = copy(
        chunks = if (newChunk || chunks.isEmpty()) chunks + 1 else chunks.dropLast(1) + (chunks.last() + 1),
        screen = (screen + c).takeLast(SCREEN_MAX),
    )

    fun erased() = copy(
        chunks = chunks.lastOrNull()?.let { n -> if (n > 1) chunks.dropLast(1) + (n - 1) else chunks.dropLast(1) } ?: chunks,
        screen = screen.dropLast(1),
    )

    private companion object {
        const val SCREEN_MAX = 256 // preContext 는 20자 남짓만 쓴다
    }
}

internal class Plan(
    override val units: List<KeyUnit>,
    override val skipped: String,
    override val ruleIds: List<String>,
    /** units[i] 를 끝까지 보낸 뒤의 상태. */
    val after: List<Snapshot>,
) : KeyPlan

private class Builder(var s: Snapshot) {
    private val units = ArrayList<KeyUnit>()
    private val after = ArrayList<Snapshot>()

    fun add(steps: List<KeyStep>, countsAsChar: Boolean, next: Snapshot) {
        units += KeyUnit(steps, countsAsChar)
        after += next
        s = next
    }

    fun build(skipped: String = "", ruleIds: List<String> = emptyList()) = Plan(units, skipped, ruleIds, after)
}
