package com.ouor.pocketboard.core.gesture.engine

import com.ouor.pocketboard.core.gesture.HapticPattern
import com.ouor.pocketboard.core.gesture.HapticStrength
import kotlin.math.roundToInt

/** VibrationEffect.createWaveform(timings, amplitudes, -1) 인자. 진폭 0 = 쉼. */
class Waveform(val timings: LongArray, val amplitudes: IntArray)

/**
 * 햅틱 토큰 → 파형(design-system §7, PRD §4.3). 수치는 [제안], 실기기에서 조정.
 * 의미는 개수·길이로만 나눈다. 세기는 F26 배율만 적용한다.
 */
object HapticWaveforms {
    private class Pulse(val ms: Long, val amp: Float)

    private val tick = Pulse(12, 0.35f)
    /** 진폭 제어가 없는 기기(ERM)에서 12ms 는 거의 안 느껴진다(design §7.4). */
    private val tickNoAmplitude = Pulse(20, 0.35f)
    private val short = Pulse(35, 0.7f)
    private val long = Pulse(150, 0.7f)
    private val gapTight = Pulse(50, 0f)
    private val gap = Pulse(90, 0f)
    private val gapWide = Pulse(160, 0f)

    fun of(pattern: HapticPattern, strength: HapticStrength, hasAmplitudeControl: Boolean): Waveform {
        val t = if (hasAmplitudeControl) tick else tickNoAmplitude
        val units = when (pattern) {
            HapticPattern.SENT -> listOf(short)
            HapticPattern.ENTER -> listOf(short, gap, short)
            HapticPattern.SPACE -> listOf(t)
            HapticPattern.BACKSPACE -> listOf(t, gapTight, t) // A8: 스페이스와 구분
            HapticPattern.CHUNK_DELETE -> listOf(long)
            HapticPattern.RESYNC -> listOf(short, gap, short, gap, short)
            HapticPattern.RECOGNITION_FAILED -> listOf(short, gap, long)
            HapticPattern.CONNECTED -> listOf(long, gap, short)
            HapticPattern.DISCONNECTED, HapticPattern.NOT_CONNECTED -> listOf(long, gapWide, long)
        }
        // 진폭 제어가 없으면 0 아닌 진폭은 기기 기본 세기로 켜진다. 배율은 의미 없음.
        val scale = if (!hasAmplitudeControl) 1f else when (strength) {
            HapticStrength.WEAK -> 0.55f
            HapticStrength.MEDIUM -> 1f
            HapticStrength.STRONG -> 1.4f
        }
        return Waveform(
            timings = units.map { it.ms }.toLongArray(),
            amplitudes = units.map { if (it.amp == 0f) 0 else (it.amp * scale * 255).roundToInt().coerceIn(1, 255) }.toIntArray(),
        )
    }
}
