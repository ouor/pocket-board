package com.ouor.pocketboard.ui.onboarding

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.ouor.pocketboard.core.hid.HidState
import com.ouor.pocketboard.core.hid.HidSupport
import com.ouor.pocketboard.di.AppPermissions
import com.ouor.pocketboard.ui.AppViewModel
import com.ouor.pocketboard.ui.components.Badge
import com.ouor.pocketboard.ui.components.BadgeKind
import com.ouor.pocketboard.ui.components.BlockedScaffold
import com.ouor.pocketboard.ui.components.OnboardingScaffold
import com.ouor.pocketboard.ui.components.PrimaryButton
import com.ouor.pocketboard.ui.components.RowKind
import com.ouor.pocketboard.ui.components.SecondaryButton
import com.ouor.pocketboard.ui.components.SettingGroup
import com.ouor.pocketboard.ui.components.SettingRow
import com.ouor.pocketboard.ui.components.StatusBadge
import com.ouor.pocketboard.ui.components.TipCard
import com.ouor.pocketboard.ui.hasRequiredPermissions
import com.ouor.pocketboard.ui.theme.PocketTheme
import com.ouor.pocketboard.ui.theme.Space
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---------- SC01 환영·개인정보 ----------

@Composable
fun WelcomeRoute(vm: AppViewModel, onSupported: () -> Unit, onUnsupported: () -> Unit, onPrivacy: () -> Unit) {
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    WelcomeScreen(
        checking = checking,
        onStart = {
            if (busy) return@WelcomeScreen
            busy = true
            scope.launch {
                val spinner = launch { delay(500); checking = true } // 0.5초 안에 끝나면 진행 표시 없음
                val support = vm.checkSupport()
                spinner.cancel()
                checking = false
                busy = false
                if (support == HidSupport.UNSUPPORTED) onUnsupported() else onSupported()
            }
        },
        onPrivacy = onPrivacy,
    )
}

@Composable
fun WelcomeScreen(checking: Boolean, onStart: () -> Unit, onPrivacy: () -> Unit) {
    OnboardingScaffold(
        title = "폰에 손글씨를 쓰면\nPC에 글자가 들어가요",
        step = 1,
        bottom = { PrimaryButton("시작하기", onStart, loading = checking) },
    ) {
        Icon(Icons.Rounded.Draw, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
        Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
            // B2: 절대 표현 없이 정확하게
            TipCard("쓴 글씨는 인터넷으로 보내지 않아요. PC로만 들어가요.", Icons.Rounded.Lock, title = "글씨 인식은 폰 안에서 해요")
            TipCard(
                "인식 모델을 받을 때와 Google 인식 도구가 사용 통계를 보낼 때만 써요.", Icons.Rounded.Wifi, title = "인터넷은 두 곳에만 써요",
                action = { TextButton(onClick = onPrivacy, contentPadding = PaddingValues(0.dp)) { Text("자세히 보기") } },
            )
            TipCard("블루투스 키보드로 연결돼요.", Icons.Rounded.Keyboard, title = "PC에 설치할 건 없어요")
        }
        Text("광고·가입·구독 없음", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------- SC02 기기 미지원 ----------

@Composable
fun UnsupportedScreen(model: Boolean, onClose: () -> Unit) {
    BlockedScaffold(
        title = if (model) "이 폰에서는 글씨 인식을 쓸 수 없어요" else "이 폰은 블루투스 키보드로 쓸 수 없어요",
        body = if (model) "글씨 인식에 Google Play 서비스가 필요한데 이 폰에는 없어요."
        else "폰 제조사가 이 기능을 막아 두어 다른 기기에 키보드로 연결할 수 없어요.",
        detail = "기기: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}",
        bottom = { PrimaryButton("닫기", onClose) },
    )
}

// ---------- SC03 권한 ----------

@Composable
fun PermissionRoute(vm: AppViewModel, onboarding: Boolean, onGranted: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    // 온보딩 이후에 들어왔다면 권한이 빠진 것이므로 바로 거부 상태(screens SC03)
    var denied by rememberSaveable { mutableStateOf(!onboarding && !context.hasRequiredPermissions()) }
    var asked by rememberSaveable { mutableStateOf(false) }
    var notificationsOn by remember { mutableStateOf(AppPermissions.optional.all(::granted)) }
    var done by remember { mutableStateOf(false) }

    fun proceed() {
        if (done) return
        done = true
        if (vm.hid.value == HidState.Unavailable(HidState.Why.NO_PERMISSION)) vm.ensureStarted() // integration §3.4
        onGranted()
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        asked = true
        notificationsOn = AppPermissions.optional.all(::granted)
        if (context.hasRequiredPermissions()) proceed() else denied = true // 알림 거부는 진행(A12)
    }
    LaunchedEffect(Unit) {
        if (onboarding && context.hasRequiredPermissions() && AppPermissions.optional.all(::granted)) proceed()
    }
    // 설정 앱에서 돌아오면 다시 확인
    LifecycleResumeEffect(denied) {
        if (denied && context.hasRequiredPermissions()) proceed()
        onPauseOrDispose {}
    }
    val canAskAgain = !asked || AppPermissions.required.any { activity?.shouldShowRequestPermissionRationale(it) == true }

    PermissionScreen(
        onboarding = onboarding,
        denied = denied,
        notificationsOn = notificationsOn,
        canAskAgain = canAskAgain,
        onRequest = { launcher.launch(AppPermissions.required + AppPermissions.optional) },
        onOpenSettings = {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        },
        onBack = onBack,
    )
}

@Composable
fun PermissionScreen(
    onboarding: Boolean,
    denied: Boolean,
    notificationsOn: Boolean,
    canAskAgain: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
) {
    OnboardingScaffold(
        title = if (denied) "권한이 있어야 쓸 수 있어요" else "블루투스만 꼭 필요해요",
        description = if (denied) "설정 > 권한 > 근처 기기에서 허용해 주세요" else "PC와 키보드로 연결하려면 근처 기기 권한이 필요해요",
        step = if (onboarding) 2 else null,
        onBack = onBack,
        bottom = {
            if (denied) {
                PrimaryButton("설정 열기", onOpenSettings)
                if (canAskAgain) SecondaryButton("다시 요청하기", onRequest)
            } else PrimaryButton("허용하기", onRequest)
        },
    ) {
        SettingGroup {
            SettingRow(
                "근처 기기(블루투스)", RowKind.Info, supporting = "PC와 키보드로 연결해요", icon = Icons.Rounded.Bluetooth,
                trailing = if (denied) ({ StatusBadge(Badge(BadgeKind.Problem, "거부됨")) }) else null,
            )
            if (AppPermissions.optional.isNotEmpty() || !onboarding) {
                SettingRow(
                    "알림 (선택)", RowKind.Info,
                    supporting = if (denied && !notificationsOn) "화면을 끄면 연결 상태를 알림으로 볼 수 없어요" else "화면을 꺼도 연결을 유지하고 있다고 알려 줘요",
                    icon = Icons.Rounded.Notifications,
                    trailing = if (denied) ({ StatusBadge(if (notificationsOn) Badge(BadgeKind.Connected, "허용됨") else Badge(BadgeKind.Idle, "꺼짐")) }) else null,
                )
            }
        }
    }
}

@Preview(heightDp = 720)
@Composable
private fun WelcomePreview() = PocketTheme { WelcomeScreen(false, {}, {}) }

@Preview(heightDp = 720)
@Composable
private fun UnsupportedPreview() = PocketTheme { UnsupportedScreen(model = false) {} }

@Preview(heightDp = 720)
@Composable
private fun PermissionPreview() = PocketTheme { PermissionScreen(true, false, false, true, {}, {}, {}) }

@Preview(heightDp = 720)
@Composable
private fun PermissionDeniedPreview() = PocketTheme { PermissionScreen(true, true, true, false, {}, {}, {}) }
