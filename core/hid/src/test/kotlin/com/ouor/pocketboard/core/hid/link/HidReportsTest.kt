package com.ouor.pocketboard.core.hid.link

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HidReportsTest {
    private fun ByteArray.hex() = joinToString(" ") { "%02X".format(it) }

    @Test fun `descriptor keeps the shape hosts cache at pairing`() {
        val d = HidReports.DESCRIPTOR.hex()
        assertTrue(d.startsWith("05 01 09 06 A1 01 85 01")) // 키보드, ID 1
        assertTrue("LED output", "05 08 19 01 29 05 75 01 95 05 91 02" in d)
        assertTrue("key range 0..255 (LANG1 0x90 included)", "19 00 29 FF 15 00 26 FF 00 75 08 95 06 81 00" in d)
        assertTrue("mouse, ID 2", "09 02 A1 01 85 02" in d)
        // 바뀌면 모든 사용자가 다시 페어링해야 한다. 의도한 변경일 때만 숫자를 고친다
        assertEquals(120,HidReports.DESCRIPTOR.size)
    }

    @Test fun `key down puts modifiers in byte 0 and usage in byte 2`() {
        assertArrayEquals(byteArrayOf(0x02, 0, 0x04, 0, 0, 0, 0, 0), HidReports.keyDown(0x02, 0x04))
        assertArrayEquals(byteArrayOf(0, 0, 0x90.toByte(), 0, 0, 0, 0, 0), HidReports.keyDown(0, 0x90))
    }

    @Test fun `led report parsed by length`() {
        assertEquals(0x02, HidReports.parseLed(byteArrayOf(0x02)))
        assertEquals(0x01, HidReports.parseLed(byteArrayOf(0x01, 0x01))) // [ID 1, Num Lock] — 첫 바이트만 보면 헷갈림
        assertNull(HidReports.parseLed(byteArrayOf()))
        assertNull(HidReports.parseLed(ByteArray(3)))
    }
}
