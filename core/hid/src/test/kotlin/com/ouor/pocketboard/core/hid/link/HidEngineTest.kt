package com.ouor.pocketboard.core.hid.link

import com.ouor.pocketboard.core.common.key.KeyModifiers
import com.ouor.pocketboard.core.common.key.KeyStep
import com.ouor.pocketboard.core.common.key.KeyUnit
import com.ouor.pocketboard.core.hid.HidEvent
import com.ouor.pocketboard.core.hid.HidState
import com.ouor.pocketboard.core.hid.HostDevice
import com.ouor.pocketboard.core.hid.HostKind
import com.ouor.pocketboard.core.hid.SendResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class HidEngineTest {

    private class FakePort : HidPort {
        lateinit var l: HidPort.Listener
        var registerAccept = true
        val reachable = mutableSetOf<String>()
        val linked = mutableListOf<String>()
        val reports = mutableListOf<ByteArray>()
        var failKeySends = 0
        var onKeyDown: (ByteArray) -> Unit = {}
        var closed = 0
        val keyDowns get() = reports.filter { r -> r.any { it != 0.toByte() } }

        override fun setListener(listener: HidPort.Listener) { l = listener }
        override fun hasPermission() = true
        override fun bluetoothOn() = true
        override fun phoneName() = "Phone"
        override fun openProxy(): Boolean { l.onProxy(true); return true }
        override fun registerApp(): Boolean { if (registerAccept) l.onAppStatus(true, null); return registerAccept }
        override fun close() { closed++ }
        override fun connect(address: String): Boolean {
            if (address in reachable) { linked += address; l.onConnectionState(address, LinkState.CONNECTED) }
            else l.onConnectionState(address, LinkState.DISCONNECTED)
            return true
        }
        override fun disconnect(address: String) { if (linked.remove(address)) l.onConnectionState(address, LinkState.DISCONNECTED) }
        override fun linkedHosts() = linked.toList()
        override fun deviceName(address: String) = "PC-$address"
        val classes = mutableMapOf<String, Int>()
        override fun majorDeviceClass(address: String) = classes[address]
        override fun sendReport(address: String, reportId: Int, data: ByteArray): Boolean {
            if (address !in linked) return false
            val isKey = data.any { it != 0.toByte() }
            if (isKey && failKeySends > 0) { failKeySends--; return false }
            reports += data.copyOf()
            if (isKey) onKeyDown(data)
            return true
        }
        fun drop(address: String) { linked.remove(address); l.onConnectionState(address, LinkState.DISCONNECTED) }
    }

    private class FakeStore(var saved: List<StoredHost>) : HostStore {
        override suspend fun load() = saved
        override suspend fun save(hosts: List<StoredHost>) { saved = hosts }
    }

    private val hostA = StoredHost("A", "PC-A", null, 1L)
    private fun press(usage: Int, mods: Int = 0) = KeyUnit(listOf(KeyStep.Press(usage, mods)), countsAsChar = true)

    private fun TestScope.engine(port: FakePort, hosts: List<StoredHost> = emptyList(), store: FakeStore = FakeStore(hosts)) = HidEngine(
        port, store, backgroundScope, StandardTestDispatcher(testScheduler),
        clock = { testScheduler.currentTime }, wallClock = { 1_000L },
    )

    private fun TestScope.events(e: HidEngine) = mutableListOf<HidEvent>().also { list ->
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { e.events.toList(list) }
    }

    /** 마지막 호스트 A 로 자동 재연결하고 안정화까지 마친 엔진. */
    private fun TestScope.connected(port: FakePort): HidEngine {
        port.reachable += "A"
        val e = engine(port, listOf(hostA))
        e.start()
        advanceTimeBy(2_000)
        return e
    }

    @Test fun `start reconnects last host and announces only after settle`() = runTest {
        val port = FakePort().apply { reachable += "A" }
        val e = engine(port, listOf(hostA))
        val ev = events(e)
        e.start()
        runCurrent()
        assertTrue(e.state.value is HidState.Connecting)
        assertEquals(emptyList<HidEvent>(), ev)
        advanceTimeBy(1_600)
        val host = HostDevice("A", "PC-A", 1_000L)
        assertEquals(HidState.Connected(host), e.state.value)
        assertEquals(listOf<HidEvent>(HidEvent.Connected(host)), ev)
        assertEquals(host, e.hosts.value.first())
    }

    @Test fun `no known host stays ready and send reports not connected`() = runTest {
        val e = engine(FakePort())
        e.start()
        runCurrent()
        assertEquals(HidState.Ready, e.state.value)
        assertEquals(SendResult.NotConnected, e.send(listOf(press(0x04))))
    }

    @Test fun `send presses each key and always ends released`() = runTest {
        val port = FakePort()
        val e = connected(port)
        val r = e.send(listOf(press(0x04), press(0x05, KeyModifiers.LEFT_SHIFT)))
        assertEquals(SendResult.Done(2), r)
        assertEquals(listOf(0x04, 0x05), port.keyDowns.map { it[2].toInt() })
        assertEquals(KeyModifiers.LEFT_SHIFT, port.keyDowns[1][0].toInt())
        assertTrue(port.reports.last().all { it == 0.toByte() })
    }

    @Test fun `link lost mid send counts only finished units`() = runTest {
        val port = FakePort()
        val e = connected(port)
        var downs = 0
        port.onKeyDown = { if (++downs == 2) port.drop("A") }
        val r = e.send(listOf(press(0x04), press(0x05), press(0x06)))
        assertEquals(SendResult.Aborted(1, SendResult.Reason.DISCONNECTED), r)
    }

    @Test fun `stack refusing reports aborts as congested and still releases`() = runTest {
        val port = FakePort()
        val e = connected(port)
        port.failKeySends = 100
        assertEquals(SendResult.Aborted(0, SendResult.Reason.CONGESTED), e.send(listOf(press(0x04))))
        assertTrue(port.reports.last().all { it == 0.toByte() })
    }

    @Test fun `await caps lock continues as soon as led arrives`() = runTest {
        val port = FakePort()
        val e = connected(port)
        port.onKeyDown = { e.onOutputReport("A", 1, byteArrayOf(0x02)) }
        val unit = KeyUnit(listOf(KeyStep.Press(0x39), KeyStep.AwaitCapsLock(true, 5_000)), countsAsChar = false)
        val before = testScheduler.currentTime
        assertEquals(SendResult.Done(1), e.send(listOf(unit)))
        assertTrue(testScheduler.currentTime - before < 1_000)
        assertEquals(true, e.leds.value?.capsLock)
    }

    @Test fun `unexpected drop vibrates once then retries until repair is likely`() = runTest {
        val port = FakePort()
        val e = connected(port)
        val ev = events(e)
        port.reachable.clear()
        port.drop("A")
        runCurrent()
        assertEquals(listOf<HidEvent>(HidEvent.LostUnexpectedly), ev)
        assertTrue((e.state.value as HidState.Reconnecting).repairLikely.not())
        advanceTimeBy(70_000) // 실패 5회: 즉시, +4s, +8s, +16s, +30s
        assertTrue((e.state.value as HidState.Reconnecting).repairLikely)
        assertEquals(1, ev.size)
    }

    @Test fun `idle timeout stops without a lost event`() = runTest {
        val port = FakePort()
        val e = connected(port)
        val ev = events(e)
        e.setIdleTimeout(1.minutes)
        advanceTimeBy(61_000)
        assertEquals(listOf<HidEvent>(HidEvent.IdleDisconnected), ev)
        assertEquals(HidState.Stopped, e.state.value)
        assertTrue(port.closed > 0)
    }

    @Test fun `led report from the connected host only`() = runTest {
        val e = connected(FakePort())
        e.onOutputReport("B", 1, byteArrayOf(0x02))
        runCurrent()
        assertEquals(null, e.leds.value)
        e.onOutputReport("A", 1, byteArrayOf(0x01, 0x02))
        runCurrent()
        assertEquals(true, e.leds.value?.capsLock)
    }

    @Test fun `registration refused repeatedly means another app holds hid`() = runTest {
        val e = engine(FakePort().apply { registerAccept = false })
        e.start()
        advanceTimeBy(5_000)
        assertEquals(HidState.Unavailable(HidState.Why.OCCUPIED_BY_OTHER_APP), e.state.value)
    }

    @Test fun `rename shows the alias everywhere and keeps it across reconnects`() = runTest {
        val port = FakePort().apply { reachable += "A"; classes["A"] = 0x0100 }
        val store = FakeStore(listOf(hostA))
        val e = engine(port, store = store)
        e.start()
        advanceTimeBy(2_000)
        e.rename(e.hosts.value.first(), " 내 노트북 ")
        runCurrent()
        assertEquals(HostDevice("A", "내 노트북", 1_000L, HostKind.COMPUTER), e.hosts.value.first())
        assertEquals("내 노트북", (e.state.value as HidState.Connected).host.name)
        assertEquals("내 노트북", store.saved.first().alias)
        port.drop("A") // 재연결해도 별칭 유지
        advanceTimeBy(2_000)
        assertEquals("내 노트북", e.hosts.value.first().name)
        e.rename(e.hosts.value.first(), "")
        runCurrent()
        assertEquals("PC-A", e.hosts.value.first().name)
    }

    @Test fun `pairing a new host while connected hands the link over quietly`() = runTest {
        val port = FakePort()
        val e = connected(port)
        val ev = events(e)
        e.onBonded("B") // 새 PC 와 페어링 완료
        runCurrent()
        assertEquals(HidState.Ready, e.state.value)
        port.reachable += "B"
        port.connect("B") // 새 PC 가 HID 연결을 걸어 옴
        advanceTimeBy(2_000)
        assertEquals("B", (e.state.value as HidState.Connected).host.address)
        assertEquals(listOf("B", "A"), e.hosts.value.map { it.address })
        assertEquals(listOf<HidEvent>(HidEvent.Connected(e.hosts.value.first())), ev)
    }

    @Test fun `host kind comes from the bluetooth major class`() {
        assertEquals(HostKind.COMPUTER, hostKindOf(0x0100))
        assertEquals(HostKind.PHONE, hostKindOf(0x0200))
        assertEquals(HostKind.AUDIO_VIDEO, hostKindOf(0x0400))
        assertEquals(HostKind.UNKNOWN, hostKindOf(null))
        assertEquals(HostKind.OTHER, hostKindOf(0x0500))
    }
}
