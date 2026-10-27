package com.ouor.pocketboard.core.hid.android

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.ouor.pocketboard.core.hid.link.HidPort
import com.ouor.pocketboard.core.hid.link.HidReports
import com.ouor.pocketboard.core.hid.link.LinkState
import java.util.concurrent.Executor

/**
 * [BluetoothHidDevice]를 [HidPort]로 감싼다. 판단은 하지 않고 옮기기만 한다.
 * 공개 API 만 쓴다(숨은 API·리플렉션 금지, hid.md §2.9).
 */
@SuppressLint("MissingPermission") // 권한은 hasPermission() 으로 확인하고, 빠지면 guard 가 SecurityException 을 잡는다
internal class AndroidHidPort(
    context: Context,
    private val executor: Executor,
) : HidPort {

    private val context = context.applicationContext
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter

    @Volatile private var listener: HidPort.Listener? = null
    @Volatile private var proxy: BluetoothHidDevice? = null
    // 현재 요청만 콜백을 넘긴다. 닫은 프록시·이전 등록의 늦은 콜백이 새 상태를 흔들지 않게(Bluke 방식)
    @Volatile private var serviceListener: BluetoothProfile.ServiceListener? = null
    @Volatile private var callback: Callback? = null

    override fun setListener(listener: HidPort.Listener) {
        this.listener = listener
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply {
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    override fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    override fun bluetoothOn(): Boolean = guard { adapter?.isEnabled } ?: false

    override fun phoneName(): String? = if (hasPermission()) guard { adapter?.name } else null

    override fun openProxy(): Boolean {
        val a = adapter ?: return false
        val sl = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, p: BluetoothProfile) {
                if (serviceListener !== this) {
                    guard { a.closeProfileProxy(BluetoothProfile.HID_DEVICE, p) }
                    return
                }
                proxy = p as BluetoothHidDevice
                listener?.onProxy(true)
            }

            override fun onServiceDisconnected(profile: Int) {
                if (serviceListener !== this) return
                proxy = null
                listener?.onProxy(false)
            }
        }
        serviceListener = sl
        return guard { a.getProfileProxy(context, sl, BluetoothProfile.HID_DEVICE) } ?: false
    }

    override fun registerApp(): Boolean {
        val p = proxy ?: return false
        callback = null
        guard { p.unregisterApp() } // 이전 프로세스가 남긴 등록 정리(barcode)
        val cb = Callback()
        callback = cb
        return guard { p.registerApp(SDP, null, QOS_OUT, executor, cb) } ?: false
    }

    override fun close() {
        callback = null
        serviceListener = null
        val p = proxy ?: return
        proxy = null
        guard { p.unregisterApp() }
        guard { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, p) }
    }

    override fun connect(address: String): Boolean =
        guard { device(address)?.let { proxy?.connect(it) } } ?: false

    override fun disconnect(address: String) {
        guard { device(address)?.let { proxy?.disconnect(it) } }
    }

    override fun linkedHosts(): List<String> =
        guard { proxy?.connectedDevices?.map { it.address } } ?: emptyList()

    override fun deviceName(address: String): String = guard { device(address)?.name } ?: address

    override fun majorDeviceClass(address: String): Int? = guard { device(address)?.bluetoothClass?.majorDeviceClass }

    override fun sendReport(address: String, reportId: Int, data: ByteArray): Boolean =
        guard { device(address)?.let { proxy?.sendReport(it, reportId, data) } } ?: false

    private fun device(address: String): BluetoothDevice? = guard { adapter?.getRemoteDevice(address) }

    private inner class Callback : BluetoothHidDevice.Callback() {
        private val current get() = callback === this

        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            if (current) listener?.onAppStatus(registered, pluggedDevice?.address)
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            if (!current) return
            val s = when (state) {
                BluetoothProfile.STATE_CONNECTED -> LinkState.CONNECTED
                BluetoothProfile.STATE_CONNECTING -> LinkState.CONNECTING
                BluetoothProfile.STATE_DISCONNECTING -> LinkState.DISCONNECTING
                else -> LinkState.DISCONNECTED
            }
            listener?.onConnectionState(device.address, s)
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            if (!current) return
            val p = proxy ?: return
            val report = if (type == BluetoothHidDevice.REPORT_TYPE_INPUT) HidReports.idleInput(id.toInt()) else null
            guard {
                if (report != null) p.replyReport(device, type, id, report)
                else p.reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ)
            }
        }

        // LED 는 제어 채널(SET_REPORT)과 인터럽트 채널 두 경로로 온다(hid.md §2.7). 둘 다 받는다.
        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            if (!current) return
            guard { proxy?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS) } // 응답이 없으면 호스트가 타임아웃까지 기다린다
            if (type == BluetoothHidDevice.REPORT_TYPE_OUTPUT) listener?.onOutputReport(device.address, id.toInt(), data)
        }

        override fun onInterruptData(device: BluetoothDevice, reportId: Byte, data: ByteArray) {
            if (current) listener?.onOutputReport(device.address, reportId.toInt() and 0xFF, data)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val l = listener ?: return
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED ->
                    when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                        BluetoothAdapter.STATE_ON -> l.onBluetoothOn(true)
                        BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> l.onBluetoothOn(false)
                    }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val address = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        ?.address ?: return
                    when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                        BluetoothDevice.BOND_NONE -> l.onBondLost(address)
                        BluetoothDevice.BOND_BONDED -> l.onBonded(address)
                    }
                }
            }
        }
    }

    private inline fun <T> guard(block: () -> T): T? = try {
        block()
    } catch (e: SecurityException) {
        Log.w(TAG, "Bluetooth permission missing", e)
        null
    } catch (e: IllegalArgumentException) { // 잘못된 주소 등
        Log.w(TAG, "Bluetooth call rejected", e)
        null
    }

    private companion object {
        const val TAG = "PocketboardHid"

        val SDP = BluetoothHidDeviceAppSdpSettings(
            "pocketboard",
            "Handwriting keyboard",
            "pocketboard",
            BluetoothHidDevice.SUBCLASS1_COMBO, // 키보드 + 마우스(B1)
            HidReports.DESCRIPTOR,
        )

        // wearmouse·barcode 기본값(hid.md §2.1). 기기별 문제가 나오면 여기만 조정한다
        val QOS_OUT = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            800, 9, 0, 11_250,
            BluetoothHidDeviceAppQosSettings.MAX,
        )
    }
}
