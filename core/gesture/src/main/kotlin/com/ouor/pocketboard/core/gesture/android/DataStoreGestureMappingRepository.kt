package com.ouor.pocketboard.core.gesture.android

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ouor.pocketboard.core.gesture.Action
import com.ouor.pocketboard.core.gesture.AssignResult
import com.ouor.pocketboard.core.gesture.Gesture
import com.ouor.pocketboard.core.gesture.GestureMapping
import com.ouor.pocketboard.core.gesture.GestureMappingRepository
import com.ouor.pocketboard.core.gesture.engine.MappingCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.gestureMappingStore: DataStore<Preferences> by preferencesDataStore(name = "gesture_mapping")

/** Preferences DataStore "gesture_mapping"(architecture §11). AppContainer 에서 하나만 만든다. */
class DataStoreGestureMappingRepository(context: Context) : GestureMappingRepository {

    private val store = context.applicationContext.gestureMappingStore

    override val mapping: Flow<GestureMapping> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it } // 파일 손상 시 기본값
        .map { MappingCodec.decode(it.toStringMap()) }
        .distinctUntilChanged()

    override suspend fun assign(gesture: Gesture, action: Action?): AssignResult {
        var result: AssignResult = AssignResult.SendRequired
        store.edit { prefs ->
            // 검증과 쓰기를 한 트랜잭션에서: 동시에 두 번 눌러도 전송이 사라지지 않는다.
            result = MappingCodec.assign(MappingCodec.decode(prefs.toStringMap()), gesture, action)
            if (result is AssignResult.Assigned) prefs[stringPreferencesKey(gesture.id)] = MappingCodec.encode(action)
        }
        return result
    }

    override suspend fun resetToDefault() {
        store.edit { it.clear() }
    }

    private fun Preferences.toStringMap(): Map<String, String> =
        asMap().entries.mapNotNull { (k, v) -> (v as? String)?.let { k.name to it } }.toMap()
}
