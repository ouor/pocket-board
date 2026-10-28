package com.ouor.pocketboard.data

import java.util.Locale

/**
 * 잉크 기록 그림(`ink-<at>.svg`). 순수 Kotlin(JVM 테스트용), 밝은 배경.
 * 왼쪽: 원래 획(최종 묶음별 색, 시작점에 획 번호, 새 글자 획엔 규칙 id, 합침·나눔 묶음 점선 상자).
 * 오른쪽: 인식기에 넣은 획(finalDx 만큼 민 것). 아래: 단어별 ko·en 후보, 고른 글자, 최종 글, 규칙, 전송 결과.
 */
object InkSvg {
    private const val PANEL = 360f
    private const val PAD = 24f
    private const val LINE = 16f
    private val COLORS = listOf("#1f77b4", "#d62728", "#2ca02c", "#9467bd", "#ff7f0e", "#8c564b", "#e377c2", "#17becf")

    fun render(entry: DevLogEntry.Ink, result: String): String {
        val r = entry.report
        val n = entry.strokes.size
        val color = arrayOfNulls<String>(n)
        val dx = FloatArray(n)
        val marks = ArrayList<Pair<List<Int>, String>>()
        var c = 0
        for (w in r.words) {
            w.strokes.forEachIndexed { k, i -> if (i < n) dx[i] = w.finalDx.getOrElse(k) { 0f } }
            for (cl in w.clusters) {
                cl.forEach { if (it < n) color[it] = COLORS[c % COLORS.size] }
                c++
                if (w.segments.isEmpty()) continue // RAW: 판정 없음
                val newAt = { i: Int -> w.segments.getOrNull(w.strokes.indexOf(i))?.newChar == true }
                if (cl.drop(1).any(newAt)) marks += cl to "merged"
                else if (cl.isNotEmpty() && !newAt(cl[0])) marks += cl to "split"
            }
        }
        val newRule = HashMap<Int, String>()
        r.words.forEach { w -> w.segments.forEachIndexed { k, s -> if (s.newChar) s.ruleId?.let { newRule[w.strokes[k]] = it.substringAfter('/') } } }

        val footer = buildList {
            add("mode=${r.mode}  pre=\"${r.preContext.takeLast(20)}\"")
            r.words.forEachIndexed { k, w ->
                add("w$k ko: ${w.ko.take(3).joinToString(" | ")}   en: ${w.en.take(3).joinToString(" | ")}")
                add("    chosen: \"${w.chosen}\"  ${w.candidateRuleId ?: ""}")
            }
            add("text: \"${r.text}\"")
            add("rules: ${r.ruleIds.joinToString(", ")}")
            add("send: $result")
        }
        val h = PANEL + footer.size * LINE + PAD
        val sb = StringBuilder()
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" width="${f(PANEL * 2)}" height="${f(h)}" viewBox="0 0 ${f(PANEL * 2)} ${f(h)}" font-family="sans-serif">""").append('\n')
        sb.append("""<rect width="100%" height="100%" fill="#ffffff"/>""").append('\n')
        sb.append("""<line x1="${f(PANEL)}" y1="0" x2="${f(PANEL)}" y2="${f(PANEL)}" stroke="#cccccc"/>""").append('\n')
        text(sb, 6f, 14f, "original", 11, "#666666")
        text(sb, PANEL + 6f, 14f, "engine input (finalDx)", 11, "#666666")
        panel(sb, entry, 0f, FloatArray(n), color, newRule, marks)
        panel(sb, entry, PANEL, dx, color, emptyMap(), emptyList())
        footer.forEachIndexed { k, s -> text(sb, 8f, PANEL + LINE * (k + 1), s, 12, "#222222") }
        sb.append("</svg>\n")
        return sb.toString()
    }

    private fun panel(
        sb: StringBuilder, e: DevLogEntry.Ink, x0: Float, dx: FloatArray, color: Array<String?>,
        rule: Map<Int, String>, marks: List<Pair<List<Int>, String>>,
    ) {
        val pts = e.strokes.mapIndexed { i, s -> s.map { (it[0] + dx[i]) to it[1] } }
        val all = pts.flatten()
        if (all.isEmpty()) return
        val minX = all.minOf { it.first }; val maxX = all.maxOf { it.first }
        val minY = all.minOf { it.second }; val maxY = all.maxOf { it.second }
        val scale = minOf((PANEL - 2 * PAD) / maxOf(maxX - minX, 1f), (PANEL - 2 * PAD) / maxOf(maxY - minY, 1f))
        val px = { x: Float -> x0 + PAD + (x - minX) * scale }
        val py = { y: Float -> PAD + (y - minY) * scale }
        pts.forEachIndexed { i, s ->
            val d = s.mapIndexed { k, (x, y) -> (if (k == 0) "M" else "L") + f(px(x)) + " " + f(py(y)) }.joinToString(" ") +
                if (s.size == 1) " l0 0" else "" // 점 획: 둥근 끝으로 점을 그림
            sb.append("""<path d="$d" fill="none" stroke="${color[i] ?: "#999999"}" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/>""").append('\n')
        }
        pts.forEachIndexed { i, s ->
            val (x, y) = s[0]
            text(sb, px(x) - 4f, py(y) - 4f, listOfNotNull(i.toString(), rule[i]).joinToString(" "), 10, "#000000")
        }
        for ((cl, label) in marks) {
            val c = cl.flatMap { pts.getOrElse(it) { emptyList() } }
            if (c.isEmpty()) continue
            val l = px(c.minOf { it.first }) - 6f; val t = py(c.minOf { it.second }) - 6f
            val r = px(c.maxOf { it.first }) + 6f; val b = py(c.maxOf { it.second }) + 6f
            sb.append("""<rect x="${f(l)}" y="${f(t)}" width="${f(r - l)}" height="${f(b - t)}" fill="none" stroke="#555555" stroke-dasharray="4 3"/>""").append('\n')
            text(sb, l, b + 11f, label, 10, "#555555")
        }
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
