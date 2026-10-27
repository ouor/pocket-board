package com.ouor.pocketboard.core.ink

import com.ouor.pocketboard.core.common.ink.InkPoint
import com.ouor.pocketboard.core.common.ink.InkStroke

/** 합성 획: (x0,y0)→(x1,y1) 직선을 [t0]부터 [durationMs] 동안 그린다. */
fun line(x0: Float, y0: Float, x1: Float, y1: Float, t0: Long, durationMs: Long = 100) =
    InkStroke((0..4).map { i -> InkPoint(x0 + (x1 - x0) * i / 4, y0 + (y1 - y0) * i / 4, t0 + durationMs * i / 4) })

/** 가로세로 [size]인 칸 안을 대각선으로 긋는 획. */
fun box(x: Float, y: Float, size: Float, t0: Long) = line(x, y, x + size, y + size, t0)
