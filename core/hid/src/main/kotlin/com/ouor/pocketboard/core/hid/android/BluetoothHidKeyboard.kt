package com.ouor.pocketboard.core.hid.android

import android.content.Context
import android.os.SystemClock
import com.ouor.pocketboard.core.hid.HidKeyboard
import com.ouor.pocketboard.core.hid.link.HidEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * [HidKeyboard]의 공개 진입점(architecture §15.1). `AppContainer` 가 프로세스에 하나만 만든다.
 * BT 콜백과 전송은 전용 스레드 하나에서 직렬로 처리한다.
 *
 * @param scope 프로세스 수명 스코프(`AppContainer.appScope`).
 */
class BluetoothHidKeyboard private constructor(engine: HidEngine) : HidKeyboard by engine {
    constructor(context: Context, scope: CoroutineScope) : this(create(context, scope))

    private companion object {
        fun create(context: Context, scope: CoroutineScope): HidEngine {
            val executor = Executors.newSingleThreadExecutor { Thread(it, "pocketboard-hid") }
            return HidEngine(
                port = AndroidHidPort(context, executor),
                store = DataStoreHostStore(context),
                parentScope = scope,
                dispatcher = executor.asCoroutineDispatcher(),
                clock = SystemClock::elapsedRealtime,
            )
        }
    }
}
