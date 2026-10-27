package com.ouor.pocketboard.core.gesture.android

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.ouor.pocketboard.core.gesture.HapticPattern
import com.ouor.pocketboard.core.gesture.HapticSettings
import com.ouor.pocketboard.core.gesture.Haptics
import com.ouor.pocketboard.core.gesture.engine.HapticWaveforms
import kotlinx.coroutines.flow.StateFlow

/**
 * 진동의 유일한 출구. 앱 스위치([HapticSettings.enabled])만 따르고 시스템 "터치 진동" 설정은 따르지 않는다(A1):
 * View.performHapticFeedback 와 USAGE_TOUCH 를 쓰지 않는다.
 */
class VibratorHaptics(
    context: Context,
    private val settings: StateFlow<HapticSettings>,
) : Haptics {

    private val vibrator: Vibrator =
        if (Build.VERSION.SDK_INT >= 31) {
            context.applicationContext.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            context.applicationContext.getSystemService(Vibrator::class.java)
        }

    private val hasAmplitudeControl = vibrator.hasAmplitudeControl()

    override fun play(pattern: HapticPattern) {
        val s = settings.value
        if (!s.enabled || !vibrator.hasVibrator()) return
        val w = HapticWaveforms.of(pattern, s.strength, hasAmplitudeControl)
        val effect = VibrationEffect.createWaveform(w.timings, w.amplitudes, -1)
        if (Build.VERSION.SDK_INT >= 33) {
            // shortcut: USAGE_MEDIA 는 "미디어 진동" 세기 설정을 따른다. 무음·방해금지에서 꺼지면 실기기 결과로 usage 를 바꾼다.
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
        } else {
            vibrator.vibrate(effect)
        }
    }
}
