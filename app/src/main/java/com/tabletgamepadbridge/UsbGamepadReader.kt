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

private const val TAG = "UsbGamepadReader"
private const val ACTION_USB_PERMISSION = "com.tabletgamepadbridge.USB_PERMISSION"

// As reported to Android's raw USB descriptor (clones a real Xbox
// One/Series controller's identity). Windows sees a different "for
// Windows" clone-chip identity (0x0C12/0x0F18) via its own driver stack.
private const val VENDOR_ID = 0x045E
private const val PRODUCT_ID = 0x02EA

/**
 * Implements enough of Microsoft's GIP (Gaming Input Protocol) to read real
 * button/stick state from an Xbox One/Series-style controller connected
 * directly via USB-OTG. Protocol details (command bytes, packet layouts,
 * required handshake) are taken from the open-source "xone" Linux driver
 * (github.com/medusalix/xone, GPL-2.0), which documents this otherwise
 * undocumented protocol through community reverse-engineering.
 */
class UsbGamepadReader(private val context: Context, private val listener: (GamepadState) -> Unit) {

    // GIP command bytes (see xone bus/protocol.c / driver/gamepad.c)
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
        const val VKEY_LEFT_WIN = 0x5B // guide/home button
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
                // already unregistered
            }
            receiverRegistered = false
        }
    }

    fun findAndRequestDevice(): Boolean {
        val allDevices = usbManager.deviceList.values
        Log.i(TAG, "USB devices currently visible: ${allDevices.size}")
        allDevices.forEach {
            Log.i(TAG, "  device: name=${it.deviceName} vendorId=${it.vendorId} (0x${it.vendorId.toString(16)}) " +
                "productId=${it.productId} (0x${it.productId.toString(16)}) " +
                "class=${it.deviceClass} subclass=${it.deviceSubclass} " +
                "interfaces=${it.interfaceCount}")
        }

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
        Log.i(TAG, "Opening device with ${device.interfaceCount} interfaces")
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            val endpointsDesc = (0 until iface.endpointCount).joinToString(", ") { j ->
                val ep = iface.getEndpoint(j)
                val dir = if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                "ep$j:addr=${ep.address},dir=$dir,type=${ep.type},maxPacket=${ep.maxPacketSize}"
            }
            Log.i(TAG, "  interface $i: class=${iface.interfaceClass} subclass=${iface.interfaceSubclass} " +
                "protocol=${iface.interfaceProtocol} endpoints=[$endpointsDesc]")
        }

        val usbInterface: UsbInterface = device.getInterface(0)
        val conn = usbManager.openDevice(device)
        if (conn == null) {
            Log.e(TAG, "Failed to open device")
            return
        }
        if (!conn.claimInterface(usbInterface, true)) {
            Log.e(TAG, "Failed to claim interface")
            return
        }
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
        if (inEndpoint == null) {
            Log.e(TAG, "No IN endpoint found on interface 0")
            return
        }
        outEndpoint = foundOutEndpoint
        val endpoint = inEndpoint

        running = true
        readerThread = Thread {
            val buffer = ByteArray(endpoint.maxPacketSize)
            while (running) {
                val len = conn.bulkTransfer(endpoint, buffer, buffer.size, 200)
                if (len > 0) {
                    Log.d(TAG, "raw[$len]=" + buffer.take(len).joinToString(" ") {
                        String.format("%02X", it)
                    })
                    handlePacket(buffer, len)
                }
            }
        }
        readerThread?.start()
        Log.i(TAG, "Gamepad reader started on endpoint ${endpoint.address}, out=${outEndpoint?.address}")
    }

    // ---- GIP header encode/decode (varint length, per xone bus/protocol.c) ----

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

        // header: command, options, sequence, single-byte varint length
        // (safe for our payloads, all well under 128 bytes)
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

        val result = conn.bulkTransfer(ep, packet, packet.size, 200)
        Log.i(TAG, "sent cmd=0x${command.toString(16)} options=0x${options.toString(16)} " +
            "len=$payloadLen -> result=$result")
    }

    private fun sendHandshake() {
        if (handshakeSent) return
        handshakeSent = true
        Log.i(TAG, "Sending GIP handshake sequence")

        // IDENTIFY (request capability info)
        sendPacket(Cmd.IDENTIFY, Opt.INTERNAL, null)

        // Give the device time to finish its (chunked) IDENTIFY reply before
        // sending further commands - blasting them immediately after IDENTIFY
        // seems to get ignored, likely because the device is still busy.
        Thread {
            Thread.sleep(600)

            // POWER ON
            sendPacket(Cmd.POWER, Opt.INTERNAL, byteArrayOf(0x00))
            Thread.sleep(150)

            // RUMBLE stop-all (required by some clone gamepads to start input, per xone driver comment)
            sendPacket(
                Cmd.RUMBLE, 0x00,
                byteArrayOf(0x00, 0x0F, 0x00, 0x00, 0x00, 0x00, 0xFF.toByte(), 0x00, 0xEB.toByte())
            )
            Thread.sleep(150)

            // LED on, player 1, moderate brightness
            sendPacket(Cmd.LED, Opt.INTERNAL, byteArrayOf(0x00, 0x01, 0x14))
            Thread.sleep(150)

            // AUTH "host hello" - the real driver always sends this as part of
            // bring-up. We don't implement the actual crypto handshake beyond
            // this (no certificate/ECDH exchange), but some clone firmwares may
            // gate real input on simply seeing an authenticate attempt occur.
            sendPacket(Cmd.AUTHENTICATE, Opt.INTERNAL or 0x10 /* ACKNOWLEDGE */, buildAuthHelloPacket())
            Thread.sleep(200)

            // AUTH "complete" signal - normally sent only after deriving a
            // real session key from the full crypto exchange, which we can't
            // do without Microsoft's key material. Sending it anyway as an
            // experiment: some clone firmware may just check for this signal
            // without actually enforcing real encryption on top.
            sendPacket(Cmd.AUTHENTICATE, Opt.INTERNAL, byteArrayOf(0x01, 0x00))
        }.start()
    }

    private fun buildAuthHelloPacket(): ByteArray {
        val random = ByteArray(32).also { java.util.Random().nextBytes(it) }
        val dataLen = 44 // total(58) - handshakeHeader(6) - trailer(8)
        val pkt = ByteArray(58)
        pkt[0] = 0x00 // context = HANDSHAKE
        pkt[1] = 0x41 // options = ACKNOWLEDGE | FROM_HOST
        pkt[2] = 0x00 // error
        pkt[3] = 0x01 // command = HOST_HELLO
        pkt[4] = ((dataLen shr 8) and 0xFF).toByte() // length (big-endian)
        pkt[5] = (dataLen and 0xFF).toByte()
        pkt[6] = 0x01 // data.command = HOST_HELLO
        pkt[7] = 0x01 // data.version
        val innerLen = dataLen - 4
        pkt[8] = ((innerLen shr 8) and 0xFF).toByte()
        pkt[9] = (innerLen and 0xFF).toByte()
        System.arraycopy(random, 0, pkt, 10, 32)
        // bytes 42..57 (unknown1, unknown2, trailer) left as zero
        return pkt
    }

    private fun handlePacket(data: ByteArray, len: Int) {
        val hdr = decodeHeader(data, len) ?: return
        val payloadStart = hdr.headerLen
        val payloadEnd = minOf(len, payloadStart + hdr.length)
        if (payloadEnd <= payloadStart) {
            if (hdr.command == Cmd.ANNOUNCE) sendHandshake()
            return
        }

        when (hdr.command) {
            Cmd.ANNOUNCE -> {
                Log.i(TAG, "Received ANNOUNCE, sending handshake")
                sendHandshake()
            }
            Cmd.INPUT -> {
                parseInputReport(data, payloadStart, payloadEnd - payloadStart)?.let { state ->
                    lastState = state.copy(guide = lastState.guide)
                    listener(lastState)
                }
            }
            Cmd.VIRTUAL_KEY -> {
                if (payloadEnd - payloadStart >= 2 && data[payloadStart + 1].toInt() == VKEY_LEFT_WIN) {
                    val down = data[payloadStart].toInt() != 0
                    Log.i(TAG, "Guide button: down=$down")
                    lastState = lastState.copy(guide = down)
                    listener(lastState)
                }
            }
            else -> {
                Log.d(TAG, "Unhandled GIP command 0x${hdr.command.toString(16)} len=${hdr.length}")
            }
        }
    }

    // struct gip_gamepad_pkt_input (14 bytes, all little-endian u16):
    // buttons, trigger_left, trigger_right,
    // stick_left_x, stick_left_y, stick_right_x, stick_right_y
    private fun parseInputReport(data: ByteArray, offset: Int, len: Int): GamepadState? {
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
        // Y axes are bitwise-inverted by the hardware (matches xone driver's `~` usage)
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
            rightStickY = stickRightY
        )
    }
}
