package com.ouor.pocketboard.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.service.quicksettings.TileService
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import com.ouor.pocketboard.service.AppIntents
import com.ouor.pocketboard.ui.theme.PocketTheme

/**
 * 단일 액티비티(integration §2). 런처·타일 탭(쓰기), 알림 탭·타일 길게 누르기(홈)를 받는다.
 * 첫 경로: 온보딩 전이면 항상 SC01, 아니면 홈을 바닥에 두고 목적지(기본 쓰기)를 위에 쌓는다.
 */
class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private var destination by mutableStateOf<DestinationRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        // 상태 복원(회전 없음, 프로세스 재생성)이면 내비게이션이 스스로 복원하므로 목적지를 다시 적용하지 않는다
        if (savedInstanceState == null) destination = request(intent)
        setContent {
            PocketTheme {
                val start by produceState<Any?>(null) {
                    val s = vm.loadSettings()
                    value = if (s.onboardingDone) Home else onboardingResume(s.onboardingStep)
                }
                start?.let { PocketApp(vm, it, destination) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        destination = request(intent)
    }

    private fun request(intent: Intent): DestinationRequest {
        val dest = when {
            intent.action == TileService.ACTION_QS_TILE_PREFERENCES -> AppIntents.DEST_HOME // A40
            else -> intent.getStringExtra(AppIntents.EXTRA_DESTINATION) ?: AppIntents.DEST_WRITING // 런처 = 쓰기(G2)
        }
        return DestinationRequest(dest, System.nanoTime())
    }
}
