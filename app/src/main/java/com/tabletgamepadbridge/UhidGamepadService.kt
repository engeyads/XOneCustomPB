package com.tabletgamepadbridge

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Real implementation of the UHID gamepad Binder service. Instantiated
 * directly inside PrivilegedMain (a process started via `adb shell
 * app_process` with shell privilege) - not bound through Shizuku. Opens
 * /dev/uhid directly using plain Android SDK APIs, which only works because
 * this object lives in a shell-privileged process.
 */
class UhidGamepadService : IUhidGamepadService.Stub() {

    companion object {
        private const val UHID_CREATE2 = 11
        private const val UHID_INPUT2 = 12
        private const val BUS_VIRTUAL: Short = 0x06

        const val VENDOR_ID = 0x1209 // pid.codes test/hobbyist VID
        const val PRODUCT_ID = 0x0001

        // Gamepad HID report descriptor, identical to the one scrcpy uses
        // (github.com/Genymobile/scrcpy, app/src/hid/hid_gamepad.c), already
        // proven to be correctly recognized by Android's input framework.
        val REPORT_DESC: ByteArray = intArrayOf(
            0x05, 0x01,
            0x09, 0x05,
            0xA1, 0x01,
            0xA1, 0x00,
            0x05, 0x01,
            0x09, 0x30,
            0x09, 0x31,
            0x09, 0x32,
            0x09, 0x35,
            0x15, 0x00,
            0x27, 0xFF, 0xFF, 0x00, 0x00,
            0x75, 0x10,
            0x95, 0x04,
            0x81, 0x02,
            0x05, 0x02,
            0x09, 0xC5,
            0x09, 0xC4,
            0x15, 0x00,
            0x26, 0xFF, 0x7F,
            0x75, 0x10,
            0x95, 0x02,
            0x81, 0x02,
            0x05, 0x09,
            0x19, 0x01,
            0x29, 0x10,
            0x15, 0x00,
            0x25, 0x01,
            0x95, 0x10,
            0x75, 0x01,
            0x81, 0x02,
            0x05, 0x01,
            0x09, 0x39,
            0x15, 0x01,
            0x25, 0x08,
            0x75, 0x04,
            0x95, 0x01,
            0x81, 0x42,
            0xC0,
            0xC0
        ).map { it.toByte() }.toByteArray()
    }

    private var fd: FileDescriptor? = null

    @Synchronized
    override fun createGamepad(): Boolean {
        if (fd != null) return true
        return try {
            val newFd = Os.open("/dev/uhid", OsConstants.O_RDWR, 0)
            val req = buildCreate2Req(VENDOR_ID, PRODUCT_ID, "TabletGamepadBridge Virtual Controller", REPORT_DESC)
            Os.write(newFd, req, 0, req.size)
            fd = newFd
            true
        } catch (e: Exception) {
            false
        }
    }

    @Synchronized
    override fun sendInput(data: ByteArray) {
        val f = fd ?: return
        try {
            val req = buildInput2Req(data)
            Os.write(f, req, 0, req.size)
        } catch (e: ErrnoException) {
            // ignore transient write failures
        }
    }

    @Synchronized
    override fun destroy() {
        fd?.let {
            try {
                Os.close(it)
            } catch (e: ErrnoException) {
                // ignore
            }
        }
        fd = null
        System.exit(0)
    }

    private fun buildCreate2Req(vendorId: Int, productId: Int, name: String, reportDesc: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(4 + 128 + 64 + 64 + 2 + 2 + 4 + 4 + 4 + 4 + reportDesc.size)
            .order(ByteOrder.nativeOrder())
        buf.putInt(UHID_CREATE2)

        val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
        val nameLen = minOf(nameBytes.size, 127)
        buf.put(nameBytes, 0, nameLen)

        buf.position(4 + 256)
        buf.putShort(reportDesc.size.toShort())
        buf.putShort(BUS_VIRTUAL)
        buf.putInt(vendorId)
        buf.putInt(productId)
        buf.putInt(0)
        buf.putInt(0)
        buf.put(reportDesc)
        return buf.array()
    }

    private fun buildInput2Req(data: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(4 + 2 + data.size).order(ByteOrder.nativeOrder())
        buf.putInt(UHID_INPUT2)
        buf.putShort(data.size.toShort())
        buf.put(data)
        return buf.array()
    }
}
