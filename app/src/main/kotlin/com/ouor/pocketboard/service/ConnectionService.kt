package com.ouor.pocketboard.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.ouor.pocketboard.R
import com.ouor.pocketboard.core.hid.HidState
import com.ouor.pocketboard.di.container
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 연결을 살려 두는 포그라운드 서비스(connectedDevice, architecture §12.1, hid 명세 R1·R2).
 * 하는 일: 상주 알림(SC16)과 그 버튼, HID 가 멈추면(Stopped·Unavailable) 스스로 종료.
 * 진동·keymap 갱신은 AppContainer 가 한다(화면이 꺼져 있어도 프로세스가 살아 있으면 동작).
 */
class ConnectionService : LifecycleService() {
    private var running = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val c = container
        when (intent?.action) {
            ACTION_DISCONNECT -> c.connection.disconnect()
            ACTION_MARK -> c.devLog.mark()
            else -> if (!running) startRunning()
        }
        if (!running) stopSelf()
        return START_NOT_STICKY // 프로세스가 죽으면 HID 등록도 사라지므로 되살리지 않는다
    }

    private fun startRunning() {
        val c = container
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification(c.hid.state.value, c.settingsRepository.settings.value.devlogEnabled),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
            )
        } catch (e: RuntimeException) {
            // API 34+: BLUETOOTH_CONNECT 가 없으면 SecurityException. hid 가 Unavailable(NO_PERMISSION)으로 알린다.
            Log.w(TAG, "startForeground failed", e)
            return
        }
        running = true
        lifecycleScope.launch {
            combine(c.hid.state, c.settingsRepository.settings) { s, settings -> s to settings.devlogEnabled }
                .collect { (s, devlog) ->
                    // 멈춤은 아래 종료 처리가 알림을 지운다. 여기서 다시 올리지 않는다.
                    if (s is HidState.Stopped || s is HidState.Unavailable || !canNotify()) return@collect
                    NotificationManagerCompat.from(this@ConnectionService).notify(NOTIFICATION_ID, notification(s, devlog))
                }
        }
        lifecycleScope.launch {
            // start() 직후는 아직 Stopped 일 수 있으므로 한 번 벗어난 뒤의 멈춤만 본다
            c.hid.state.dropWhile { it is HidState.Stopped }.first { it is HidState.Stopped || it is HidState.Unavailable }
            ServiceCompat.stopForeground(this@ConnectionService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun canNotify() = NotificationManagerCompat.from(this).areNotificationsEnabled()

    private fun notification(state: HidState, devlog: Boolean): Notification {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.notification_channel)).setVibrationEnabled(false).setSound(null, null).build(),
        )
        val text = when (state) {
            is HidState.Connected -> getString(R.string.notification_connected, state.host.name)
            is HidState.Connecting -> getString(R.string.notification_reconnecting, state.host.name)
            is HidState.Reconnecting -> getString(R.string.notification_reconnecting, state.host.name)
            HidState.BluetoothOff -> getString(R.string.notification_bluetooth_off)
            else -> getString(R.string.notification_waiting)
        } + if (devlog) getString(R.string.notification_devlog_suffix) else ""
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pocketboard)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.notification_disconnect), action(ACTION_DISCONNECT, 1))
        AppIntents.open(this, AppIntents.DEST_HOME)?.let {
            b.setContentIntent(PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        if (devlog) b.addAction(0, getString(R.string.notification_mark_wrong), action(ACTION_MARK, 2))
        return b.build()
    }

    private fun action(action: String, requestCode: Int) = PendingIntent.getService(
        this, requestCode, Intent(this, ConnectionService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val TAG = "pb.service"
        private const val CHANNEL_ID = "connection"
        private const val NOTIFICATION_ID = 1
        const val ACTION_DISCONNECT = "com.ouor.pocketboard.action.DISCONNECT"
        const val ACTION_MARK = "com.ouor.pocketboard.action.MARK_WRONG"
    }
}

/** 앱 화면 열기. 액티비티는 UI 몫이라 클래스 대신 런처 인텐트에 목적지 extra 를 붙인다. */
object AppIntents {
    const val EXTRA_DESTINATION = "com.ouor.pocketboard.extra.DESTINATION"
    const val DEST_HOME = "home"
    const val DEST_WRITING = "writing"

    /** 런처 액티비티가 아직 없으면 null. */
    fun open(context: Context, destination: String): Intent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.putExtra(EXTRA_DESTINATION, destination)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
}
