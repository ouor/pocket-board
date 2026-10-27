package com.ouor.pocketboard.core.hid.link

import com.ouor.pocketboard.core.common.key.KeyStep
import com.ouor.pocketboard.core.common.key.KeyUnit
import com.ouor.pocketboard.core.common.key.LedState
import com.ouor.pocketboard.core.common.rule.firstMatch
import com.ouor.pocketboard.core.hid.HidEvent
import com.ouor.pocketboard.core.hid.HidKeyboard
import com.ouor.pocketboard.core.hid.HidState
import com.ouor.pocketboard.core.hid.HidSupport
import com.ouor.pocketboard.core.hid.HostDevice
import com.ouor.pocketboard.core.hid.SendResult
import com.ouor.pocketboard.core.hid.rules.DefaultRules
import com.ouor.pocketboard.core.hid.rules.RepairContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * [HidKeyboard]의 순수 로직: 등록·재연결 상태기계, 유휴 타이머, 직렬 전송 큐.
 * 모든 필드는 [dispatcher](단일 스레드)에서만 읽고 쓴다. 공개 메서드와 [HidPort.Listener] 콜백은
 * 어느 스레드에서 불려도 그 디스패처로 옮겨 처리한다.
 *
 * @param clock 단조 증가 ms(타이머 계산용). 테스트는 가상 시간.
 * @param wallClock epoch ms([HostDevice.lastConnectedAt]용).
 */
internal class HidEngine(
    private val port: HidPort,
    private val store: HostStore,
    parentScope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val clock: () -> Long,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val tuning: HidTuning = HidTuning(),
) : HidKeyboard, HidPort.Listener {

    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + dispatcher)

    private val _state = MutableStateFlow<HidState>(HidState.Stopped)
    override val state: StateFlow<HidState> = _state.asStateFlow()
    private val _leds = MutableStateFlow<LedState?>(null)
    override val leds: StateFlow<LedState?> = _leds.asStateFlow()
    private val _events = MutableSharedFlow<HidEvent>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: Flow<HidEvent> = _events.asSharedFlow()
    private val _hosts = MutableStateFlow<List<HostDevice>>(emptyList())
    override val hosts: StateFlow<List<HostDevice>> = _hosts.asStateFlow()

    private val repairRules = DefaultRules.repair(tuning.repairFailureThreshold)
    private val hostsLoaded = CompletableDeferred<Unit>()
    private var known = emptyList<StoredHost>() // 진실의 출처. [hosts]는 이것을 HostDevice 로 보인 것

    // --- 등록 ---
    private var started = false
    private var proxyOpen = false
    private var registered = false
    private var everRegistered = false
    private var regFailures = 0
    private var regJob: Job? = null          // 등록 무응답 타이머 또는 재시도 대기

    // --- 링크 ---
    private var target: String? = null      // 연결하려는(또는 연결된) 호스트
    private var linked: String? = null      // HID 링크가 올라온 호스트
    private var announced = false           // linked 에 대해 HidEvent.Connected 를 냈는가
    private var dialing = false
    private val leaving = HashSet<String>() // 앱이 끊는 중인 호스트(끊김 콜백 무시)
    private var attempt = 0
    private var failures = 0
    private var bondLost = false
    private var reconnectSince = 0L
    private var dialJob: Job? = null        // 연결 시간 초과 또는 재시도 대기
    private var settleJob: Job? = null
    private var connectedAt = 0L

    // --- 전송 ---
    private val sendMutex = Mutex()
    private var sending = false
    private var sendGeneration = 0L
    private var linkEpoch = 0L              // 링크가 내려갈 때마다 증가. 빠른 재연결도 끊김으로 본다
    private var lastReportAt: Long? = null
    private var awakeUntil = 0L
    private var pulseJob: Job? = null

    // --- 유휴 ---
    private var idleTimeout: Duration? = 30.minutes
    private var lastActivity = 0L
    private var idleJob: Job? = null

    init {
        port.setListener(this)
        scope.launch {
            known = runCatching { store.load() }.getOrDefault(emptyList())
            _hosts.value = known.map { it.toDevice(kindOf(it.address)) }
            hostsLoaded.complete(Unit)
        }
    }

    // ===== 공개 API =====

    override fun phoneName(): String? = port.phoneName()

    override suspend fun checkSupport(): HidSupport = withContext(dispatcher) {
        if (everRegistered) return@withContext HidSupport.SUPPORTED
        val wasStopped = !started
        doStart()
        val settled = withTimeoutOrNull(tuning.supportProbeMs) { state.first { it !is HidState.Registering } }
        val result = when (settled) {
            null, HidState.BluetoothOff, HidState.Stopped -> HidSupport.UNKNOWN
            is HidState.Unavailable ->
                if (settled.why == HidState.Why.NO_HID_SUPPORT) HidSupport.UNSUPPORTED else HidSupport.UNKNOWN
            else -> HidSupport.SUPPORTED
        }
        if (wasStopped) doStop() // 조사만 하고 서비스 없이 등록을 남기지 않는다
        result
    }

    override fun start() { scope.launch { doStart() } }

    override fun stop() { scope.launch { doStop() } }

    override fun connect(host: HostDevice) {
        scope.launch {
            touch()
            if (linked == host.address) return@launch
            target = host.address
            resetFailures()
            if (!started || _state.value is HidState.Unavailable) return@launch doStart() // 등록되면 target 으로 건다
            if (!registered) return@launch
            linked?.let { old -> leaving += old; linkDown(); port.disconnect(old) }
            dial(host.address)
        }
    }

    override fun forget(host: HostDevice) {
        scope.launch {
            saveHosts(known.filter { it.address != host.address })
            if (target == host.address && linked == null) {
                target = null
                dialJob?.cancel()
                dialing = false
                if (registered) _state.value = HidState.Ready
            }
        }
    }

    override fun rename(host: HostDevice, name: String) {
        scope.launch {
            val alias = name.trim().ifEmpty { null }
            saveHosts(known.map { if (it.address == host.address) it.copy(alias = alias) else it })
            // 화면이 들고 있는 상태의 호스트 이름도 바꾼다
            when (val s = _state.value) {
                is HidState.Connecting -> if (s.host.address == host.address) setState(s.copy(host = hostOf(host.address)))
                is HidState.Connected -> if (s.host.address == host.address) setState(s.copy(host = hostOf(host.address)))
                is HidState.Reconnecting -> if (s.host.address == host.address) setState(s.copy(host = hostOf(host.address)))
                else -> Unit
            }
        }
    }

    override fun setIdleTimeout(timeout: Duration?) {
        scope.launch { idleTimeout = timeout; armIdle() }
    }

    override fun warmUp() {
        scope.launch {
            touch()
            val s = _state.value
            when {
                !started || s is HidState.Unavailable -> doStart() // A13: 유휴 해제 뒤 첫 획
                s is HidState.Reconnecting && !dialing -> { dialJob?.cancel(); target?.let { dial(it) } }
                linked != null -> keepAwake()
            }
        }
    }

    override fun noteActivity() { scope.launch { touch() } }

    override fun cancelSending() { scope.launch { sendGeneration++ } }

    override suspend fun send(units: List<KeyUnit>): SendResult = withContext(dispatcher) {
        val gen = sendGeneration
        sendMutex.withLock {
            touch()
            val address = linked ?: return@withLock SendResult.NotConnected
            if (gen != sendGeneration) return@withLock SendResult.Aborted(0, SendResult.Reason.CANCELLED)
            val link = Link(address, linkEpoch)
            sending = true
            try {
                sendUnits(link, units, gen)
            } finally {
                if (link.alive()) withContext(NonCancellable) { releaseAll(address) }
                sending = false
                touch()
            }
        }
    }

    // ===== 등록 =====

    private fun doStart() {
        if (started && _state.value !is HidState.Unavailable) return
        started = true
        touch()
        armIdle()
        if (!port.hasPermission()) return setState(HidState.Unavailable(HidState.Why.NO_PERMISSION))
        if (!port.bluetoothOn()) return setState(HidState.BluetoothOff)
        regFailures = 0
        register()
    }

    private fun register() {
        regJob?.cancel()
        setState(HidState.Registering)
        if (!proxyOpen) {
            if (!port.openProxy()) return registerFailed(if (everRegistered) HidState.Why.STACK_NOT_ANSWERING else HidState.Why.NO_HID_SUPPORT)
            armRegisterTimeout()
            return // onProxy(true) 에서 registerApp
        }
        registerApp()
    }

    private fun registerApp() {
        if (!port.registerApp()) return registerFailed(HidState.Why.OCCUPIED_BY_OTHER_APP)
        armRegisterTimeout()
    }

    private fun armRegisterTimeout() {
        regJob?.cancel()
        regJob = scope.launch {
            delay(tuning.registerTimeoutMs)
            registerFailed(HidState.Why.STACK_NOT_ANSWERING)
        }
    }

    /** 거절·무응답. 정해진 횟수까지 백오프로 다시 시도하고, 넘으면 [HidState.Unavailable]. */
    private fun registerFailed(why: HidState.Why) {
        regJob?.cancel()
        port.close()
        proxyOpen = false
        registered = false
        regFailures++
        if (regFailures >= tuning.registerAttempts) return setState(HidState.Unavailable(why))
        val wait = tuning.registerRetryBaseMs shl (regFailures - 1)
        regJob = scope.launch {
            delay(wait)
            if (started && port.bluetoothOn()) register()
        }
    }

    override fun onProxy(connected: Boolean) {
        scope.launch {
            if (!started) return@launch
            if (connected) {
                proxyOpen = true
                regJob?.cancel()
                registerApp()
            } else {
                // 블루투스 서비스가 내려감. 등록·링크 모두 사라진다
                proxyOpen = false
                registered = false
                dropLink(unexpected = true)
                if (port.bluetoothOn()) register() else setState(HidState.BluetoothOff)
            }
        }
    }

    override fun onAppStatus(registered: Boolean, plugged: String?) {
        scope.launch {
            if (!started) return@launch
            regJob?.cancel()
            if (registered) {
                this@HidEngine.registered = true
                everRegistered = true
                regFailures = 0
                afterRegistered(plugged)
            } else if (this@HidEngine.registered) {
                // 등록 뒤에 스택이 등록을 잃음: 처음부터 다시
                this@HidEngine.registered = false
                dropLink(unexpected = true)
                regFailures = 0
                register()
            } else {
                registerFailed(HidState.Why.OCCUPIED_BY_OTHER_APP)
            }
        }
    }

    private suspend fun afterRegistered(plugged: String?) {
        hostsLoaded.await()
        val up = port.linkedHosts()
        // plugged 는 다른 프로필(차량 오디오 등)일 수 있어 HID 링크가 있을 때만 믿는다
        val already = plugged?.takeIf { it in up } ?: up.firstOrNull()
        if (already != null) return linkUp(already)
        val t = target ?: known.firstOrNull()?.address
        if (t == null) return setState(HidState.Ready)
        resetFailures()
        dial(t)
    }

    // ===== 연결 =====

    private fun dial(address: String) {
        target = address
        dialJob?.cancel()
        for (other in port.linkedHosts()) if (other != address) { leaving += other; port.disconnect(other) }
        dialing = true
        val host = hostOf(address)
        setState(if (attempt == 0) HidState.Connecting(host) else HidState.Reconnecting(host, attempt, repairLikely()))
        if (!port.connect(address)) return dialFailed(address)
        dialJob = scope.launch {
            delay(tuning.connectTimeoutMs)
            dialFailed(address)
        }
    }

    private fun dialFailed(address: String) {
        if (address != target || linked != null) return
        dialJob?.cancel()
        dialing = false
        failures++
        attempt++
        if (reconnectSince == 0L) reconnectSince = clock()
        setState(HidState.Reconnecting(hostOf(address), attempt, repairLikely()))
        dialJob = scope.launch {
            delay(tuning.reconnectDelayMs(attempt, clock() - reconnectSince))
            if (started && registered && linked == null && target == address) dial(address)
        }
    }

    override fun onConnectionState(address: String, state: LinkState) {
        scope.launch {
            if (!started || !registered) return@launch
            when (state) {
                // 한 번에 호스트 하나. 새로 올라온 링크(선택한 호스트, 새로 페어링한 호스트)가 이긴다
                LinkState.CONNECTED -> if (linked != address) {
                    linked?.let { old -> leaving += old; port.disconnect(old); linkDown() }
                    linkUp(address)
                }
                LinkState.DISCONNECTED -> when {
                    leaving.remove(address) -> Unit
                    address == linked -> {
                        dropLink(unexpected = true)
                        resetFailures()
                        attempt = 1
                        reconnectSince = clock()
                        dial(address)
                    }
                    address == target && linked == null -> dialFailed(address)
                }
                else -> Unit
            }
        }
    }

    private fun linkUp(address: String) {
        dialJob?.cancel()
        dialing = false
        leaving.remove(address)
        linked = address
        target = address
        resetFailures()
        announced = false
        connectedAt = clock()
        lastReportAt = null
        _leds.value = null
        val alias = known.firstOrNull { it.address == address }?.alias
        saveHosts(listOf(StoredHost(address, port.deviceName(address), alias, wallClock())) + known.filter { it.address != address })
        val host = hostOf(address)
        setState(HidState.Connecting(host))
        settleJob?.cancel()
        settleJob = scope.launch {
            pulseUntil(address, connectedAt + tuning.settleMs) // 안정화 동안 링크를 깨워 둔다
            if (linked != address) return@launch
            announced = true
            setState(HidState.Connected(host))
            _events.tryEmit(HidEvent.Connected(host))
        }
    }

    /** 링크를 잃음. [unexpected]이고 사용자에게 연결을 알린 뒤였으면 끊김 이벤트(F16). */
    private fun dropLink(unexpected: Boolean) {
        if (linked == null) return
        val wasAnnounced = announced
        linkDown()
        if (unexpected && wasAnnounced) _events.tryEmit(HidEvent.LostUnexpectedly)
    }

    private fun linkDown() {
        linked = null
        announced = false
        settleJob?.cancel()
        pulseJob?.cancel()
        linkEpoch++
        sendGeneration++ // 진행·대기 중 전송은 버린다(재연결 후 엉뚱한 창에 들어가지 않게)
        _leds.value = null
    }

    private fun resetFailures() {
        attempt = 0
        failures = 0
        bondLost = false
        reconnectSince = 0L
    }

    private fun repairLikely(): Boolean =
        repairRules.firstMatch(RepairContext(bondLost, failures)) { false }.value

    private fun kindOf(address: String) = hostKindOf(port.majorDeviceClass(address))

    private fun hostOf(address: String) =
        (known.firstOrNull { it.address == address } ?: StoredHost(address, port.deviceName(address), null, null))
            .toDevice(kindOf(address))

    private fun saveHosts(list: List<StoredHost>) {
        known = list
        _hosts.value = list.map { it.toDevice(kindOf(it.address)) }
        scope.launch { runCatching { store.save(list) } }
    }

    override fun onOutputReport(address: String, reportId: Int, data: ByteArray) {
        scope.launch {
            if (address != linked || (reportId != HidReports.KEYBOARD_ID && reportId != 0)) return@launch
            HidReports.parseLed(data)?.let { _leds.value = LedState(it) }
        }
    }

    override fun onBluetoothOn(on: Boolean) {
        scope.launch {
            if (!started) return@launch
            if (!on) {
                if (_state.value == HidState.BluetoothOff) return@launch
                dropLink(unexpected = true) // E4: 사용 중이면 E1과 같은 진동
                regJob?.cancel()
                dialJob?.cancel()
                dialing = false
                port.close()
                proxyOpen = false
                registered = false
                setState(HidState.BluetoothOff)
            } else if (_state.value == HidState.BluetoothOff) {
                regFailures = 0
                register()
            }
        }
    }

    /**
     * 다른 호스트와 연결된 채 새 호스트를 페어링했다. 스택이 HID 연결을 한 호스트만 받을 수 있으므로
     * 기존 링크를 비켜 새 호스트의 연결을 기다린다. 오지 않으면 기존 호스트로 돌아간다.
     */
    override fun onBonded(address: String) {
        scope.launch {
            val old = linked ?: return@launch
            if (!started || address == old || known.any { it.address == address }) return@launch
            leaving += old
            port.disconnect(old)
            linkDown() // 앱이 끊은 것이므로 LostUnexpectedly 없음
            target = null
            setState(HidState.Ready)
            dialJob?.cancel()
            dialJob = scope.launch {
                delay(tuning.connectTimeoutMs)
                if (started && registered && linked == null) { resetFailures(); dial(old) }
            }
        }
    }

    override fun onBondLost(address: String) {
        scope.launch {
            if (address != target) return@launch
            bondLost = true
            val s = _state.value
            if (s is HidState.Reconnecting) setState(s.copy(repairLikely = repairLikely()))
        }
    }

    // ===== 정지·유휴 =====

    private fun doStop() {
        started = false
        regJob?.cancel()
        dialJob?.cancel()
        idleJob?.cancel()
        dialing = false
        linked?.let { port.disconnect(it) } // 정지 뒤 콜백은 started=false 로 무시된다
        leaving.clear()
        linkDown()
        target = null
        registered = false
        port.close()
        proxyOpen = false
        resetFailures()
        setState(HidState.Stopped)
    }

    private fun touch() { lastActivity = clock() }

    private fun armIdle() {
        idleJob?.cancel()
        val timeout = idleTimeout?.inWholeMilliseconds ?: return
        if (!started) return
        idleJob = scope.launch {
            while (true) {
                val wait = lastActivity + timeout - clock()
                if (wait <= 0) break
                delay(wait)
            }
            _events.tryEmit(HidEvent.IdleDisconnected)
            doStop()
        }
    }

    // ===== 전송 =====

    /** 전송을 시작한 링크. 같은 주소로 다시 붙어도 epoch 가 다르면 다른 링크다. */
    private inner class Link(val address: String, val epoch: Long) {
        fun alive() = linked == address && linkEpoch == epoch
    }

    private suspend fun sendUnits(link: Link, units: List<KeyUnit>, gen: Long): SendResult {
        val wake = tuning.wakeBeforeSendMs(clock(), connectedAt, lastReportAt)
        if (wake > 0) pulseUntil(link.address, clock() + wake)
        var sent = 0
        for (unit in units) {
            if (!link.alive()) return SendResult.Aborted(sent, SendResult.Reason.DISCONNECTED)
            // 취소는 단위 경계에서만 받는다(글자 중간에서 멈추지 않게)
            if (gen != sendGeneration) return SendResult.Aborted(sent, SendResult.Reason.CANCELLED)
            for (step in unit.steps) {
                when (step) {
                    is KeyStep.Press -> press(link, step)?.let { return SendResult.Aborted(sent, it) }
                    is KeyStep.Pause -> delay(step.ms)
                    is KeyStep.AwaitCapsLock ->
                        withTimeoutOrNull(step.timeoutMs) { leds.first { it?.capsLock == step.capsLock } }
                }
            }
            sent++
        }
        return SendResult.Done(sent)
    }

    private suspend fun press(link: Link, step: KeyStep.Press): SendResult.Reason? {
        if (!sendReliably(link, HidReports.keyDown(step.modifiers, step.usage))) return failReason(link)
        delay(tuning.keyDownMs)
        if (!sendReliably(link, HidReports.KEY_UP)) return failReason(link)
        delay(tuning.keyGapMs)
        return null
    }

    private fun failReason(link: Link) =
        if (link.alive()) SendResult.Reason.CONGESTED else SendResult.Reason.DISCONNECTED

    private suspend fun sendReliably(link: Link, report: ByteArray): Boolean {
        repeat(tuning.sendRetries) {
            if (!link.alive()) return false
            if (port.sendReport(link.address, HidReports.KEYBOARD_ID, report)) {
                lastReportAt = clock()
                return true
            }
            delay(tuning.sendRetryBackoffMs)
        }
        return false
    }

    /** 놓치면 호스트에서 키가 계속 반복되므로 더 여러 번 시도한다. */
    private suspend fun releaseAll(address: String) {
        repeat(tuning.releaseRetries) {
            if (port.sendReport(address, HidReports.KEYBOARD_ID, HidReports.KEY_UP)) {
                lastReportAt = clock()
                return
            }
            delay(tuning.releaseRetryBackoffMs)
        }
    }

    /** [untilMs]까지 빈 리포트로 링크를 깨운다. 손실돼도 아무 키도 누르지 않은 상태라 해가 없다. */
    private suspend fun pulseUntil(address: String, untilMs: Long) {
        while (linked == address) {
            if (port.sendReport(address, HidReports.KEYBOARD_ID, HidReports.KEY_UP)) lastReportAt = clock()
            val remaining = untilMs - clock()
            if (remaining <= 0) return
            delay(minOf(tuning.pulseIntervalMs, remaining))
        }
    }

    /** 쓰는 동안(warmUp 뒤 keepAwakeMs) 링크가 잠들지 않게 드문드문 빈 리포트를 보낸다. */
    private fun keepAwake() {
        awakeUntil = clock() + tuning.keepAwakeMs
        if (pulseJob?.isActive == true) return
        val address = linked ?: return
        pulseJob = scope.launch {
            while (linked == address && clock() < awakeUntil) {
                val quiet = lastReportAt?.let { clock() - it } ?: Long.MAX_VALUE
                if (!sending && quiet >= tuning.keepAwakePulseMs &&
                    port.sendReport(address, HidReports.KEYBOARD_ID, HidReports.KEY_UP)
                ) lastReportAt = clock()
                delay(tuning.keepAwakePulseMs)
            }
        }
    }

    private fun setState(s: HidState) { _state.value = s }
}
