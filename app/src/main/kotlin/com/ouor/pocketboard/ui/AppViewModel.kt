package com.ouor.pocketboard.ui

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ouor.pocketboard.core.gesture.Action
import com.ouor.pocketboard.core.gesture.AssignResult
import com.ouor.pocketboard.core.gesture.Gesture
import com.ouor.pocketboard.core.gesture.HapticPattern
import com.ouor.pocketboard.core.hid.HostDevice
import com.ouor.pocketboard.data.AppSettings
import com.ouor.pocketboard.di.AppPermissions
import com.ouor.pocketboard.di.container
import kotlinx.coroutines.launch

/**
 * 일반 화면(SC01~SC08, SC10~SC17)이 함께 쓰는 액티비티 범위 ViewModel. AppContainer 의 상태·명령을
 * 그대로 옮겨 놓을 뿐 로직을 갖지 않는다(integration §1). 쓰기·연습 화면은 자기 ViewModel 이 있다.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container

    val hid = c.hid.state
    val hosts = c.hid.hosts
    val model = c.models.state
    val settings = c.settingsRepository.settings
    val mapping = c.mapping
    val devLogStats = c.devLog.stats
    val imeState = c.keyInput.imeState

    suspend fun loadSettings() = c.settingsRepository.load()
    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { c.settingsRepository.update(transform) }
    }

    fun ensureStarted() = c.connection.ensureStarted()
    fun connect(host: HostDevice) = c.connection.connect(host)
    fun disconnect() = c.connection.disconnect()
    fun forget(host: HostDevice) = c.hid.forget(host)
    fun rename(host: HostDevice, name: String) = c.hid.rename(host, name)
    fun discoverableIntent() = c.connection.discoverableIntent()
    fun phoneName(): String? = c.hid.phoneName()
    suspend fun checkSupport() = c.hid.checkSupport()
    suspend fun sendConnectionTest() = c.sendConnectionTest()

    fun ensureDownloaded() = c.models.ensureDownloaded()
    fun redownload() {
        viewModelScope.launch { c.models.redownload() }
    }

    suspend fun assign(gesture: Gesture, action: Action?): AssignResult = c.mappingRepository.assign(gesture, action)
    suspend fun resetMapping() = c.mappingRepository.resetToDefault()

    fun play(pattern: HapticPattern) = c.haptics.play(pattern)
    fun devLogExport() = c.devLog.exportIntent()
    fun devLogClear() = c.devLog.clear()
}

fun Context.hasRequiredPermissions(): Boolean =
    AppPermissions.required.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
