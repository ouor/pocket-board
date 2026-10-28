package com.ouor.pocketboard.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.ouor.pocketboard.core.common.ink.InkItem
import com.ouor.pocketboard.core.common.ink.InkPoint
import com.ouor.pocketboard.core.common.ink.InkStroke
import com.ouor.pocketboard.core.gesture.ContactRecord
import com.ouor.pocketboard.core.gesture.HapticPattern
import com.ouor.pocketboard.core.ink.InkReport
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

    /**
     * 전송 1회의 잉크와 인식 보고서(ink.md §3.2). 같은 [at] 의 send 줄과 짝, 그림은 `ink-<at>.svg`.
     * [strokes]: 보고서 획 번호 순서의 원래 좌표 `[x, y, t-첫 점 t]`. [spaces]: 그 획 번호 앞에 스페이스가 있었음.
     */
    @Serializable @SerialName("ink")
    data class Ink(
        override val at: Long,
        val strokes: List<List<List<Float>>>,
        val spaces: List<Int>,
        val report: InkReport,
    ) : DevLogEntry {
        /** 보낸 items 복원(획 id = 획 번호). replayInk 에 그대로 넣는다. */
        fun items(): List<InkItem> = buildList {
            strokes.forEachIndexed { i, s ->
                repeat(spaces.count { it == i }) { add(InkItem.Space) }
                add(InkItem.Stroke(i.toLong(), InkStroke(s.map { InkPoint(it[0], it[1], it[2].toLong()) })))
            }
            repeat(spaces.count { it == strokes.size }) { add(InkItem.Space) }
        }

        companion object {
            fun of(at: Long, items: List<InkItem>, report: InkReport): Ink {
                val strokes = items.filterIsInstance<InkItem.Stroke>().map { it.stroke }
                val t0 = strokes.firstOrNull()?.points?.first()?.t ?: 0L
                var n = 0
                val spaces = items.mapNotNull { if (it is InkItem.Stroke) { n++; null } else n }
                // shortcut: t 를 Float 로 저장해 2^24 ms(약 4.6시간)까지만 정확, 그보다 긴 전송은 없다
                return Ink(at, strokes.map { s -> s.points.map { listOf(it.x, it.y, (it.t - t0).toFloat()) } }, spaces, report)
            }
        }
    }
}

data class DevLogStats(val entries: Int, val bytes: Long)

/**
 * 오판정 기록(F30, B6). 켜져 있을 때만 `files/devlog/` 의 jsonl 파일에 덧붙인다. 폰 밖으로 보내지 않는다(G6):
 * 내보내기는 [exportIntent]를 공유 시트로 띄워 사용자가 고른다. 잉크 전송은 `ink-<at>.svg` 그림도 남긴다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DevLog(
    private val context: Context,
    private val scope: CoroutineScope,
    private val enabled: StateFlow<Boolean>,
) : SessionLog {
    private val dir = File(context.filesDir, "devlog")
    private val io = Dispatchers.IO.limitedParallelism(1) // 줄 순서 유지
    private val json = DEVLOG_JSON
    private val _stats = MutableStateFlow(DevLogStats(0, 0))
    val stats: StateFlow<DevLogStats> = _stats.asStateFlow()

    init {
        scope.launch(io) { refreshStats() }
    }

    override fun contact(record: ContactRecord) = append(DevLogEntry.Contact(now(), record))

    override fun send(text: String, ruleIds: List<String>, keyRuleIds: List<String>, result: String, items: List<InkItem>, report: InkReport?) {
        val at = now()
        append(DevLogEntry.Send(at, text, ruleIds, keyRuleIds, result))
        if (report == null || !enabled.value) return
        scope.launch(io) {
            // 디버그 기록 실패가 전송을 깨지 않게
            runCatching {
                val entry = DevLogEntry.Ink.of(at, items, report)
                write(entry)
                File(dir, "ink-$at.svg").writeText(InkSvg.render(entry, result))
                prune()
                _stats.value = _stats.value.copy(bytes = all().sumOf { it.length() })
            }.onFailure { Log.w(TAG, "ink record failed", it) }
        }
    }

    override fun haptic(pattern: HapticPattern) = append(DevLogEntry.Haptic(now(), pattern))

    fun mark() = append(DevLogEntry.Mark(now()))

    fun clear() {
        scope.launch(io) {
            dir.listFiles()?.forEach { it.delete() }
            refreshStats()
        }
    }

    /** 공유 시트용 인텐트(jsonl + svg). 기록이 없으면 null. `startActivity(Intent.createChooser(it, null))`. */
    fun exportIntent(): Intent? {
        val uris = all().map { FileProvider.getUriForFile(context, "${context.packageName}.devlog", it) }
        if (uris.isEmpty()) return null
        return Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType("*/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(uris))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun append(entry: DevLogEntry) {
        if (!enabled.value) return
        scope.launch(io) {
            runCatching { write(entry) }.onFailure { Log.w(TAG, "devlog write failed", it) }
        }
    }

    private fun write(entry: DevLogEntry) {
        dir.mkdirs()
        val current = files().lastOrNull()?.takeIf { it.length() < MAX_FILE_BYTES }
            ?: File(dir, "devlog-${System.currentTimeMillis()}.jsonl")
        current.appendText(json.encodeToString(DevLogEntry.serializer(), entry) + "\n")
        _stats.value = DevLogStats(_stats.value.entries + 1, all().sumOf { it.length() })
    }

    /** SVG 는 [MAX_SVGS] 개, 폴더 전체는 [MAX_DIR_BYTES] 까지. 오래된 것부터 지운다. */
    private fun prune() {
        dir.listFiles { f -> f.name.endsWith(".svg") }.orEmpty().sortedBy { it.name }.dropLast(MAX_SVGS).forEach { it.delete() }
        val fs = all().sortedBy { it.lastModified() }.toMutableList()
        var bytes = fs.sumOf { it.length() }
        var linesGone = false
        while (bytes > MAX_DIR_BYTES && fs.size > 1) {
            val f = fs.removeAt(0)
            linesGone = linesGone || f.name.endsWith(".jsonl")
            bytes -= f.length()
            f.delete()
        }
        if (linesGone) refreshStats()
    }

    private fun files(): List<File> = dir.listFiles { f -> f.name.endsWith(".jsonl") }?.sortedBy { it.name }.orEmpty()

    private fun all(): List<File> = dir.listFiles()?.toList().orEmpty()

    private fun refreshStats() {
        _stats.value = DevLogStats(files().sumOf { f -> f.useLines { it.count() } }, all().sumOf { it.length() })
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        /** 기록 줄 쓰기·읽기 공용. 읽을 때 모르는 필드는 무시(형식은 필드를 더하기만 한다). */
        val DEVLOG_JSON = Json { encodeDefaults = false; ignoreUnknownKeys = true }
        private const val MAX_FILE_BYTES = 5L * 1024 * 1024
        private const val MAX_SVGS = 300
        private const val MAX_DIR_BYTES = 30L * 1024 * 1024
        private const val TAG = "pb.devlog"
    }
}
