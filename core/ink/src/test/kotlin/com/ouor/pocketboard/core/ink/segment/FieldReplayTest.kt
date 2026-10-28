package com.ouor.pocketboard.core.ink.segment

import com.ouor.pocketboard.core.common.ink.InkItem
import com.ouor.pocketboard.core.common.ink.InkPoint
import com.ouor.pocketboard.core.common.ink.InkStroke
import com.ouor.pocketboard.core.ink.InkReport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 현장 devlog 의 "ink" 줄(architecture.md §11)을 그대로 재현한다. 파일은 src/test/resources/field/. */
class FieldReplayTest {
    /** DevLogEntry.Ink 의 필요한 부분(:app 형식, 모르는 키는 무시). */
    @Serializable
    private class FieldInk(val strokes: List<List<List<Float>>>, val spaces: List<Int>, val report: InkReport) {
        fun items(): List<InkItem> = buildList {
            strokes.forEachIndexed { i, s ->
                repeat(spaces.count { it == i }) { add(InkItem.Space) }
                add(InkItem.Stroke(i.toLong(), InkStroke(s.map { InkPoint(it[0], it[1], it[2].toLong()) })))
            }
            repeat(spaces.count { it == strokes.size }) { add(InkItem.Space) }
        }
    }

    private fun load(name: String): FieldInk =
        Json { ignoreUnknownKeys = true }.decodeFromString(FieldInk.serializer(), javaClass.getResource("/field/$name")!!.readText())

    private suspend fun replay(name: String): Pair<InkReport, RecordedInkEngine> {
        val f = load(name)
        val engine = RecordedInkEngine(f.items(), f.report)
        return replayInk(f.items(), f.report, engine) to engine
    }

    @Test
    fun kkkk_overlapped8_splitPartsCountEvenIfReReadDropsOne() = runTest {
        // 현장: 8획 → ㅋ 조각 4개를 하나씩 확인했는데 재배치 재인식이 "ㅋㅋㅋ"(하나 빠짐)
        val (r, engine) = replay("kkkk-overlapped-8.json")
        assertEquals("ㅋㅋㅋㅋ", r.text)
        assertEquals(4, r.words.single().clusters.size)
        assertEquals(0, engine.misses)
    }

    @Test
    fun leadingSpaceFromPreContext_isTrimmed() = runTest {
        // 현장: ko 1순위 " 테스트"(앞 문맥 때문에 앞에 공백) → 호스트에 공백이 하나 더 갔다
        val (r, engine) = replay("test-leading-space.json")
        assertEquals("테스트", r.text)
        assertTrue(r.ruleIds.toString(), "text/trim-leading" in r.ruleIds)
        assertEquals(0, engine.misses)
    }
}
