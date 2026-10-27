package com.ouor.pocketboard.core.gesture.engine

import com.ouor.pocketboard.core.common.ink.InkPoint
import com.ouor.pocketboard.core.common.ink.InkStroke
import com.ouor.pocketboard.core.common.rule.firstMatch
import com.ouor.pocketboard.core.gesture.ContactRecord
import com.ouor.pocketboard.core.gesture.Gesture
import com.ouor.pocketboard.core.gesture.GestureConfig
import com.ouor.pocketboard.core.gesture.GestureMapping
import com.ouor.pocketboard.core.gesture.GestureOutput
import com.ouor.pocketboard.core.gesture.GestureRecognizer
import com.ouor.pocketboard.core.gesture.GestureStep
import com.ouor.pocketboard.core.gesture.TouchAction
import com.ouor.pocketboard.core.gesture.TouchSample
import com.ouor.pocketboard.core.gesture.rules.DefaultRules
import com.ouor.pocketboard.core.gesture.rules.GestureRules

/**
 * 공개 진입점. 접촉이 끝날 때(모든 손가락 UP 또는 CANCEL) Reject → Classify → Sequence → Resolve 순으로 판정한다.
 * 획은 접촉이 끝나야 나가므로 두 손가락 착지가 엇갈려도 첫 손가락이 획으로 새지 않는다.
 * 단일 탭은 즉시 점 획으로 내보내고, 멀티 탭으로 확정되면 retract 한다(필기 지연 0, gesture.md §3.1).
 */
class DefaultGestureRecognizer(
    private val config: GestureConfig,
    private val rules: GestureRules = DefaultRules.create(config),
) : GestureRecognizer {

    init {
        config.requireInRange()
    }

    private class PendingRecord(val samples: List<TouchSample>, var result: String, val ruleIds: MutableList<String>) {
        fun toRecord() = ContactRecord(samples, result, ruleIds.toList())
    }

    private class PendingTap(val tap: ContactKind.Tap, val dotStrokeId: Long?, val record: PendingRecord)

    private var active: MutableList<TouchSample>? = null
    private val down = HashSet<Int>()
    private val sequence = ArrayList<PendingTap>()
    private var nextStrokeId = 1L
    private var mapped: Set<Gesture> = GestureMapping.DEFAULT.actions.keys

    override fun setMappedGestures(gestures: Set<Gesture>) {
        mapped = gestures
    }

    override fun onTouch(sample: TouchSample): GestureStep {
        val outputs = ArrayList<GestureOutput>()
        val records = ArrayList<ContactRecord>()
        val samples = active ?: if (sample.action == TouchAction.DOWN) mutableListOf<TouchSample>().also { active = it } else null
        if (samples != null) {
            samples += sample
            when (sample.action) {
                TouchAction.DOWN -> down += sample.pointerId
                TouchAction.UP -> down -= sample.pointerId
                TouchAction.CANCEL -> down.clear()
                TouchAction.MOVE -> Unit
            }
            if (down.isEmpty()) {
                active = null
                endContact(Contact(samples), outputs, records)
            }
        } // 접촉 밖의 MOVE/UP(reset 직후 등)은 버린다.
        return GestureStep(outputs, wakeAt(), records)
    }

    override fun onTimer(nowMs: Long): GestureStep {
        val outputs = ArrayList<GestureOutput>()
        val records = ArrayList<ContactRecord>()
        val deadline = wakeAt()
        if (deadline != null && nowMs >= deadline) finalize(outputs, records)
        return GestureStep(outputs, wakeAt(), records)
    }

    override fun reset() {
        active = null
        down.clear()
        sequence.clear()
    }

    /** 접촉 중에는 시퀀스를 확정하지 않는다: 지금 손가락이 다음 탭일 수 있다(시퀀스 규칙이 착지 시각으로 판단). */
    private fun wakeAt(): Long? =
        if (active == null && sequence.isNotEmpty()) sequence.last().tap.endMs + config.multiTapGapMs else null

    private fun endContact(contact: Contact, outputs: MutableList<GestureOutput>, records: MutableList<ContactRecord>) {
        val reject = rules.reject.firstMatch(contact) { RejectVerdict.PASS }
        if (reject.value == RejectVerdict.REJECT) {
            // 거부된 접촉은 없던 일: 진행 중 탭 시퀀스도 건드리지 않는다.
            records += ContactRecord(contact.samples, "rejected", listOfNotNull(reject.ruleId))
            return
        }
        val kind = rules.classify.firstMatch(contact) { ContactKind.Ignore }
        val record = PendingRecord(contact.samples, "ignored", listOfNotNull(kind.ruleId).toMutableList())
        when (val k = kind.value) {
            ContactKind.Ignore -> records += record.toRecord()
            ContactKind.Stroke -> {
                finalize(outputs, records)
                outputs += GestureOutput.StrokeCompleted(nextStrokeId++, contact.toStroke())
                record.result = "stroke"
                records += record.toRecord()
            }
            is ContactKind.Swipe -> {
                finalize(outputs, records)
                outputs += GestureOutput.Detected(k.gesture)
                record.result = k.gesture.id
                records += record.toRecord()
            }
            is ContactKind.Tap -> onTap(k, contact, record, outputs, records)
        }
    }

    private fun onTap(
        tap: ContactKind.Tap,
        contact: Contact,
        record: PendingRecord,
        outputs: MutableList<GestureOutput>,
        records: MutableList<ContactRecord>,
    ) {
        record.result = Gesture.Tap(tap.fingers, 1).id
        val dotId = if (tap.fingers == 1) nextStrokeId++ else null
        if (sequence.isNotEmpty()) {
            val verdict = rules.sequence.firstMatch(SequenceInput(sequence.last().tap, tap, sequence.size)) { SequenceVerdict.NEW }
            if (verdict.value == SequenceVerdict.NEW) finalize(outputs, records) else verdict.ruleId?.let { record.ruleIds += it }
        }
        // 점 획은 이전 시퀀스 확정 뒤에 낸다(출력 순서 = 시간 순서).
        if (dotId != null) outputs += GestureOutput.StrokeCompleted(dotId, contact.toStroke())
        sequence += PendingTap(tap, dotId, record)
        if (sequence.size >= maxMappedCount(tap.fingers)) finalize(outputs, records)
    }

    /** 이 손가락 수로 매핑된 가장 긴 멀티 탭. 여기 도달하면 기다리지 않고 바로 확정한다. */
    private fun maxMappedCount(fingers: Int): Int =
        mapped.filterIsInstance<Gesture.Tap>().filter { it.fingers == fingers }.maxOfOrNull { it.count } ?: 1

    private fun finalize(outputs: MutableList<GestureOutput>, records: MutableList<ContactRecord>) {
        if (sequence.isEmpty()) return
        val input = ResolveInput(
            fingers = sequence.first().tap.fingers,
            count = sequence.size,
            dotStrokeIds = sequence.mapNotNull { it.dotStrokeId },
            mapped = mapped,
        )
        val resolution = rules.resolve.firstMatch(input) { Resolution.NONE }
        resolution.value.retract.forEach { outputs += GestureOutput.StrokeRetracted(it) }
        resolution.value.gesture?.let { g ->
            outputs += GestureOutput.Detected(g)
            sequence.last().record.apply {
                result = g.id
                resolution.ruleId?.let { ruleIds += it }
            }
        }
        sequence.forEach { records += it.record.toRecord() }
        sequence.clear()
    }

    private fun Contact.toStroke() = InkStroke(tracks.single().samples.map { InkPoint(it.xDp, it.yDp, it.timeMs) })
}

/** 어댑터가 MotionEvent 하나에서 낸 샘플 묶음을 차례로 넣고 결과를 합친다. [GestureStep.wakeAtMs]는 마지막 값. */
fun GestureRecognizer.onTouchAll(samples: List<TouchSample>): GestureStep {
    // 빈 묶음이어도 현재 예약을 돌려줘야 호출자가 타이머를 잘못 취소하지 않는다(MIN_VALUE 는 아무것도 확정하지 않음).
    if (samples.isEmpty()) return onTimer(Long.MIN_VALUE)
    var wake: Long? = null
    val outputs = ArrayList<GestureOutput>()
    val records = ArrayList<ContactRecord>()
    for (s in samples) {
        val step = onTouch(s)
        outputs += step.outputs
        records += step.records
        wake = step.wakeAtMs
    }
    return GestureStep(outputs, wake, records)
}
