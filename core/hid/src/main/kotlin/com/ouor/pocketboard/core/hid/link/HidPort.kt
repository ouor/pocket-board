package com.ouor.pocketboard.core.hid.link

import com.ouor.pocketboard.core.hid.HostDevice
import com.ouor.pocketboard.core.hid.HostKind

/** 링크 상태. BluetoothProfile.STATE_* 를 옮긴 것. */
internal enum class LinkState { CONNECTING, CONNECTED, DISCONNECTING, DISCONNECTED }

/**
 * 블루투스 HID 장치 프로필의 얇은 포트. 실제 구현은 android.AndroidHidPort, 테스트는 가짜.
 * 기기는 주소 문자열로만 다룬다. 권한 오류(SecurityException)는 구현이 잡아 false/null 로 바꾼다.
 */
internal interface HidPort {
    fun setListener(listener: Listener)
    fun hasPermission(): Boolean
    fun bluetoothOn(): Boolean
    fun phoneName(): String?

    /** 프로필 프록시 요청. false = 즉시 거절. 결과는 [Listener.onProxy]. */
    fun openProxy(): Boolean

    /** 묵은 등록을 지우고(unregisterApp) 등록한다. false = 즉시 거절. 결과는 [Listener.onAppStatus]. */
    fun registerApp(): Boolean

    /** 등록 해제 + 프록시 닫기. 이후 이전 등록의 콜백은 오지 않는다. */
    fun close()

    /** false = 연결을 시작하지 못함. */
    fun connect(address: String): Boolean
    fun disconnect(address: String)

    /** HID 링크가 올라와 있는 기기. */
    fun linkedHosts(): List<String>
    fun deviceName(address: String): String

    /** BluetoothClass major device class(예: 0x0100 COMPUTER). 모르면 null. */
    fun majorDeviceClass(address: String): Int?

    /** false = 스택이 받지 않음(큐 가득, 권한, 링크 없음). */
    fun sendReport(address: String, reportId: Int, data: ByteArray): Boolean

    /** 아무 스레드에서나 불린다. */
    interface Listener {
        fun onProxy(connected: Boolean)
        fun onAppStatus(registered: Boolean, plugged: String?)
        fun onConnectionState(address: String, state: LinkState)

        /** 호스트가 보낸 출력 리포트(SET_REPORT 또는 인터럽트 채널). */
        fun onOutputReport(address: String, reportId: Int, data: ByteArray)
        fun onBluetoothOn(on: Boolean)

        /** 폰 쪽 bond 가 풀림(BOND_NONE). E10 판정에 쓴다. */
        fun onBondLost(address: String)

        /** 새 bond 가 생김(BOND_BONDED). 다른 호스트와 연결 중 새 호스트를 페어링하는 경우를 알아챈다. */
        fun onBonded(address: String)
    }
}

/** 저장되는 호스트. [deviceName]은 연결 때마다 갱신, [alias]는 사용자가 지은 이름(F31). */
internal data class StoredHost(val address: String, val deviceName: String, val alias: String?, val lastConnectedAt: Long?) {
    fun toDevice(kind: HostKind) = HostDevice(address, alias ?: deviceName, lastConnectedAt, kind)
}

/** 호스트 목록 저장소(architecture §11 hid_hosts). 첫 항목이 마지막 호스트. */
internal interface HostStore {
    suspend fun load(): List<StoredHost>
    suspend fun save(hosts: List<StoredHost>)
}

/** E16: 기기 클래스 → 종류. 값은 android.bluetooth.BluetoothClass.Device.Major 상수. */
internal fun hostKindOf(majorClass: Int?): HostKind = when (majorClass) {
    0x0100 -> HostKind.COMPUTER
    0x0200 -> HostKind.PHONE
    0x0400 -> HostKind.AUDIO_VIDEO
    null, 0x0000, 0x1F00 -> HostKind.UNKNOWN // MISC, UNCATEGORIZED
    else -> HostKind.OTHER
}
