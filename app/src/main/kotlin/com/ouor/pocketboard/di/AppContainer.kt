package com.ouor.pocketboard.di

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.ouor.pocketboard.core.gesture.GestureMapping
import com.ouor.pocketboard.core.gesture.GestureMappingRepository
import com.ouor.pocketboard.core.gesture.Haptics
import com.ouor.pocketboard.core.gesture.android.DataStoreGestureMappingRepository
import com.ouor.pocketboard.core.gesture.android.VibratorHaptics
import com.ouor.pocketboard.core.gesture.engine.DefaultGestureRecognizer
import com.ouor.pocketboard.core.hid.HidKeyboard
import com.ouor.pocketboard.core.hid.SendResult
import com.ouor.pocketboard.core.hid.android.BluetoothHidKeyboard
import com.ouor.pocketboard.core.ink.InkModelManager
import com.ouor.pocketboard.core.ink.android.MlKitInkModelManager
import com.ouor.pocketboard.core.keymap.DefaultKeyInput
import com.ouor.pocketboard.core.keymap.KeyInput
import com.ouor.pocketboard.data.AppSettings
import com.ouor.pocketboard.data.DevLog
import com.ouor.pocketboard.data.SettingsRepository
import com.ouor.pocketboard.service.ConnectionController
import com.ouor.pocketboard.session.ConnectionFeedback
import com.ouor.pocketboard.session.WritingSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes

/**
 * 수동 DI(architecture R8). Application 이 하나 만들고 액티비티·서비스·타일이 같은 인스턴스를 쓴다.
 * UI 는 여기 공개 멤버(상태 StateFlow + 명령)만 쓴다. 목록은 docs/architecture/integration.md.
 */
class AppContainer(context: Context) {
    private val app = context.applicationContext

    /** 프로세스 수명. 메인 스레드: KeyInput 은 메인에서만 부른다(architecture §6). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settingsRepository = SettingsRepository(app, appScope)
    private val settings = settingsRepository.settings

    val hid: HidKeyboard = BluetoothHidKeyboard(app, appScope)
    val keyInput: KeyInput = DefaultKeyInput(settings.value.keymap)
    val models: InkModelManager = MlKitInkModelManager(app, appScope, settings.part { it.ink })
    val mappingRepository: GestureMappingRepository = DataStoreGestureMappingRepository(app)
    val mapping: StateFlow<GestureMapping> = mappingRepository.mapping.stateIn(appScope, SharingStarted.Eagerly, GestureMapping.DEFAULT)
    val devLog = DevLog(app, appScope, settings.part { it.devlogEnabled })
    private val vibrator = VibratorHaptics(app, settings.part { it.haptics })
    val haptics = Haptics { p -> vibrator.play(p); devLog.haptic(p) }
    val connection = ConnectionController(app, hid)
    private val feedback = ConnectionFeedback(haptics, keyInput)

    init {
        appScope.launch { hid.events.collect(feedback::onEvent) }
        appScope.launch { hid.state.collect(feedback::onState) }
        appScope.launch { hid.leds.collect(keyInput::onLeds) }
        appScope.launch { settings.map { it.keymap }.distinctUntilChanged().collect(keyInput::updateSettings) }
        appScope.launch {
            settings.map { it.idleTimeoutMin }.distinctUntilChanged()
                .collect { hid.setIdleTimeout(if (it <= 0) null else it.minutes) }
        }
        // 판단 2(A37): 첫 실행 흐름에서는 SC04 가 부르고, 그 뒤로는 앱 시작마다 여기서 부른다(받았으면 네트워크 없이 Ready)
        appScope.launch { settings.filter { it.onboardingDone }.collect { models.ensureDownloaded() } }
    }

    /** 쓰기 화면 ViewModel 이 하나 연다. 소멸 시 [WritingSession.close]. */
    fun openWritingSession(scope: CoroutineScope): WritingSession = WritingSession(
        hid = hid,
        keyInput = keyInput,
        recognizer = models.openRecognizer(),
        haptics = haptics,
        log = devLog,
        starter = connection,
        mapping = mapping,
        gestureConfig = settings.part { it.gesture },
        emptySendEnter = { settings.value.emptySendEnter },
        devlogEnabled = { settings.value.devlogEnabled },
        previewEnabled = settings.part { it.previewEnabled },
        newRecognizer = ::DefaultGestureRecognizer,
        scope = scope,
        now = SystemClock::uptimeMillis,
    )

    /** SC06 연결 테스트(A14). 메인에서 부른다. 쓰기 세션과 동시에 쓰지 않는다. */
    suspend fun sendConnectionTest(): SendResult {
        val plan = keyInput.text(CONNECTION_TEST_TEXT)
        val result = hid.send(plan.units)
        when (result) {
            is SendResult.Done -> keyInput.confirm(plan, result.unitsSent)
            is SendResult.Aborted -> keyInput.confirm(plan, result.unitsSent)
            SendResult.NotConnected -> Unit
        }
        return result
    }

    private fun <T> StateFlow<AppSettings>.part(f: (AppSettings) -> T): StateFlow<T> =
        map(f).distinctUntilChanged().stateIn(appScope, SharingStarted.Eagerly, f(value))

    companion object {
        const val CONNECTION_TEST_TEXT = "안녕 Hello 123"
    }
}

/** 런타임 권한(SC03, hid 명세 §6). [required]가 없으면 진행 불가, [optional]은 거부해도 진행(A12). */
object AppPermissions {
    val required: Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        else emptyArray()
    val optional: Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
}

class PocketboardApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

val Context.container: AppContainer get() = (applicationContext as PocketboardApp).container
