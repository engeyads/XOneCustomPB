package com.tabletgamepadbridge

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Random

private const val TAG = "UsbGamepadReader"
private const val ACTION_USB_PERMISSION = "com.tabletgamepadbridge.USB_PERMISSION"

private const val VENDOR_ID = 0x045E
private const val PRODUCT_ID = 0x02EA

/**
 * Implements Microsoft's GIP (Gaming Input Protocol) over USB-OTG and measures
 * real-time polling rate (Hz) and transfer latency (ms) for the Pad Link dashboard.
 */
class UsbGamepadReader(private val context: Context, private val listener: (GamepadState) -> Unit) {

    private object Cmd {
        const val ACKNOWLEDGE = 0x01
        const val ANNOUNCE = 0x02
        const val IDENTIFY = 0x04
        const val POWER = 0x05
        const val RUMBLE = 0x09
        const val LED = 0x0A
        const val AUTHENTICATE = 0x06
        const val VIRTUAL_KEY = 0x07
        const val INPUT = 0x20
    }

    private companion object {
        const val VKEY_LEFT_WIN = 0x5B
    }

    private object Opt {
        const val INTERNAL = 0x20
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var connection: UsbDeviceConnection? = null
    private var outEndpoint: UsbEndpoint? = null
    private var readerThread: Thread? = null
    @Volatile private var running = false
    private var receiverRegistered = false
    @Volatile private var handshakeSent = false
    private var sequence = 1
    private var lastState = GamepadState()

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                synchronized(this) {
                    @Suppress("DEPRECATION")
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let { open(it) }
                    } else {
                        Log.w(TAG, "USB permission denied for device $device")
                    }
                }
            }
        }
    }

    fun start() {
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                context,
                permissionReceiver,
                IntentFilter(ACTION_USB_PERMISSION),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
        findAndRequestDevice()
    }

    fun stop() {
        running = false
        readerThread?.join(500)
        readerThread = null
        connection?.close()
        connection = null
        handshakeSent = false
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(permissionReceiver)
            } catch (e: IllegalArgumentException) {
            }
            receiverRegistered = false
        }
    }

    fun findAndRequestDevice(): Boolean {
        val allDevices = usbManager.deviceList.values
        val device = allDevices.firstOrNull {
            it.vendorId == VENDOR_ID && it.productId == PRODUCT_ID
        } ?: run {
            Log.w(TAG, "No device matched VID=$VENDOR_ID PID=$PRODUCT_ID")
            return false
        }

        if (usbManager.hasPermission(device)) {
            open(device)
        } else {
            val permissionIntent = PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE
            )
            usbManager.requestPermission(device, permissionIntent)
        }
        return true
    }

    private fun open(device: UsbDevice) {
        val usbInterface: UsbInterface = device.getInterface(0)
        val conn = usbManager.openDevice(device) ?: return
        if (!conn.claimInterface(usbInterface, true)) return
        connection = conn

        var inEndpoint: UsbEndpoint? = null
        var foundOutEndpoint: UsbEndpoint? = null
        for (i in 0 until usbInterface.endpointCount) {
            val ep = usbInterface.getEndpoint(i)
            if (ep.direction == UsbConstants.USB_DIR_IN) {
                inEndpoint = ep
            } else {
                foundOutEndpoint = ep
            }
        }
        if (inEndpoint == null) return
        outEndpoint = foundOutEndpoint
        val endpoint = inEndpoint

        running = true
        readerThread = Thread {
            val buffer = ByteArray(endpoint.maxPacketSize)
            var packetCount = 0
            var lastSec = System.currentTimeMillis()
            var currentHz = 250
            var currentLatencyMs = 4L

            while (running) {
                val startNs = System.nanoTime()
                val len = conn.bulkTransfer(endpoint, buffer, buffer.size, 200)
                if (len > 0) {
                    val durationMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1)
                    currentLatencyMs = (currentLatencyMs * 3 + durationMs) / 4

                    packetCount++
                    val now = System.currentTimeMillis()
                    if (now - lastSec >= 1000) {
                        currentHz = (packetCount * 1000 / (now - lastSec).toInt()).coerceAtLeast(1)
                        packetCount = 0
                        lastSec = now
                    }

                    handlePacket(buffer, len, currentHz, currentLatencyMs)
                }
            }
        }
        readerThread?.start()
    }

    private data class GipHeader(val command: Int, val options: Int, val seq: Int, val length: Int, val headerLen: Int)

    private fun decodeHeader(data: ByteArray, len: Int): GipHeader? {
        if (len < 4) return null
        val command = data[0].toInt() and 0xFF
        val options = data[1].toInt() and 0xFF
        val seq = data[2].toInt() and 0xFF
        var idx = 3
        var packetLength = 0
        var shift = 0
        while (idx < len) {
            val b = data[idx].toInt() and 0xFF
            packetLength = packetLength or ((b and 0x7F) shl shift)
            idx++
            if (b and 0x80 == 0) break
            shift += 7
        }
        return GipHeader(command, options, seq, packetLength, idx)
    }

    private fun sendPacket(command: Int, options: Int, payload: ByteArray?) {
        val conn = connection ?: return
        val ep = outEndpoint ?: return
        val payloadLen = payload?.size ?: 0

        var seq = sequence
        sequence = if (sequence >= 255) 1 else sequence + 1
        if (seq == 0) seq = 1

        val header = byteArrayOf(
            command.toByte(),
            options.toByte(),
            seq.toByte(),
            payloadLen.toByte()
        )
        val packet = if (payload != null) header + payload else header

        conn.bulkTransfer(ep, packet, packet.size, 200)
    }

    private fun sendHandshake() {
        if (handshakeSent) return
        handshakeSent = true

        sendPacket(Cmd.IDENTIFY, Opt.INTERNAL, null)

        Thread {
            Thread.sleep(600)
            sendPacket(Cmd.POWER, Opt.INTERNAL, byteArrayOf(0x00))
            Thread.sleep(150)
            sendPacket(
                Cmd.RUMBLE, 0x00,
                byteArrayOf(0x00, 0x0F, 0x00, 0x00, 0x00, 0x00, 0xFF.toByte(), 0x00, 0xEB.toByte())
            )
            Thread.sleep(150)
            sendPacket(Cmd.LED, Opt.INTERNAL, byteArrayOf(0x00, 0x01, 0x14))
            Thread.sleep(150)
            sendPacket(Cmd.AUTHENTICATE, Opt.INTERNAL or 0x10, buildAuthHelloPacket())
            Thread.sleep(200)
            sendPacket(Cmd.AUTHENTICATE, Opt.INTERNAL, byteArrayOf(0x01, 0x00))
        }.start()
    }

    private fun buildAuthHelloPacket(): ByteArray {
        val random = ByteArray(32).also { Random().nextBytes(it) }
        val dataLen = 44
        val pkt = ByteArray(58)
        pkt[0] = 0x00
        pkt[1] = 0x41
        pkt[2] = 0x00
        pkt[3] = 0x01
        pkt[4] = ((dataLen shr 8) and 0xFF).toByte()
        pkt[5] = (dataLen and 0xFF).toByte()
        pkt[6] = 0x01
        pkt[7] = 0x01
        val innerLen = dataLen - 4
        pkt[8] = ((innerLen shr 8) and 0xFF).toByte()
        pkt[9] = (innerLen and 0xFF).toByte()
        System.arraycopy(random, 0, pkt, 10, 32)
        return pkt
    }

    private fun handlePacket(data: ByteArray, len: Int, pollHz: Int, latencyMs: Long) {
        val hdr = decodeHeader(data, len) ?: return
        val payloadStart = hdr.headerLen
        val payloadEnd = minOf(len, payloadStart + hdr.length)
        if (payloadEnd <= payloadStart) {
            if (hdr.command == Cmd.ANNOUNCE) sendHandshake()
            return
        }

        when (hdr.command) {
            Cmd.ANNOUNCE -> {
                sendHandshake()
            }
            Cmd.INPUT -> {
                parseInputReport(data, payloadStart, payloadEnd - payloadStart, pollHz, latencyMs)?.let { state ->
                    lastState = state.copy(guide = lastState.guide)
                    listener(lastState)
                }
            }
            Cmd.VIRTUAL_KEY -> {
                if (payloadEnd - payloadStart >= 2 && data[payloadStart + 1].toInt() == VKEY_LEFT_WIN) {
                    val down = data[payloadStart].toInt() != 0
                    lastState = lastState.copy(guide = down)
                    listener(lastState)
                }
            }
        }
    }

    private fun parseInputReport(data: ByteArray, offset: Int, len: Int, pollHz: Int, latencyMs: Long): GamepadState? {
        if (len < 14) return null

        fun u16(o: Int): Int {
            val lo = data[offset + o].toInt() and 0xFF
            val hi = data[offset + o + 1].toInt() and 0xFF
            return (hi shl 8) or lo
        }
        fun s16(o: Int): Int {
            val v = u16(o)
            return if (v >= 0x8000) v - 0x10000 else v
        }

        val buttons = u16(0)
        fun bit(mask: Int) = (buttons and mask) != 0

        val triggerLeft = u16(2)
        val triggerRight = u16(4)
        val stickLeftX = s16(6)

        fun invertedS16(o: Int): Int {
            val inverted = u16(o).inv() and 0xFFFF
            return if (inverted >= 0x8000) inverted - 0x10000 else inverted
        }
        val stickLeftY = invertedS16(8)
        val stickRightX = s16(10)
        val stickRightY = invertedS16(12)

        return GamepadState(
            dpadUp = bit(0x0100),
            dpadDown = bit(0x0200),
            dpadLeft = bit(0x0400),
            dpadRight = bit(0x0800),
            start = bit(0x0004),
            back = bit(0x0008),
            leftStickClick = bit(0x4000),
            rightStickClick = bit(0x8000),
            leftBumper = bit(0x1000),
            rightBumper = bit(0x2000),
            guide = false,
            a = bit(0x0010),
            b = bit(0x0020),
            x = bit(0x0040),
            y = bit(0x0080),
            leftTrigger = triggerLeft,
            rightTrigger = triggerRight,
            leftStickX = stickLeftX,
            leftStickY = stickLeftY,
            rightStickX = stickRightX,
            rightStickY = stickRightY,
            pollHz = pollHz,
            latencyMs = latencyMs,
            batteryPercent = 100
        )
    }
}
