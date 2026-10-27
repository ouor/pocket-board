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
    // 새 PC 추가(온보딩·다시 페어링 아님)에서는 목록에 이미 있던 PC 가 (자동 재)연결돼도 완료로 치지 않는다
    val knownAtEntry = rememberSaveable { ArrayList(vm.hosts.value.map { it.address }) }
    // hid.md §5.1: 다른 PC 와 연결된 채 새 PC 를 추가하면 HID 가 기존 연결을 넘겨준다(UI 는 아무것도 부르지 않음)
    val handoverFrom = rememberSaveable { (vm.hid.value as? HidState.Connected)?.host?.name.orEmpty() }
        .takeIf { it.isNotEmpty() && !onboarding && !repair }
    fun isNew(h: HidState) = h is HidState.Connected && (onboarding || repair || h.host.address !in knownAtEntry)
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
    val registered = hid == HidState.Ready || hid is HidState.Connecting || hid is HidState.Reconnecting || hid is HidState.Connected
    LaunchedEffect(registered) {
        if (registered && discover == Discover.NotAsked) requestDiscoverable()
    }
    LaunchedEffect(hid) {
        val h = hid
        if (h is HidState.Unavailable && h.why == HidState.Why.NO_PERMISSION) onPermission()
        if (isNew(h)) { delay(1000); onConnected() } // 연결 진동 —· 뒤 1초
    }
    LaunchedEffect(discover) {
        while (discover == Discover.Waiting) { now = SystemClock.elapsedRealtime(); delay(1000) }
    }
    val remainingSec = ((deadline - now) / 1000).coerceAtLeast(0)

    PairingScreen(
        hid = hid,
        newlyConnected = (hid as? HidState.Connected)?.host?.takeIf { isNew(hid) },
        waiting = discover == Discover.Waiting && remainingSec > 0,
        declined = discover == Discover.Declined,
        handoverFrom = handoverFrom,
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
    declined: Boolean,
    remainingSec: Long,
    handoverFrom: String? = null,
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
                    StatusBadge(Badge(BadgeKind.Idle, if (declined) "검색 허용이 필요해요" else "찾지 못했어요"))
                    if (declined) Text("허용해야 PC 목록에 폰이 보여요", style = MaterialTheme.typography.bodyMedium)
                    PrimaryButton("다시 기다리기", onWaitAgain)
                }
            }
            Text("PC 목록에 보일 이름: $phone", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    ) {
        WarningCard("PC에 한국어 입력기가 있어야 한글이 들어가요", title = "한국어 입력기 확인")
        if (handoverFrom != null) {
            TipCard("새 PC가 연결되면 지금 연결된 \"$handoverFrom\"와는 끊겨요. 15초 안에 새 PC가 연결되지 않으면 원래 PC로 다시 연결해요.", title = "지금 PC는 잠시 끊길 수 있어요")
        }
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
    PairingScreen(HidState.Ready, null, true, false, 272, null, "Galaxy S23", true, false, ModelState.Downloading(1, 2), {}, {}, {}, {}, {})
}

@Preview(heightDp = 900)
@Composable
private fun PairingRepairPreview() = PocketTheme {
    PairingScreen(HidState.BluetoothOff, null, false, false, 0, "내 노트북", "Galaxy S23", false, true, null, {}, {}, {}, {}, {})
}
