package com.ouor.pocketboard.core.hid.android

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ouor.pocketboard.core.hid.link.HostStore
import com.ouor.pocketboard.core.hid.link.StoredHost
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.hidHosts by preferencesDataStore(name = "hid_hosts")

/** architecture §11: `hosts` = JSON 배열 문자열, 첫 항목이 마지막 호스트. 백업 제외 대상. */
internal class DataStoreHostStore(context: Context) : HostStore {
    private val store = context.applicationContext.hidHosts
    private val key = stringPreferencesKey("hosts")
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun load(): List<StoredHost> = try {
        store.data.first()[key]?.let { json.decodeFromString<List<HostRecord>>(it) }.orEmpty().map { it.toHost() }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Log.w("PocketboardHid", "Host list unreadable, starting empty", e)
        emptyList()
    }

    override suspend fun save(hosts: List<StoredHost>) {
        store.edit { it[key] = json.encodeToString(hosts.map(::HostRecord)) }
    }

    // name = 기기 이름. alias 는 F31 에서 추가(없던 파일도 그대로 읽힌다)
    @Serializable
    private data class HostRecord(val address: String, val name: String, val lastConnectedAt: Long? = null, val alias: String? = null) {
        constructor(h: StoredHost) : this(h.address, h.deviceName, h.lastConnectedAt, h.alias)
        fun toHost() = StoredHost(address, name, alias, lastConnectedAt)
    }
}
