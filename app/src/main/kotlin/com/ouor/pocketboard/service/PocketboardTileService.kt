package com.ouor.pocketboard.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.ouor.pocketboard.R
import com.ouor.pocketboard.core.hid.HidState
import com.ouor.pocketboard.di.container
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 빠른 설정 타일(F17, SC15). 표시는 최소: 상태 4가지를 부제목과 활성 여부로만.
 * 탭은 쓰기 화면을 연다. 연결 시작은 화면(포그라운드)이 한다(타일에서 FGS 를 띄우지 않음).
 * 길게 누르기 → SC10 은 런처 액티비티에 `QS_TILE_PREFERENCES` 필터를 다는 것으로 UI 가 처리한다.
 */
class PocketboardTileService : TileService() {
    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        val c = container
        scope = MainScope().also { s ->
            s.launch {
                combine(c.hid.state, c.hid.hosts, c.settingsRepository.settings) { state, hosts, settings ->
                    Triple(state, hosts.firstOrNull()?.name, settings.onboardingDone)
                }.collect { (state, lastHost, onboarded) -> render(state, lastHost, onboarded) }
            }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val intent = AppIntents.open(this, AppIntents.DEST_WRITING) ?: return
        val open = {
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
        if (isLocked) unlockAndRun(open) else open() // F29: 잠금 위 쓰기는 하지 않음
    }

    private fun render(state: HidState, lastHost: String?, onboarded: Boolean) {
        val tile = qsTile ?: return
        val (active, subtitle) = when {
            !onboarded -> false to getString(R.string.tile_setup_needed)
            state is HidState.Connected -> true to state.host.name
            state is HidState.Connecting || state is HidState.Registering -> false to getString(R.string.tile_connecting)
            else -> false to (lastHost ?: getString(R.string.tile_disconnected))
        }
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = subtitle
        tile.updateTile()
    }
}
