package com.ouor.pocketboard.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.ouor.pocketboard.core.gesture.ContactRecord
import com.ouor.pocketboard.core.gesture.HapticPattern
import com.ouor.pocketboard.session.SessionLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** 오판정 기록 한 줄(architecture §11). `type` 으로 구분. 기존 필드 의미는 바꾸지 않는다. */
@Serializable
sealed interface DevLogEntry {
    val at: Long

    @Serializable @SerialName("contact")
    data class Contact(override val at: Long, val record: ContactRecord) : DevLogEntry

    @Serializable @SerialName("send")
    data class Send(
        override val at: Long,
        val text: String,
        val ruleIds: List<String>,
        val keyRuleIds: List<String>,
        val result: String,
    ) : DevLogEntry

    @Serializable @SerialName("haptic")
    data class Haptic(override val at: Long, val pattern: HapticPattern) : DevLogEntry

    /** A28 [방금 오판정]: 바로 앞 접촉 묶음을 가리킨다. */
    @Serializable @SerialName("mark")
    data class Mark(override val at: Long) : DevLogEntry
}

data class DevLogStats(val entries: Int, val bytes: Long)

/**
 * 오판정 기록(F30, B6). 켜져 있을 때만 `files/devlog/` 의 jsonl 파일에 덧붙인다. 폰 밖으로 보내지 않는다(G6):
 * 내보내기는 [exportIntent]를 공유 시트로 띄워 사용자가 고른다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DevLog(
    private val context: Context,
    private val scope: CoroutineScope,
    private val enabled: StateFlow<Boolean>,
) : SessionLog {
    private val dir = File(context.filesDir, "devlog")
    private val io = Dispatchers.IO.limitedParallelism(1) // 줄 순서 유지
    private val json = Json { encodeDefaults = false }
    private val _stats = MutableStateFlow(DevLogStats(0, 0))
    val stats: StateFlow<DevLogStats> = _stats.asStateFlow()

    init {
        scope.launch(io) { refreshStats() }
    }

    override fun contact(record: ContactRecord) = append(DevLogEntry.Contact(now(), record))

    override fun send(text: String, ruleIds: List<String>, keyRuleIds: List<String>, result: String) =
        append(DevLogEntry.Send(now(), text, ruleIds, keyRuleIds, result))

    override fun haptic(pattern: HapticPattern) = append(DevLogEntry.Haptic(now(), pattern))

    fun mark() = append(DevLogEntry.Mark(now()))

    fun clear() {
        scope.launch(io) {
            dir.listFiles()?.forEach { it.delete() }
            refreshStats()
        }
    }

    /** 공유 시트용 인텐트. 기록이 없으면 null. `startActivity(Intent.createChooser(it, null))`. */
    fun exportIntent(): Intent? {
        val uris = files().map { FileProvider.getUriForFile(context, "${context.packageName}.devlog", it) }
        if (uris.isEmpty()) return null
        return Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType("application/x-ndjson")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(uris))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun append(entry: DevLogEntry) {
        if (!enabled.value) return
        scope.launch(io) {
            dir.mkdirs()
            val current = files().lastOrNull()?.takeIf { it.length() < MAX_FILE_BYTES }
                ?: File(dir, "devlog-${System.currentTimeMillis()}.jsonl")
            current.appendText(json.encodeToString(DevLogEntry.serializer(), entry) + "\n")
            _stats.value = DevLogStats(_stats.value.entries + 1, files().sumOf { it.length() })
        }
    }

    private fun files(): List<File> = dir.listFiles { f -> f.name.endsWith(".jsonl") }?.sortedBy { it.name }.orEmpty()

    private fun refreshStats() {
        val fs = files()
        _stats.value = DevLogStats(fs.sumOf { f -> f.useLines { it.count() } }, fs.sumOf { it.length() })
    }

    private fun now() = System.currentTimeMillis()

    private companion object {
        const val MAX_FILE_BYTES = 5L * 1024 * 1024
    }
}
