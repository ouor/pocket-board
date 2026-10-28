package com.ouor.pocketboard.ui.settings

import android.content.Intent
import android.text.format.Formatter
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ouor.pocketboard.core.gesture.GestureConfig
import com.ouor.pocketboard.core.gesture.engine.GestureRanges
import com.ouor.pocketboard.core.ink.InkSettings
import com.ouor.pocketboard.core.ink.SegmentationMode
import com.ouor.pocketboard.core.ink.rules.InkConfig
import com.ouor.pocketboard.core.keymap.CommitMethod
import com.ouor.pocketboard.core.keymap.HostImeState
import com.ouor.pocketboard.core.keymap.ImeMode
import com.ouor.pocketboard.core.keymap.ImeToggleKey
import com.ouor.pocketboard.core.keymap.LatinMethod
import com.ouor.pocketboard.data.AppSettings
import com.ouor.pocketboard.data.DevLogStats
import com.ouor.pocketboard.data.InkRanges
import com.ouor.pocketboard.ui.AppViewModel
import com.ouor.pocketboard.ui.components.ChoiceSheet
import com.ouor.pocketboard.ui.components.PageScaffold
import com.ouor.pocketboard.ui.components.RowKind
import com.ouor.pocketboard.ui.components.SettingGroup
import com.ouor.pocketboard.ui.components.SettingRow
import com.ouor.pocketboard.ui.components.StepperRow
import com.ouor.pocketboard.ui.components.WarningCard
import com.ouor.pocketboard.ui.theme.PocketTheme
import kotlin.math.pow
import kotlin.math.roundToLong

// ---------- SC17 개발자 옵션(B6, A21/A32, A36) ----------

private val IME_TOGGLE = listOf(ImeToggleKey.LANG1 to "한/영 키(LANG1)", ImeToggleKey.RIGHT_ALT to "오른쪽 Alt", ImeToggleKey.SHIFT_SPACE to "Shift + Space")
private val COMMIT = listOf(CommitMethod.TOGGLE_TWICE to "한/영 두 번", CommitMethod.SPACE_BACKSPACE to "스페이스 + 백스페이스")
private val LATIN = listOf(LatinMethod.CAPS_LOCK to "Caps Lock", LatinMethod.MODE_SWITCH to "영문 모드로 전환")
private val SEGMENTATION = listOf(
    SegmentationMode.RAW to "A · 원본 그대로",
    SegmentationMode.RULES to "B · 규칙 분할(기본)",
    SegmentationMode.HYPOTHESIS to "C · B + 합쳐 보기",
)

private fun <T> List<Pair<T, String>>.name(v: T) = first { it.first == v }.second

@Composable
fun DevOptionsRoute(vm: AppViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val stats by vm.devLogStats.collectAsStateWithLifecycle()
    val ime by vm.imeState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    DevOptionsScreen(
        settings, stats, ime,
        update = vm::update,
        onExport = {
            // 앱이 직접 보내지 않는다: 시스템 공유 시트로 사용자가 고른다(G6)
            vm.devLogExport()?.let { context.startActivity(Intent.createChooser(it, "기록 내보내기")) }
        },
        onClear = vm::devLogClear,
        onHide = {
            vm.update { it.copy(devOptionsVisible = false, devlogEnabled = false) } // 기록 파일은 유지
            onBack()
        },
        onBack = onBack,
    )
}

@Composable
fun DevOptionsScreen(
    s: AppSettings,
    stats: DevLogStats,
    ime: HostImeState,
    update: ((AppSettings) -> AppSettings) -> Unit,
    onExport: () -> Unit,
    onClear: () -> Unit,
    onHide: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var sheet by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    fun ink(f: (InkConfig) -> InkConfig) = update { it.copy(ink = it.ink.copy(config = f(it.ink.config))) }
    fun gesture(f: (GestureConfig) -> GestureConfig) = update { it.copy(gesture = f(it.gesture)) }
    val i = s.ink.config
    val g = s.gesture

    PageScaffold("개발자 옵션", onBack) {
        WarningCard("기록에는 쓴 획 모양이 들어가요. 폰 안에만 저장되고, 내보내기를 눌러야 밖으로 나가요.", title = "테스트용 기능이에요")

        SettingGroup("오판정 기록(F30)") {
            SettingRow("접촉 기록", RowKind.Toggle, checked = s.devlogEnabled, supporting = "쓰기 화면의 터치와 판정을 기록해요. 기록 중엔 알림에 [방금 오판정] 버튼이 생겨요") {
                update { it.copy(devlogEnabled = !it.devlogEnabled) }
            }
            SettingRow("기록", RowKind.Info, value = "${stats.entries}건 · ${Formatter.formatShortFileSize(context, stats.bytes)}")
            SettingRow("기록 내보내기", RowKind.Action, enabled = stats.entries > 0, onClick = onExport)
            SettingRow("기록 지우기", RowKind.Destructive, enabled = stats.entries > 0) { confirmClear = true }
        }

        SettingGroup("한/영 입력(A21)") {
            SettingRow("한/영 키", RowKind.Value, value = IME_TOGGLE.name(s.keymap.imeToggle)) { sheet = "ime" }
            SettingRow("조합 확정 방식", RowKind.Value, value = COMMIT.name(s.keymap.commit)) { sheet = "commit" }
            SettingRow("영문 입력 방식", RowKind.Value, value = LATIN.name(s.keymap.latin)) { sheet = "latin" }
            SettingRow(
                "지금 추정 상태", RowKind.Info,
                value = (if (ime.assumedMode == ImeMode.HANGUL) "한글" else "영어") +
                    " · Caps " + when (ime.capsLock) { true -> "켜짐"; false -> "꺼짐"; null -> "모름" },
            )
        }

        SettingGroup("글씨 인식(A36)") {
            SettingRow("겹쳐 쓰기 처리", RowKind.Value, value = SEGMENTATION.name(s.ink.segmentation)) { sheet = "seg" }
            Num("같은 자리 재시작 쉼", i.restartPauseMs.toDouble(), 25.0, InkRanges.restartPauseMs.l(), "ms", 0) { v -> ink { it.copy(restartPauseMs = v.toLong()) } }
            Num("긴 쉼 새 글자", i.newCharPauseMs.toDouble(), 25.0, InkRanges.newCharPauseMs.l(), "ms", 0) { v -> ink { it.copy(newCharPauseMs = v.toLong()) } }
            Num("왼쪽 위 되돌아감 영역", i.topLeftCornerRatio.toDouble(), 0.05, InkRanges.ratio.f(), "×", 2) { v -> ink { it.copy(topLeftCornerRatio = v.toFloat()) } }
            Num("왼쪽 위 되돌아감 거리", i.topLeftBackRatio.toDouble(), 0.05, InkRanges.topLeftBackRatio.f(), "×", 2) { v -> ink { it.copy(topLeftBackRatio = v.toFloat()) } }
            Num("재시작 겹침 비율", i.restartOverlapRatio.toDouble(), 0.05, InkRanges.ratio.f(), "", 2) { v -> ink { it.copy(restartOverlapRatio = v.toFloat()) } }
            Num("재시작 상자 여유", i.restartMarginRatio.toDouble(), 0.05, InkRanges.ratio.f(), "×", 2) { v -> ink { it.copy(restartMarginRatio = v.toFloat()) } }
            Num("오른쪽 새 글자 간격", i.rightOfGapRatio.toDouble(), 0.05, InkRanges.rightOfGapRatio.f(), "×", 2) { v -> ink { it.copy(rightOfGapRatio = v.toFloat()) } }
            Num("재배치 간격", i.relayoutGapRatio.toDouble(), 0.05, InkRanges.ratio.f(), "×", 2) { v -> ink { it.copy(relayoutGapRatio = v.toFloat()) } }
            Num("글자 크기 하한", i.minCharSize.toDouble(), 2.0, InkRanges.minCharSize.f(), "dp", 0) { v -> ink { it.copy(minCharSize = v.toFloat()) } }
            Num("C단계 추가 인식 상한", i.maxHypothesisCalls.toDouble(), 1.0, InkRanges.maxHypothesisCalls.let { it.first.toDouble()..it.last.toDouble() }, "회", 0) { v -> ink { it.copy(maxHypothesisCalls = v.toInt()) } }
            Num("겹친 반복 글자 획 수 배수", i.splitStrokeRatio.toDouble(), 0.25, InkRanges.splitStrokeRatio.f(), "×", 2) { v -> ink { it.copy(splitStrokeRatio = v.toFloat()) } }
            Num("겹친 반복 나누기 인식 상한", i.maxSplitCalls.toDouble(), 1.0, InkRanges.maxSplitCalls.let { it.first.toDouble()..it.last.toDouble() }, "회", 0) { v -> ink { it.copy(maxSplitCalls = v.toInt()) } }
        }

        SettingGroup("제스처 판정(A36)") {
            val r = GestureRanges
            Num("탭 이동 허용", g.tapSlopDp.toDouble(), 1.0, r.tapSlopDp.f(), "dp", 0) { v -> gesture { it.copy(tapSlopDp = v.toFloat()) } }
            Num("탭 최대 시간", g.tapMaxMs.toDouble(), 10.0, r.tapMaxMs.l(), "ms", 0) { v -> gesture { it.copy(tapMaxMs = v.toLong()) } }
            Num("연속 탭 간격", g.multiTapGapMs.toDouble(), 10.0, r.multiTapGapMs.l(), "ms", 0) { v -> gesture { it.copy(multiTapGapMs = v.toLong()) } }
            Num("연속 탭 반경", g.multiTapRadiusDp.toDouble(), 2.0, r.multiTapRadiusDp.f(), "dp", 0) { v -> gesture { it.copy(multiTapRadiusDp = v.toFloat()) } }
            Num("여러 손가락 착지 창", g.multiDownWindowMs.toDouble(), 10.0, r.multiDownWindowMs.l(), "ms", 0) { v -> gesture { it.copy(multiDownWindowMs = v.toLong()) } }
            Num("스와이프 최소 거리", g.swipeMinDp.toDouble(), 4.0, r.swipeMinDp.f(), "dp", 0) { v -> gesture { it.copy(swipeMinDp = v.toFloat()) } }
            Num("스와이프 축 비율", g.swipeAxisRatio.toDouble(), 0.1, r.swipeAxisRatio.f(), "", 1) { v -> gesture { it.copy(swipeAxisRatio = v.toFloat()) } }
            Num("손바닥 크기(0=끔)", g.palmTouchMajorDp.toDouble(), 1.0, r.palmTouchMajorDp.f(), "dp", 0) { v -> gesture { it.copy(palmTouchMajorDp = v.toFloat()) } }
            Num("가장자리 무시 띠(0=끔)", g.edgeBandDp.toDouble(), 2.0, r.edgeBandDp.f(), "dp", 0) { v -> gesture { it.copy(edgeBandDp = v.toFloat()) } }
            SettingRow("임계값 기본값으로", RowKind.Action, supporting = "인식·제스처 수치만 되돌려요") {
                update { it.copy(ink = InkSettings(it.ink.segmentation), gesture = GestureConfig()) }
            }
        }

        SettingGroup { SettingRow("개발자 옵션 숨기기", RowKind.Action, supporting = "기록도 꺼요. 기록 파일은 남아요", onClick = onHide) }
        Text("바뀐 값은 다음 전송·다음 접촉부터 적용돼요", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    when (sheet) {
        "ime" -> ChoiceSheet("한/영 키", IME_TOGGLE, s.keymap.imeToggle, { v -> update { it.copy(keymap = it.keymap.copy(imeToggle = v)) } }) { sheet = null }
        "commit" -> ChoiceSheet("조합 확정 방식", COMMIT, s.keymap.commit, { v -> update { it.copy(keymap = it.keymap.copy(commit = v)) } }) { sheet = null }
        "latin" -> ChoiceSheet("영문 입력 방식", LATIN, s.keymap.latin, { v -> update { it.copy(keymap = it.keymap.copy(latin = v)) } }) { sheet = null }
        "seg" -> ChoiceSheet("겹쳐 쓰기 처리", SEGMENTATION, s.ink.segmentation, { v -> update { it.copy(ink = it.ink.copy(segmentation = v)) } }) { sheet = null }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("기록을 지울까요?") },
            text = { Text("${stats.entries}건이 모두 지워지고 되돌릴 수 없어요.") },
            confirmButton = { TextButton({ confirmClear = false; onClear() }) { Text("지우기", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ confirmClear = false }) { Text("취소") } },
        )
    }
}

private fun ClosedFloatingPointRange<Float>.f() = start.toDouble()..endInclusive.toDouble()
private fun LongRange.l() = first.toDouble()..last.toDouble()

/** 수치 한 줄. 결과를 [decimals] 자리로 반올림하고 [min]..[max] 로 자른다(범위 밖이면 코어가 예외, ink.md §3.1). */
@Composable
private fun Num(title: String, value: Double, step: Double, range: ClosedFloatingPointRange<Double>, unit: String, decimals: Int, set: (Double) -> Unit) {
    val min = range.start
    val max = range.endInclusive
    fun next(d: Double): Double {
        val p = 10.0.pow(decimals)
        return ((value + d) * p).roundToLong().div(p).coerceIn(min, max)
    }
    StepperRow(
        title, "%.${decimals}f%s".format(value, unit),
        canDec = value > min, canInc = value < max,
        onDec = { set(next(-step)) }, onInc = { set(next(step)) },
    )
}

@Preview(heightDp = 2200)
@Composable
private fun DevOptionsPreview() = PocketTheme {
    DevOptionsScreen(AppSettings(devlogEnabled = true), DevLogStats(128, 2_100_000), HostImeState(ImeMode.HANGUL, false), {}, {}, {}, {}, {})
}
