package com.ouor.pocketboard.data

import java.util.Locale

/**
 * 잉크 기록 그림(`ink-<at>.svg`) v2. 순수 Kotlin(JVM 테스트용), 밝은 배경, 높이는 내용에 맞춘다.
 * 위: 인식기 입력(finalDx 로 민 획, 단어를 이어 붙인 띠, 너무 납작하면 두 줄).
 * 가운데: 최종 글자 묶음마다 작은 칸(그 묶음 획만, 획 번호, 아래에 시작 규칙·합침·나눔).
 * 아래: 왼쪽 원래 겹친 획(작게, 획 번호만), 오른쪽 후보·고른 글자·최종 글·규칙·전송 결과(공백은 ␣, 줄바꿈).
 */
object InkSvg {
    private const val W = 720f
    private const val P = 16f
    private const val STRIP_H = 160f
    private const val CELL = 104f
    private const val GAP = 8f
    private const val ORIG = 220f
    private const val LINE = 16f
    private const val LABEL = 12f
    private val COLORS = listOf("#1f77b4", "#d62728", "#2ca02c", "#9467bd", "#ff7f0e", "#8c564b", "#e377c2", "#17becf")

    private class Cluster(val strokes: List<Int>, val color: String, val labels: List<String>)

    fun render(entry: DevLogEntry.Ink, result: String): String {
        val r = entry.report
        val n = entry.strokes.size
        val pts = entry.strokes.map { s -> s.map { it[0] to it[1] } }
        val color = Array(n) { "#999999" }
        val clusters = ArrayList<Cluster>()
        val notes = ArrayList<String>()
        // 인식기 입력 띠: 단어마다 finalDx 로 민 획을 왼쪽 위 맞춰 이어 붙인다
        val strip = arrayOfNulls<List<Pair<Float, Float>>>(n)
        var cursor = 0f
        r.words.forEachIndexed { wi, w ->
            val moved = w.strokes.mapIndexedNotNull { k, i -> pts.getOrNull(i)?.let { s -> i to s.map { (x, y) -> x + w.finalDx.getOrElse(k) { 0f } to y } } }
            val all = moved.flatMap { it.second }
            if (all.isNotEmpty()) {
                val minX = all.minOf { it.first }; val minY = all.minOf { it.second }
                moved.forEach { (i, s) -> strip[i] = s.map { (x, y) -> x - minX + cursor to y - minY } }
                cursor += all.maxOf { it.first } - minX + 40f
            }
            val newAt = { i: Int -> w.segments.getOrNull(w.strokes.indexOf(i)) }
            for (cl in w.clusters) {
                val c = COLORS[clusters.size % COLORS.size]
                cl.forEach { if (it < n) color[it] = c }
                val labels = ArrayList<String>()
                val start = cl.firstOrNull()?.let(newAt)
                labels += "#${clusters.size} " + when {
                    w.segments.isEmpty() -> "raw"
                    start?.newChar == true -> short(start.ruleId)
                    else -> "(split)"
                }
                cl.drop(1).forEach { i -> newAt(i)?.takeIf { it.newChar }?.let { labels += "merged ${short(it.ruleId)}@$i" } }
                w.splits.forEach { s -> val parts = s.parts ?: return@forEach; parts.indexOf(cl).takeIf { it >= 0 }?.let { labels += "split ${short(s.ruleId)} ${s.target} ${it + 1}/${parts.size}" } }
                clusters += Cluster(cl.filter { it < n }, c, labels)
            }
            w.splits.filter { it.parts == null }.forEach { notes += "w$wi ${short(it.ruleId)}#${it.cluster} ${it.target} failed" }
        }

        val sb = StringBuilder()
        var y = P
        // 위: 인식기 입력
        text(sb, P, y + 10f, "engine input (finalDx)", 11, "#666666")
        y += 18f
        val stripW = maxOf(cursor - 40f, 1f)
        val stripH = maxOf(strip.filterNotNull().flatten().maxOfOrNull { it.second } ?: 1f, 1f)
        var breakX = Float.MAX_VALUE
        if (minOf((W - 2 * P) / stripW, STRIP_H / stripH) * stripH < STRIP_H / 2 && clusters.size > 1) {
            // 너무 납작하면 가운데를 넘는 첫 묶음부터 둘째 줄
            breakX = clusters.mapNotNull { c -> c.strokes.mapNotNull { strip[it] }.flatten().minOfOrNull { it.first } }
                .filter { it >= stripW / 2 }.minOrNull() ?: Float.MAX_VALUE
        }
        val rows = if (breakX == Float.MAX_VALUE) 1 else 2
        val rowW = if (rows == 1) stripW else maxOf(breakX, stripW - breakX)
        val s = minOf((W - 2 * P) / rowW, STRIP_H / stripH)
        for (i in 0 until n) {
            val sp = strip[i] ?: continue
            val second = rows == 2 && clusterStart(clusters, i, strip) >= breakX
            val ox = if (second) breakX else 0f
            val oy = if (second) y + stripH * s + 12f else y
            stroke(sb, sp.map { (px, py) -> P + (px - ox) * s to oy + py * s }, color[i], 3f, i, 10)
        }
        y += rows * (stripH * s + 12f) + 8f

        // 가운데: 묶음별 칸
        val perRow = ((W - 2 * P + GAP) / (CELL + GAP)).toInt()
        clusters.chunked(perRow).forEach { row ->
            row.forEachIndexed { k, c ->
                val x = P + k * (CELL + GAP)
                sb.append("""<rect x="${f(x)}" y="${f(y)}" width="${f(CELL)}" height="${f(CELL)}" fill="none" stroke="${c.color}" stroke-opacity="0.5"/>""").append('\n')
                val cp = c.strokes.flatMap { pts[it] }
                if (cp.isNotEmpty()) {
                    val minX = cp.minOf { it.first }; val minY = cp.minOf { it.second }
                    val cs = minOf((CELL - 24f) / maxOf(cp.maxOf { it.first } - minX, 1f), (CELL - 24f) / maxOf(cp.maxOf { it.second } - minY, 1f))
                    c.strokes.forEach { i -> stroke(sb, pts[i].map { (px, py) -> x + 12f + (px - minX) * cs to y + 12f + (py - minY) * cs }, c.color, 2.5f, i, 9) }
                }
                c.labels.forEachIndexed { j, l -> text(sb, x + 2f, y + CELL + LABEL * (j + 1), l, 10, if (j == 0) c.color else "#333333") }
            }
            y += CELL + LABEL * row.maxOf { it.labels.size } + 10f
        }

        // 아래: 원래 겹친 획(작게) + 글
        text(sb, P, y + 10f, "original", 11, "#666666")
        val all = pts.flatten()
        var origH = 0f
        if (all.isNotEmpty()) {
            val minX = all.minOf { it.first }; val minY = all.minOf { it.second }
            val os = minOf((ORIG - 16f) / maxOf(all.maxOf { it.first } - minX, 1f), (ORIG - 16f) / maxOf(all.maxOf { it.second } - minY, 1f))
            origH = (all.maxOf { it.second } - minY) * os
            pts.forEachIndexed { i, sp -> stroke(sb, sp.map { (px, py) -> P + 8f + (px - minX) * os to y + 26f + (py - minY) * os }, color[i], 2f, i, 9) }
        }
        val tx = P + ORIG + P
        val footer = buildList {
            add("mode=${r.mode}  pre=\"${vis(r.preContext.takeLast(20))}\"")
            r.words.forEachIndexed { k, w ->
                add("w$k ko: ${w.ko.take(3).joinToString(" | ") { vis(it) }}")
                add("   en: ${w.en.take(3).joinToString(" | ") { vis(it) }}")
                add("   chosen: \"${vis(w.chosen)}\"  ${w.candidateRuleId ?: ""}")
            }
            addAll(notes)
            add("text: \"${vis(r.text)}\"")
            add("rules: ${r.ruleIds.joinToString(", ")}")
            add("send: $result")
        }.flatMap { wrap(it, W - tx - P, 12) }
        footer.forEachIndexed { k, l -> text(sb, tx, y + 10f + LINE * (k + 1), l, 12, "#222222") }
        y += maxOf(origH + 34f, 10f + LINE * (footer.size + 1)) + P

        return """<svg xmlns="http://www.w3.org/2000/svg" width="${f(W)}" height="${f(y)}" viewBox="0 0 ${f(W)} ${f(y)}" font-family="sans-serif">""" + "\n" +
            """<rect width="100%" height="100%" fill="#ffffff"/>""" + "\n" + sb + "</svg>\n"
    }

    private fun clusterStart(clusters: List<Cluster>, i: Int, strip: Array<List<Pair<Float, Float>>?>): Float =
        clusters.firstOrNull { i in it.strokes }?.strokes?.mapNotNull { strip[it] }?.flatten()?.minOfOrNull { it.first } ?: 0f

    /** 획 하나와 시작점의 획 번호. */
    private fun stroke(sb: StringBuilder, s: List<Pair<Float, Float>>, color: String, width: Float, index: Int, size: Int) {
        val d = s.mapIndexed { k, (x, y) -> (if (k == 0) "M" else "L") + f(x) + " " + f(y) }.joinToString(" ") +
            if (s.size == 1) " l0 0" else "" // 점 획: 둥근 끝으로 점을 그림
        sb.append("""<path d="$d" fill="none" stroke="$color" stroke-width="${f(width)}" stroke-linecap="round" stroke-linejoin="round"/>""").append('\n')
        text(sb, s[0].first - 3f, s[0].second - 3f, index.toString(), size, "#000000")
    }

    private fun short(ruleId: String?) = ruleId?.substringAfter('/')?.replace("same-spot-restart", "same-spot") ?: "?"

    private fun vis(s: String) = s.replace(' ', '␣')

    /** 글자 폭 어림(ASCII 0.6em, 그 밖 1em)으로 [maxW] 안에 줄바꿈. 이어지는 줄은 들여 쓴다. */
    private fun wrap(s: String, maxW: Float, size: Int): List<String> {
        val out = ArrayList<String>()
        var line = StringBuilder()
        var w = 0f
        for (ch in s) {
            val cw = size * if (ch.code < 0x1100) 0.6f else 1f
            if (w + cw > maxW && line.isNotBlank()) {
                val cut = line.lastIndexOf(" ").takeIf { it > line.length / 2 } ?: line.length
                out += line.substring(0, cut)
                line = StringBuilder("    ").append(line.substring(cut).trimStart())
                w = line.sumOf { (size * if (it.code < 0x1100) 0.6 else 1.0) }.toFloat()
            }
            line.append(ch); w += cw
        }
        out += line.toString()
        return out
    }

    private fun text(sb: StringBuilder, x: Float, y: Float, s: String, size: Int, fill: String) {
        sb.append("""<text x="${f(x)}" y="${f(y)}" font-size="$size" fill="$fill" xml:space="preserve">""").append(escape(s)).append("</text>\n")
    }

    /** XML 텍스트·속성용. XML 1.0 에 못 넣는 제어 문자는 버린다. */
    fun escape(s: String): String = buildString {
        for (ch in s) when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> if (ch >= ' ' || ch == '\t') append(ch)
        }
    }

    private fun f(v: Float) = String.format(Locale.ROOT, "%.1f", v)
}
