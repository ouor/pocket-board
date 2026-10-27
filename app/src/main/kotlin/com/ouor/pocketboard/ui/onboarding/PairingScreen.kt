package com.ouor.pocketboard.ui.onboarding

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ouor.pocketboard.core.hid.HidState
import com.ouor.pocketboard.core.hid.HostDevice
import com.ouor.pocketboard.core.ink.ModelState
import com.ouor.pocketboard.ui.AppViewModel
import com.ouor.pocketboard.ui.components.Badge
import com.ouor.pocketboard.ui.components.BadgeKind
import com.ouor.pocketboard.ui.components.OnboardingScaffold
import com.ouor.pocketboard.ui.components.PrimaryButton
import com.ouor.pocketboard.ui.components.StatusBadge
import com.ouor.pocketboard.ui.components.StepCard
import com.ouor.pocketboard.ui.components.TipCard
import com.ouor.pocketboard.ui.components.WarningCard
import com.ouor.pocketboard.ui.components.reason
import com.ouor.pocketboard.ui.theme.PocketTheme
import kotlinx.coroutines.delay

// ---------- SC05 페어링 안내(Windows) ----------

/** 검색 가능 요청 상태. 시스템 대화상자 결과(허용 시 초)로 남은 시간을 센다. */
private enum class Discover { NotAsked, Waiting, Declined }

@Composable
fun PairingRoute(
    vm: AppViewModel,
    onboarding: Boolean,
    repair: Boolean,
    onConnected: () -> Unit,
    onModel: () -> Unit,
    onPermission: () -> Unit,
    onBack: () -> Unit,
) {
    val hid by vm.hid.collectAsStateWithLifecycle()
    val model by vm.model.collectAsStateWithLifecycle()
    val phone = remember { vm.phoneName() ?: "이 폰" }
    // 들어올 때 이미 연결돼 있던 호스트(새 호스트 추가 중)는 "새로 연결됨"으로 치지 않는다
    val connectedAtEntry: String? = rememberSaveable { (vm.hid.value as? HidState.Connected)?.host?.address ?: "" }.ifEmpty { null }
    var discover by rememberSaveable { mutableStateOf(Discover.NotAsked) }
    var deadline by rememberSaveable { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    val discoverLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_CANCELED) discover = Discover.Declined
        else { discover = Discover.Waiting; deadline = SystemClock.elapsedRealtime() + r.resultCode * 1000L }
    }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    val requestDiscoverable = { discoverLauncher.launch(vm.discoverableIntent()) }

    LaunchedEffect(Unit) { vm.ensureStarted() }
    val registered = hid == HidState.Ready || hid is HidState.Connecting || hid is HidState.Reconnecting ||
        (hid is HidState.Connected && (hid as HidState.Connected).host.address == connectedAtEntry)
    LaunchedEffect(registered) {
        if (registered && discover == Discover.NotAsked) requestDiscoverable()
    }
    LaunchedEffect(hid) {
        val h = hid
        if (h is HidState.Unavailable && h.why == HidState.Why.NO_PERMISSION) onPermission()
        if (h is HidState.Connected && h.host.address != connectedAtEntry) { delay(1000); onConnected() }
    }
    LaunchedEffect(discover) {
        while (discover == Discover.Waiting) { now = SystemClock.elapsedRealtime(); delay(1000) }
    }
    val remainingSec = ((deadline - now) / 1000).coerceAtLeast(0)

    PairingScreen(
        hid = hid,
        newlyConnected = (hid as? HidState.Connected)?.host?.takeIf { it.address != connectedAtEntry },
        waiting = discover == Discover.Waiting && remainingSec > 0,
        remainingSec = remainingSec,
        phone = phone,
        onboarding = onboarding,
        repair = repair,
        model = if (onboarding) model else null,
        onModel = onModel,
        onWaitAgain = requestDiscoverable,
        onEnableBluetooth = { enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
        onRetry = vm::ensureStarted,
        onBack = onBack,
    )
}

@Composable
fun PairingScreen(
    hid: HidState,
    newlyConnected: HostDevice?,
    waiting: Boolean,
    remainingSec: Long,
    phone: String,
    onboarding: Boolean,
    repair: Boolean,
    model: ModelState?,
    onModel: () -> Unit,
    onWaitAgain: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    OnboardingScaffold(
        title = if (repair) "다시 페어링해 주세요" else "PC에서 키보드를 추가해 주세요",
        step = if (onboarding) 4 else null,
        onBack = onBack,
        model = model,
        onModelClick = onModel,
        bottom = {
            when {
                newlyConnected != null -> StatusBadge(Badge(BadgeKind.Connected, "연결됐어요 · ${newlyConnected.name}"))
                hid == HidState.BluetoothOff -> {
                    WarningCard("폰 블루투스가 꺼져 있어요")
                    PrimaryButton("블루투스 켜기", onEnableBluetooth)
                }
                hid is HidState.Unavailable -> {
                    WarningCard(
                        if (hid.why == HidState.Why.OCCUPIED_BY_OTHER_APP) "다른 앱이 키보드 연결을 쓰고 있어요. 그 앱을 닫아 주세요."
                        else hid.reason() ?: "",
                    )
                    if (hid.why != HidState.Why.NO_HID_SUPPORT) PrimaryButton("다시 시도", onRetry)
                }
                waiting -> Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusBadge(Badge(BadgeKind.Connecting, "PC를 기다리는 중 · ${remainingSec / 60}:${"%02d".format(remainingSec % 60)}"))
                }
                hid == HidState.Registering || hid == HidState.Stopped -> StatusBadge(Badge(BadgeKind.Connecting, "준비 중…"))
                else -> {
                    StatusBadge(Badge(BadgeKind.Idle, "찾지 못했어요"))
                    PrimaryButton("다시 기다리기", onWaitAgain)
                }
            }
            Text("PC 목록에 보일 이름: $phone", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    ) {
        WarningCard("PC에 한국어 입력기가 있어야 한글이 들어가요", title = "한국어 입력기 확인")
        if (repair) TipCard("PC의 블루투스 목록에서 \"$phone\"을 먼저 제거해 주세요.")
        StepCard(1, "Windows 설정을 열어요", "시작 버튼 → 설정(톱니바퀴)", Icons.Rounded.Settings, "설정")
        StepCard(2, "블루투스 및 장치 → 장치 추가", "왼쪽 메뉴에서 블루투스 및 장치를 고른 뒤 장치 추가를 눌러요", Icons.Rounded.Bluetooth, "장치 추가")
        StepCard(3, "\"블루투스\"를 골라요", "추가할 장치 종류 중 맨 위 항목이에요", Icons.Rounded.BluetoothSearching, "블루투스")
        StepCard(4, "목록에서 \"$phone\"을 눌러요", "연결되면 폰이 —· 하고 진동해요", Icons.Rounded.Laptop, phone)
    }
}

@Preview(heightDp = 900)
@Composable
private fun PairingWaitingPreview() = PocketTheme {
    PairingScreen(HidState.Ready, null, true, 272, "Galaxy S23", true, false, ModelState.Downloading(1, 2), {}, {}, {}, {}, {})
}

@Preview(heightDp = 900)
@Composable
private fun PairingRepairPreview() = PocketTheme {
    PairingScreen(HidState.BluetoothOff, null, false, 0, "Galaxy S23", false, true, null, {}, {}, {}, {}, {})
}
