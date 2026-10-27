package com.ouor.pocketboard.core.hid.link

/**
 * 리포트 디스크립터(R11, B1)와 리포트 바이트.
 * 디스크립터는 호스트가 페어링 때 캐시한다. 바꾸면 모든 사용자가 다시 페어링해야 하므로 고치지 않는다.
 */
internal object HidReports {
    const val KEYBOARD_ID = 1
    const val MOUSE_ID = 2

    /** 키보드 입력 리포트 길이: [수정키, 예약, 키1..키6]. */
    const val KEYBOARD_INPUT_SIZE = 8
    const val MOUSE_INPUT_SIZE = 4

    val DESCRIPTOR: ByteArray = bytes(
        // 키보드 (Report ID 1)
        0x05, 0x01, // Usage Page (Generic Desktop)
        0x09, 0x06, // Usage (Keyboard)
        0xA1, 0x01, // Collection (Application)
        0x85, KEYBOARD_ID, //   Report ID (1)
        0x05, 0x07, //   Usage Page (Keyboard/Keypad)
        0x19, 0xE0, //   Usage Minimum (Left Ctrl)
        0x29, 0xE7, //   Usage Maximum (Right GUI)
        0x15, 0x00, //   Logical Minimum (0)
        0x25, 0x01, //   Logical Maximum (1)
        0x75, 0x01, //   Report Size (1)
        0x95, 0x08, //   Report Count (8)
        0x81, 0x02, //   Input (Data, Var, Abs) — 수정키
        0x75, 0x08, //   Report Size (8)
        0x95, 0x01, //   Report Count (1)
        0x81, 0x01, //   Input (Const) — 예약 바이트
        0x05, 0x08, //   Usage Page (LEDs)
        0x19, 0x01, //   Usage Minimum (Num Lock)
        0x29, 0x05, //   Usage Maximum (Kana)
        0x75, 0x01, //   Report Size (1)
        0x95, 0x05, //   Report Count (5)
        0x91, 0x02, //   Output (Data, Var, Abs) — LED (E14)
        0x75, 0x03, //   Report Size (3)
        0x95, 0x01, //   Report Count (1)
        0x91, 0x01, //   Output (Const) — 패딩
        0x05, 0x07, //   Usage Page (Keyboard/Keypad)
        0x19, 0x00, //   Usage Minimum (0)
        0x29, 0xFF, //   Usage Maximum (255) — LANG1(0x90)·LANG2(0x91) 포함
        0x15, 0x00, //   Logical Minimum (0)
        0x26, 0xFF, 0x00, // Logical Maximum (255)
        0x75, 0x08, //   Report Size (8)
        0x95, 0x06, //   Report Count (6)
        0x81, 0x00, //   Input (Data, Array) — 키 6개
        0xC0, // End Collection

        // 마우스 (Report ID 2). 1차에는 보내지 않는다(B1, 터치패드 확장 대비)
        0x05, 0x01, // Usage Page (Generic Desktop)
        0x09, 0x02, // Usage (Mouse)
        0xA1, 0x01, // Collection (Application)
        0x85, MOUSE_ID, //   Report ID (2)
        0x09, 0x01, //   Usage (Pointer)
        0xA1, 0x00, //   Collection (Physical)
        0x05, 0x09, //     Usage Page (Buttons)
        0x19, 0x01, //     Usage Minimum (1)
        0x29, 0x03, //     Usage Maximum (3)
        0x15, 0x00, //     Logical Minimum (0)
        0x25, 0x01, //     Logical Maximum (1)
        0x75, 0x01, //     Report Size (1)
        0x95, 0x03, //     Report Count (3)
        0x81, 0x02, //     Input (Data, Var, Abs) — 버튼
        0x75, 0x05, //     Report Size (5)
        0x95, 0x01, //     Report Count (1)
        0x81, 0x01, //     Input (Const) — 패딩
        0x05, 0x01, //     Usage Page (Generic Desktop)
        0x09, 0x30, //     Usage (X)
        0x09, 0x31, //     Usage (Y)
        0x09, 0x38, //     Usage (Wheel)
        0x15, 0x81, //     Logical Minimum (-127)
        0x25, 0x7F, //     Logical Maximum (127)
        0x75, 0x08, //     Report Size (8)
        0x95, 0x03, //     Report Count (3)
        0x81, 0x06, //     Input (Data, Var, Rel)
        0xC0, //   End Collection
        0xC0, // End Collection
    )

    /** 모든 키를 뗀 키보드 리포트. 빈 리포트(링크 깨우기)로도 쓴다. 호출자는 고치지 않는다. */
    val KEY_UP = ByteArray(KEYBOARD_INPUT_SIZE)

    fun keyDown(modifiers: Int, usage: Int): ByteArray = ByteArray(KEYBOARD_INPUT_SIZE).also {
        it[0] = modifiers.toByte()
        it[2] = usage.toByte()
    }

    /** GET_REPORT(INPUT) 응답: 아무것도 누르지 않은 상태. 모르는 ID 면 null. */
    fun idleInput(reportId: Int): ByteArray? = when (reportId) {
        KEYBOARD_ID -> ByteArray(KEYBOARD_INPUT_SIZE)
        MOUSE_ID -> ByteArray(MOUSE_INPUT_SIZE)
        else -> null
    }

    /**
     * 키보드 LED 출력 리포트 → LED 바이트. 스택에 따라 앞에 리포트 ID 가 붙어 오므로 길이로 판단한다
     * (1바이트 = LED, 2바이트 = [ID, LED]). 그 밖의 길이는 null.
     */
    fun parseLed(data: ByteArray): Int? = when (data.size) {
        1 -> data[0].toInt() and 0xFF
        2 -> data[1].toInt() and 0xFF
        else -> null
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
}
