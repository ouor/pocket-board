package com.ouor.pocketboard.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.ouor.pocketboard.core.hid.HidState
import com.ouor.pocketboard.service.AppIntents
import com.ouor.pocketboard.ui.home.HomeRoute
import com.ouor.pocketboard.ui.home.HostsRoute
import com.ouor.pocketboard.ui.onboarding.ConnectionTestRoute
import com.ouor.pocketboard.ui.onboarding.ModelRoute
import com.ouor.pocketboard.ui.onboarding.PairingRoute
import com.ouor.pocketboard.ui.onboarding.PermissionRoute
import com.ouor.pocketboard.ui.onboarding.PracticeRoute
import com.ouor.pocketboard.ui.onboarding.TutorialRoute
import com.ouor.pocketboard.ui.onboarding.UnsupportedScreen
import com.ouor.pocketboard.ui.onboarding.WelcomeRoute
import com.ouor.pocketboard.ui.settings.DevOptionsRoute
import com.ouor.pocketboard.ui.settings.LicensesScreen
import com.ouor.pocketboard.ui.settings.MappingRoute
import com.ouor.pocketboard.ui.settings.PrivacyScreen
import com.ouor.pocketboard.ui.settings.SettingsRoute
import com.ouor.pocketboard.ui.settings.TroubleshootRoute
import com.ouor.pocketboard.ui.writing.WritingRoute
import kotlinx.serialization.Serializable

// 내비게이션 경로(screens §0 화면 지도). onboarding = 첫 실행 흐름 안인지(진행 막대·다음 화면이 다름).
@Serializable object Welcome
@Serializable data class Unsupported(val model: Boolean = false)
@Serializable data class Permission(val onboarding: Boolean)
/** [toWriting]: 앱 실행 시 모델이 없어 쓰기 대신 온 경우(A10). 받으면 쓰기 화면으로. */
@Serializable data class Model(val onboarding: Boolean, val toWriting: Boolean = false)
@Serializable data class Pairing(val onboarding: Boolean, val repair: Boolean = false)
@Serializable data class ConnectionTest(val onboarding: Boolean)
@Serializable data class Tutorial(val onboarding: Boolean)
@Serializable data class Practice(val onboarding: Boolean)
@Serializable object Writing
@Serializable object Home
@Serializable object Hosts
@Serializable object Settings
@Serializable object Privacy
@Serializable object Licenses
@Serializable object Mapping
/** [symptom]: 펼칠 증상(0 영어만, 1 깨짐, 2 아무것도), -1 = 없음. */
@Serializable data class Troubleshoot(val symptom: Int = -1)
@Serializable object DevOptions

/**
 * 첫 실행 이어하기(screens §0): 필수 단계(환영~연결 테스트) 중 마지막으로 들어간 화면부터 다시 연다.
 * 연결 테스트가 끝나면 onboardingDone 이 되므로 튜토리얼·연습(건너뛸 수 있는 단계)은 이어 열지 않는다.
 */
fun onboardingResume(step: Int): Any = when (step) {
    1 -> Permission(onboarding = true)
    2 -> Model(onboarding = true)
    3 -> Pairing(onboarding = true)
    4 -> ConnectionTest(onboarding = true)
    else -> Welcome
}

@Composable
private fun SaveOnboardingStep(vm: AppViewModel, step: Int) {
    LaunchedEffect(Unit) { if (vm.settings.value.onboardingStep != step) vm.update { it.copy(onboardingStep = step) } }
}

/** 홈·설정의 일시 결과(Snackbar). 쓰기 화면에서는 쓰지 않는다(D1). */
val LocalSnackbar = staticCompositionLocalOf { SnackbarHostState() }

/**
 * 앱 루트. [start]: 첫 경로(Welcome 또는 Home), [destination]: 인텐트 목적지(쓰기/홈), 바뀔 때마다 따라간다.
 */
@Composable
fun PocketApp(vm: AppViewModel, start: Any, destination: DestinationRequest?) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val settings by vm.settings.collectAsStateWithLifecycle()

    // 앱 진동이 꺼져 있으면 UI 터치 피드백도 끈다(design-system §7.1)
    val view = LocalView.current
    SideEffect { view.isHapticFeedbackEnabled = settings.haptics.enabled }

    LaunchedEffect(destination) {
        // 온보딩 중에는 따르지 않는다(타일·알림보다 첫 실행 흐름이 먼저)
        if (destination == null || !(start == Home || vm.settings.value.onboardingDone)) return@LaunchedEffect
        val hasHome = runCatching { nav.getBackStackEntry(Home) }.isSuccess
        if (hasHome) nav.popBackStack(Home, inclusive = false) else nav.navigate(Home) { popUpTo(nav.graph.id) { inclusive = true } }
        if (destination.dest == AppIntents.DEST_WRITING) nav.navigate(Writing)
    }
    BluetoothEnableOnce(vm, enabled = settings.onboardingDone)

    CompositionLocalProvider(LocalSnackbar provides snackbar) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            PocketNavHost(nav, vm, start)
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 88.dp))
        }
    }
}

/** 인텐트로 들어온 목적지. [id]로 같은 목적지의 반복 요청도 구분한다(onNewIntent). */
data class DestinationRequest(val dest: String, val id: Long)

/** E4: 폰 블루투스가 꺼져 있으면 앱 실행 시 1회만 켜기 요청(integration §3.5). */
@Composable
private fun BluetoothEnableOnce(vm: AppViewModel, enabled: Boolean) {
    val hid by vm.hid.collectAsStateWithLifecycle()
    var asked by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    LaunchedEffect(hid, enabled) {
        if (enabled && !asked && hid == HidState.BluetoothOff && context.hasRequiredPermissions()) {
            asked = true
            launcher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }
}

private fun NavHostController.finishOnboarding() {
    navigate(Home) { popUpTo(graph.id) { inclusive = true } }
    navigate(Writing)
}

@Composable
private fun PocketNavHost(nav: NavHostController, vm: AppViewModel, start: Any) {
    val activity = LocalActivity.current
    val back: () -> Unit = { nav.popBackStack() }
    val go: (Any) -> Unit = { nav.navigate(it) { launchSingleTop = true } }
    // 현재 화면을 [route]로 바꾼다(뒤로 가기에 남기지 않음)
    fun replace(route: Any) {
        val current = nav.currentDestination?.id ?: return
        nav.navigate(route) { popUpTo(current) { inclusive = true } }
    }

    NavHost(nav, startDestination = start) {
        composable<Welcome> {
            SaveOnboardingStep(vm, 0)
            WelcomeRoute(vm, onSupported = { go(Permission(onboarding = true)) }, onUnsupported = { go(Unsupported()) }, onPrivacy = { go(Privacy) })
        }
        composable<Unsupported> { UnsupportedScreen(model = it.toRoute<Unsupported>().model, onClose = { activity?.finish() }) }
        composable<Permission> {
            val r = it.toRoute<Permission>()
            if (r.onboarding) SaveOnboardingStep(vm, 1)
            PermissionRoute(vm, r.onboarding, onGranted = { if (r.onboarding) replace(Model(onboarding = true)) else back() }, onBack = back)
        }
        composable<Model> {
            val r = it.toRoute<Model>()
            if (r.onboarding) SaveOnboardingStep(vm, 2)
            ModelRoute(
                vm, r.onboarding, r.toWriting,
                onNext = { if (r.toWriting) replace(Writing) else go(Pairing(onboarding = true)) },
                onUnavailable = { replace(Unsupported(model = true)) },
                onBack = back,
            )
        }
        composable<Pairing> {
            val r = it.toRoute<Pairing>()
            if (r.onboarding) SaveOnboardingStep(vm, 3)
            PairingRoute(
                vm, r.onboarding, r.repair,
                onConnected = { if (r.onboarding) go(ConnectionTest(onboarding = true)) else replace(ConnectionTest(onboarding = false)) },
                onModel = { go(Model(onboarding = false)) },
                onPermission = { go(Permission(onboarding = false)) },
                onBack = back,
            )
        }
        composable<ConnectionTest> {
            val r = it.toRoute<ConnectionTest>()
            if (r.onboarding) SaveOnboardingStep(vm, 4)
            ConnectionTestRoute(
                vm, r.onboarding,
                onYes = { if (r.onboarding) go(Tutorial(onboarding = true)) else back() },
                onSymptom = { s -> go(Troubleshoot(s)) },
                onRepair = { go(Pairing(onboarding = r.onboarding, repair = true)) },
                onModel = { go(Model(onboarding = false)) },
                onBack = back,
            )
        }
        composable<Tutorial> {
            val r = it.toRoute<Tutorial>()
            TutorialRoute(
                vm, r.onboarding,
                onPractice = { go(Practice(r.onboarding)) },
                onDone = { if (r.onboarding) nav.finishOnboarding() else back() },
                onBack = back,
            )
        }
        composable<Practice> {
            val r = it.toRoute<Practice>()
            PracticeRoute(vm, r.onboarding, onDone = { if (r.onboarding) nav.finishOnboarding() else back() }, onBack = back)
        }
        composable<Writing> {
            WritingRoute(
                vm, onExit = back,
                onNeedModel = { replace(Model(onboarding = false, toWriting = true)) },
                onModelUnavailable = { replace(Unsupported(model = true)) },
                onNeedPermission = { replace(Permission(onboarding = false)) },
            )
        }
        composable<Home> { HomeRoute(vm, go) }
        composable<Hosts> { HostsRoute(vm, go, back) }
        composable<Settings> { SettingsRoute(vm, go, back) }
        composable<Privacy> { PrivacyScreen(back) }
        composable<Licenses> { LicensesScreen(back) }
        composable<Mapping> { MappingRoute(vm, back) }
        composable<Troubleshoot> { TroubleshootRoute(vm, it.toRoute<Troubleshoot>().symptom, go, back) }
        composable<DevOptions> { DevOptionsRoute(vm, back) }
    }
}
