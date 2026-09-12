package com.tabletgamepadbridge

/**
 * Converts our parsed GIP GamepadState into the 15-byte HID input report
 * matching scrcpy's SC_HID_GAMEPAD_REPORT_DESC layout (see hid_gamepad.c in
 * github.com/Genymobile/scrcpy):
 *
 *  bytes 0-1:  left stick X  (u16 LE, rescaled from signed to 0..65535)
 *  bytes 2-3:  left stick Y
 *  bytes 4-5:  right stick X
 *  bytes 6-7:  right stick Y
 *  bytes 8-9:  left trigger  (u16 LE, 0..32767)
 *  bytes 10-11: right trigger
 *  bytes 12-13: buttons (16-bit LE bitmask)
 *  byte 14:    hat switch / dpad (0 = neutral, 1..8 clockwise from up)
 */
object GamepadHidReport {

    private const val BTN_SOUTH = 0x0001   // A
    private const val BTN_EAST = 0x0002    // B
    private const val BTN_WEST = 0x0008    // X
    private const val BTN_NORTH = 0x0010   // Y
    private const val BTN_LEFT_SHOULDER = 0x0040  // LB
    private const val BTN_RIGHT_SHOULDER = 0x0080 // RB
    private const val BTN_BACK = 0x0400
    private const val BTN_START = 0x0800
    private const val BTN_GUIDE = 0x1000
    private const val BTN_LEFT_STICK = 0x2000
    private const val BTN_RIGHT_STICK = 0x4000

    // Our GIP trigger values top out around ~1023 (10-bit); rescale toward
    // the HID descriptor's full 0..32767 range for better precision use.
    private const val TRIGGER_SCALE = 32

    fun build(state: GamepadState): ByteArray {
        val data = ByteArray(15)

        writeU16LE(data, 0, rescaleAxis(state.leftStickX))
        writeU16LE(data, 2, rescaleAxis(state.leftStickY))
        writeU16LE(data, 4, rescaleAxis(state.rightStickX))
        writeU16LE(data, 6, 0xFFFF - rescaleAxis(state.rightStickY))
        writeU16LE(data, 8, rescaleTrigger(state.leftTrigger))
        writeU16LE(data, 10, rescaleTrigger(state.rightTrigger))

        var buttons = 0
        if (state.a) buttons = buttons or BTN_SOUTH
        if (state.b) buttons = buttons or BTN_EAST
        if (state.x) buttons = buttons or BTN_WEST
        if (state.y) buttons = buttons or BTN_NORTH
        if (state.leftBumper) buttons = buttons or BTN_LEFT_SHOULDER
        if (state.rightBumper) buttons = buttons or BTN_RIGHT_SHOULDER
        if (state.back) buttons = buttons or BTN_BACK
        if (state.start) buttons = buttons or BTN_START
        if (state.guide) buttons = buttons or BTN_GUIDE
        if (state.leftStickClick) buttons = buttons or BTN_LEFT_STICK
        if (state.rightStickClick) buttons = buttons or BTN_RIGHT_STICK
        writeU16LE(data, 12, buttons)

        data[14] = dpadHatValue(state).toByte()

        return data
    }

    private fun rescaleAxis(value: Int): Int {
        // [-32768, 32767] -> [0, 65535]
        return (value + 0x8000) and 0xFFFF
    }

    private fun rescaleTrigger(value: Int): Int {
        val scaled = value * TRIGGER_SCALE
        return scaled.coerceIn(0, 32767)
    }

    private fun writeU16LE(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun dpadHatValue(state: GamepadState): Int {
        // 9 positions: 0=neutral, 1=up, 2=up-right, 3=right, 4=down-right,
        // 5=down, 6=down-left, 7=left, 8=up-left
        if (state.dpadUp) {
            return when {
                state.dpadLeft -> 8
                state.dpadRight -> 2
                else -> 1
            }
        }
        if (state.dpadDown) {
            return when {
                state.dpadLeft -> 6
                state.dpadRight -> 4
                else -> 5
            }
        }
        if (state.dpadLeft) return 7
        if (state.dpadRight) return 3
        return 0
    }
}
