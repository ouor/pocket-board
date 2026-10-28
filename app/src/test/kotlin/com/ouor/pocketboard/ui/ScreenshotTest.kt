package com.ouor.pocketboard.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.ouor.pocketboard.core.gesture.GestureMapping
import com.ouor.pocketboard.core.hid.HidState
import com.ouor.pocketboard.core.hid.HostDevice
import com.ouor.pocketboard.core.hid.HostKind
import com.ouor.pocketboard.core.ink.ModelState
import com.ouor.pocketboard.ui.home.HomeScreen
import com.ouor.pocketboard.ui.home.HostsScreen
import com.ouor.pocketboard.ui.onboarding.ConnectionTestScreen
import com.ouor.pocketboard.ui.onboarding.PairingScreen
import com.ouor.pocketboard.ui.onboarding.TestStep
import com.ouor.pocketboard.ui.onboarding.TutorialScreen
import com.ouor.pocketboard.ui.onboarding.UnsupportedScreen
import com.ouor.pocketboard.ui.onboarding.WelcomeScreen
import com.ouor.pocketboard.ui.settings.LicensesScreen
import com.ouor.pocketboard.ui.settings.MappingScreen
import com.ouor.pocketboard.ui.theme.PocketTheme
import android.os.Looper
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sergio.sastre.composable.preview.scanner.android.AndroidComposablePreviewScanner

/**
 * QA 리뷰용 스크린샷(JVM, 기기 없음). 기록: `gradlew.bat :app:recordRoborazziDebug` → docs/qa/screenshots/.
 * 일반 `test` 에서는 그리기만 하고 파일은 쓰지 않는다(그리다 죽는 화면을 잡는 역할).
 *
 * 화면은 UI 개발자의 @Preview 를 그대로 부르고(이름으로 찾음), 프리뷰에 없는 상태만 같은 화면 함수에 가짜 값을 넣어 부른다.
 * 크기: Pixel 7(1080x2400, 420dpi) 세로. 앱은 다크 고정(PocketTheme).
 * 한글 글꼴: Robolectric 4.17 네이티브 그래픽(GraphicsMode.NATIVE)이 CJK 글꼴을 갖고 있어 별도 설정 없이 한글이 나온다(두부 없음 확인).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "ko-rKR-" + RobolectricDeviceQualifiers.Pixel7) // 날짜 표기도 한국어로
@OptIn(ExperimentalRoborazziApi::class)
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val laptop = HostDevice("00:11", "내 노트북", System.currentTimeMillis() - 3_600_000, HostKind.COMPUTER)
    private val tv = HostDevice("00:22", "거실 TV", System.currentTimeMillis() - 86_400_000, HostKind.AUDIO_VIDEO)
    private val desk = HostDevice("00:33", "사무실 PC", System.currentTimeMillis() - 7 * 86_400_000, HostKind.COMPUTER)

    private fun shot(name: String, content: @Composable () -> Unit) {
        captureRoboImage("$name.png") { content() }
    }

    /** UI 개발자의 @Preview(비공개 포함)를 이름으로 찾아 그린다. */
    private fun preview(name: String, method: String) {
        val p = previews[method] ?: error("@Preview $method 없음")
        shot(name) { p() }
    }

    /** 다이얼로그·시트는 별도 창이라 화면 전체를 찍는다. */
    private fun screen(name: String) = captureScreenRoboImage("$name.png")

    private fun content(block: @Composable () -> Unit) {
        compose.setContent { PocketTheme(block) }
    }

    // ---------- @Preview 그대로 ----------

    @Test
    fun previews() {
        listOf(
            "SC01_welcome" to "WelcomePreview",
            "SC02_unsupported_hid" to "UnsupportedPreview",
            "SC03_permission" to "PermissionPreview",
            "SC03_permission_denied" to "PermissionDeniedPreview",
            "SC04_model_downloading" to "ModelDownloadingPreview",
            "SC04_model_mobile_data_warning" to "ModelCellularPreview",
            "SC04_model_failed" to "ModelFailedPreview",
            "SC04_model_ready_from_settings" to "ModelReadySettingsPreview",
            "SC05_pairing_waiting" to "PairingWaitingPreview",
            "SC05_pairing_repair_bluetooth_off" to "PairingRepairPreview",
            "SC06_test_ready" to "TestReadyPreview",
            "SC06_test_asked" to "TestAskedPreview",
            "SC07_tutorial_page1" to "TutorialPreview",
            "SC08_practice" to "PracticePreview",
            "SC09_writing_idle_hint" to "WritingIdlePreview",
            "SC09_writing_ink_preview_on" to "WritingInkPreview",
            "SC09_writing_just_sent" to "WritingSentPreview",
            "SC09_writing_reconnecting" to "WritingReconnectingPreview",
            "SC09_writing_model_downloading" to "WritingModelPreview",
            "SC09_writing_talkback_blocked" to "TalkBackPreview",
            "SC10_home_connected" to "HomeConnectedPreview",
            "SC10_home_reconnecting_model_failed" to "HomeRetryingPreview",
            "SC10_home_empty_model_downloading" to "HomeEmptyPreview",
            "SC11_hosts_list_non_pc_warning" to "HostsPreview",
            "SC12_settings" to "SettingsPreview",
            "SC12_privacy" to "PrivacyPreview",
            "SC13_mapping" to "MappingPreview",
            "SC14_troubleshoot" to "TroubleshootPreview",
            "SC17_dev_options" to "DevOptionsPreview",
        ).forEach { (name, method) -> preview(name, method) }
    }

    /** 긴 화면은 끝까지(세로만 늘림). */
    @Test
    @Config(qualifiers = "+h2200dp")
    fun longScreens_full() {
        preview("SC12_settings_full", "SettingsPreview")
        preview("SC17_dev_options_full", "DevOptionsPreview")
    }

    // ---------- 프리뷰에 없는 상태(같은 화면 함수, 가짜 값) ----------

    @Test
    fun extraStates() {
        val none = {}
        shot("SC01_welcome_checking") { PocketTheme { WelcomeScreen(true, none, none) } }
        shot("SC02_unsupported_model") { PocketTheme { UnsupportedScreen(model = true, onClose = none) } }

        fun pairing(hid: HidState, newly: HostDevice? = null, waiting: Boolean = false, declined: Boolean = false, handover: String? = null, onboarding: Boolean = false) =
            @Composable { PocketTheme { PairingScreen(hid, newly, waiting, declined, 245, handover, "Galaxy S23", onboarding, false, null, none, none, none, none, none) } }
        shot("SC05_pairing_new_pc_handover", pairing(HidState.Connected(laptop), waiting = true, handover = laptop.name))
        shot("SC05_pairing_declined", pairing(HidState.Ready, declined = true, onboarding = true))
        shot("SC05_pairing_connected", pairing(HidState.Connected(desk), newly = desk, onboarding = true))
        shot("SC05_pairing_other_app_occupied", pairing(HidState.Unavailable(HidState.Why.OCCUPIED_BY_OTHER_APP)))

        fun test(step: TestStep) = @Composable { PocketTheme { ConnectionTestScreen(step, {}, true, null, none, none, none, {}, none, none) } }
        shot("SC06_test_not_connected", test(TestStep.NotConnected))
        shot("SC06_test_aborted", test(TestStep.Aborted))

        fun home(hid: HidState, hosts: List<HostDevice>) =
            @Composable { PocketTheme { HomeScreen(hid, hosts, ModelState.Ready, GestureMapping.DEFAULT, true, {}, none, none, none, none, none, none, {}) } }
        shot("SC10_home_disconnected", home(HidState.Ready, listOf(laptop)))
        shot("SC10_home_non_pc_host", home(HidState.Connected(tv), listOf(tv, laptop)))
        shot("SC10_home_bluetooth_off", home(HidState.BluetoothOff, listOf(laptop)))
        shot("SC10_home_repair_needed", home(HidState.Reconnecting(laptop, 6, true), listOf(laptop)))
    }

    // ---------- 다이얼로그·시트(눌러서 연다) ----------

    private fun hosts() = content { HostsScreen(HidState.Connected(laptop), listOf(laptop, tv, desk), {}, {}, { _, _ -> }, {}, {}, {}) }

    @Test
    fun sc11_hostSheet() {
        hosts()
        compose.onNodeWithText("거실 TV").performClick()
        compose.waitForIdle()
        screen("SC11_host_sheet")
    }

    @Test
    fun sc11_renameDialog() {
        hosts()
        compose.onNodeWithText("내 노트북").performClick()
        compose.onNodeWithText("이름 바꾸기").performClick()
        compose.mainClock.autoAdvance = false // 커서 깜빡임이 끝나지 않아 idle 을 기다리면 멈춘다
        repeat(20) { compose.mainClock.advanceTimeBy(100); shadowOf(Looper.getMainLooper()).idle() }
        screen("SC11_host_rename_dialog")
    }

    @Test
    fun sc11_deleteConfirm() {
        hosts()
        compose.onNodeWithText("거실 TV").performClick()
        compose.onNodeWithText("목록에서 삭제").performClick()
        compose.waitForIdle()
        screen("SC11_host_delete_confirm")
    }

    @Test
    fun sc06_symptomSheet() {
        content { ConnectionTestScreen(TestStep.Asked, {}, true, null, {}, {}, {}, {}, {}, {}) }
        compose.onNodeWithText("아니요").performClick()
        compose.waitForIdle()
        screen("SC06_test_symptom_sheet")
    }

    @Test
    fun sc07_tutorialLastPage() {
        content { TutorialScreen(GestureMapping.DEFAULT, true, true, true, {}, {}, {}, {}, {}) }
        repeat(4) { compose.onNodeWithText("다음").performClick(); compose.waitForIdle() }
        screen("SC07_tutorial_last_page_exit_tip")
    }

    /** 목록은 비동기로 읽으므로 항목이 보일 때까지 기다린다. */
    @Test
    fun sc12_licenses() {
        content { LicensesScreen {} }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Apache", substring = true).fetchSemanticsNodes().isNotEmpty() }
        screen("SC12_licenses")
    }

    @Test
    fun sc13_gestureSheet() {
        content { MappingScreen(GestureMapping.DEFAULT, true, true, { _, _ -> }, {}, {}, {}) }
        compose.onAllNodesWithText("엔터").onFirst().performClick()
        compose.waitForIdle()
        screen("SC13_mapping_gesture_sheet")
    }

    private companion object {
        val previews by lazy {
            AndroidComposablePreviewScanner()
                .scanPackageTrees("com.ouor.pocketboard.ui")
                .includePrivatePreviews()
                .getPreviews()
                .associateBy { it.methodName }
        }
    }
}
