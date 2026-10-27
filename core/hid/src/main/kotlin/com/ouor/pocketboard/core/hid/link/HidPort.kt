package com.ouor.pocketboard.core.hid.link

import com.ouor.pocketboard.core.hid.HostDevice

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
    }
}

/** 호스트 목록 저장소(architecture §11 hid_hosts). 첫 항목이 마지막 호스트. */
internal interface HostStore {
    suspend fun load(): List<HostDevice>
    suspend fun save(hosts: List<HostDevice>)
}
