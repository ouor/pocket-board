package com.ouor.pocketboard.service

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.ouor.pocketboard.core.hid.HidKeyboard
import com.ouor.pocketboard.core.hid.HostDevice
import com.ouor.pocketboard.session.ConnectionStarter

/**
 * 연결 명령. 서비스를 먼저 띄우고 hid 를 시작한다(hid 명세 R1). 포그라운드 화면에서만 부른다.
 */
class ConnectionController(private val context: Context, private val hid: HidKeyboard) : ConnectionStarter {

    /** 쓰기 화면·페어링 화면 진입, 첫 획(유휴 해제 뒤 A13). 이미 시작했으면 아무 일 없음. */
    override fun ensureStarted() {
        startService()
        hid.start()
    }

    /** 호스트 목록에서 선택(F23). */
    fun connect(host: HostDevice) {
        startService()
        hid.connect(host)
    }

    /** [연결 끊기]. 서비스는 Stopped 를 보고 스스로 끝난다. */
    fun disconnect() = hid.stop()

    /** 페어링(SC05): `hid.state` 가 Ready 일 때 `startActivity` 로 띄운다(hid 명세 §5, R7). */
    fun discoverableIntent(seconds: Int = 300): Intent =
        Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, seconds)

    private fun startService() {
        try {
            ContextCompat.startForegroundService(context, Intent(context, ConnectionService::class.java))
        } catch (e: IllegalStateException) {
            // 백그라운드에서 불림(ForegroundServiceStartNotAllowedException). 호출 규칙 위반이므로 로그만.
            Log.w("pb.connection", "startForegroundService not allowed", e)
        }
    }
}
