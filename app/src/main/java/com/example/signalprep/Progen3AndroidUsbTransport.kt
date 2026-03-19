package com.example.signalprep

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Concrete Android USB bulk/interrupt transport for ProGen III protocol packets.
 */
class Progen3AndroidUsbTransport(
    private val context: Context,
    private val vendorId: Int? = null,
    private val productId: Int? = null,
    private val timeoutMs: Int = 3_000
) : Progen3UsbClient.Transport {

    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var inEndpoint: UsbEndpoint? = null
    private var outEndpoint: UsbEndpoint? = null

    @Throws(IOException::class)
    override fun connect() {
        if (connection != null) return

        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = findDevice(manager)
            ?: throw IOException(
                "No compatible USB device with IN/OUT endpoints found. " +
                    "Detected: ${buildDeviceDiagnostics(manager)}"
            )

        if (!manager.hasPermission(device)) {
            throw IOException("USB permission not granted for device ${device.deviceName}")
        }

        val iface = findCompatibleInterface(device)
            ?: throw IOException("No compatible transfer interface found on USB device ${device.deviceName}")

        val inEp = findEndpoint(iface, UsbConstants.USB_DIR_IN)
            ?: throw IOException("No IN endpoint found")
        val outEp = findEndpoint(iface, UsbConstants.USB_DIR_OUT)
            ?: throw IOException("No OUT endpoint found")

        val conn = manager.openDevice(device)
            ?: throw IOException("Failed to open USB device ${device.deviceName}")

        if (!conn.claimInterface(iface, true)) {
            conn.close()
            throw IOException("Failed to claim USB interface")
        }

        connection = conn
        usbInterface = iface
        inEndpoint = inEp
        outEndpoint = outEp
    }

    @Throws(IOException::class)
    override fun send(packet: ByteArray) {
        val conn = connection ?: throw IOException("USB connection is not open")
        val ep = outEndpoint ?: throw IOException("USB OUT endpoint unavailable")
        val directSent = if (ep.type == UsbConstants.USB_ENDPOINT_XFER_INT) {
            if (sendViaUsbRequest(conn, ep, packet)) packet.size else -1
        } else {
            conn.bulkTransfer(ep, packet, packet.size, timeoutMs)
        }
        if (directSent > 0) return

        val packetSize = maxOf(ep.maxPacketSize, packet.size)
        val padded = ByteArray(packetSize)
        System.arraycopy(packet, 0, padded, 0, minOf(packet.size, padded.size))
        val paddedSent = if (ep.type == UsbConstants.USB_ENDPOINT_XFER_INT) {
            if (sendViaUsbRequest(conn, ep, padded)) padded.size else -1
        } else {
            conn.bulkTransfer(ep, padded, padded.size, timeoutMs)
        }
        if (paddedSent > 0) return

        val reportPrefixed = ByteArray(packetSize.coerceAtLeast(packet.size + 1))
        reportPrefixed[0] = 0x00
        System.arraycopy(packet, 0, reportPrefixed, 1, minOf(packet.size, reportPrefixed.size - 1))
        val reportSent = if (ep.type == UsbConstants.USB_ENDPOINT_XFER_INT) {
            if (sendViaUsbRequest(conn, ep, reportPrefixed)) reportPrefixed.size else -1
        } else {
            conn.bulkTransfer(ep, reportPrefixed, reportPrefixed.size, timeoutMs)
        }
        if (reportSent > 0) return

        val iface = usbInterface
        if (iface != null && iface.interfaceClass == UsbConstants.USB_CLASS_HID) {
            val hidReportTypeOutput = 0x02
            val requestType = UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_CLASS or 0x01
            val request = 0x09
            val payloads = listOf(packet, reportPrefixed)
            val reportIds = listOf(0x00, 0x01)
            val indexes = listOf(iface.id, 0)
            var controlResult = -1
            for (reportId in reportIds) {
                val value = (hidReportTypeOutput shl 8) or reportId
                for (index in indexes) {
                    for (payload in payloads) {
                        controlResult = conn.controlTransfer(
                            requestType,
                            request,
                            value,
                            index,
                            payload,
                            payload.size,
                            timeoutMs
                        )
                        if (controlResult > 0) return
                    }
                }
            }
            throw IOException(
                "USB send failed (direct=$directSent, padded=$paddedSent, report=$reportSent, hidControl=$controlResult)"
            )
        }

        throw IOException("USB send failed (direct=$directSent, padded=$paddedSent, report=$reportSent)")
    }

    @Throws(IOException::class)
    override fun receive(): ByteArray? {
        val conn = connection ?: throw IOException("USB connection is not open")
        val ep = inEndpoint ?: throw IOException("USB IN endpoint unavailable")

        val packetSize = maxOf(ep.maxPacketSize, Progen3UsbClient.MESSAGE_SIZE)
        if (ep.type == UsbConstants.USB_ENDPOINT_XFER_INT) {
            return receiveViaUsbRequest(conn, ep, packetSize)
        }

        val buffer = ByteArray(packetSize)
        val received = conn.bulkTransfer(ep, buffer, buffer.size, timeoutMs)
        if (received <= 0) return null
        return buffer.copyOf(received)
    }

    @Throws(IOException::class)
    override fun close() {
        runCatching {
            connection?.let { conn ->
                usbInterface?.let { iface ->
                    conn.releaseInterface(iface)
                }
                conn.close()
            }
        }
        connection = null
        usbInterface = null
        inEndpoint = null
        outEndpoint = null
    }

    private fun findDevice(manager: UsbManager): UsbDevice? {
        return manager.deviceList.values.firstOrNull { device ->
            val vendorMatches = vendorId == null || device.vendorId == vendorId
            val productMatches = productId == null || device.productId == productId
            vendorMatches && productMatches && findCompatibleInterface(device) != null
        }
    }

    private fun findCompatibleInterface(device: UsbDevice): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            val hasIn = findEndpoint(iface, UsbConstants.USB_DIR_IN) != null
            val hasOut = findEndpoint(iface, UsbConstants.USB_DIR_OUT) != null
            if (hasIn && hasOut) return iface
        }
        return null
    }

    private fun findEndpoint(iface: UsbInterface, direction: Int): UsbEndpoint? {
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == direction) {
                return ep
            }
        }
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_INT && ep.direction == direction) {
                return ep
            }
        }
        return null
    }

    private fun buildDeviceDiagnostics(manager: UsbManager): String {
        if (manager.deviceList.isEmpty()) return "no USB devices visible"
        return manager.deviceList.values.joinToString(" | ") { device ->
            val ifaceSummary = (0 until device.interfaceCount).joinToString(",") { idx ->
                val iface = device.getInterface(idx)
                val eps = (0 until iface.endpointCount).joinToString("/") { epIdx ->
                    val ep = iface.getEndpoint(epIdx)
                    val dir = if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                    val type = when (ep.type) {
                        UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
                        UsbConstants.USB_ENDPOINT_XFER_INT -> "INT"
                        UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CTRL"
                        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOC"
                        else -> "UNK"
                    }
                    "$dir-$type"
                }
                "if$idx[$eps]"
            }
            "vid=0x${device.vendorId.toString(16)},pid=0x${device.productId.toString(16)},$ifaceSummary"
        }
    }

    private fun sendViaUsbRequest(conn: UsbDeviceConnection, ep: UsbEndpoint, data: ByteArray): Boolean {
        val request = android.hardware.usb.UsbRequest()
        return try {
            if (!request.initialize(conn, ep)) return false
            if (!request.queue(ByteBuffer.wrap(data), data.size)) return false
            conn.requestWait() === request
        } catch (_: Exception) {
            false
        } finally {
            runCatching { request.close() }
        }
    }

    private fun receiveViaUsbRequest(
        conn: UsbDeviceConnection,
        ep: UsbEndpoint,
        packetSize: Int
    ): ByteArray? {
        val request = android.hardware.usb.UsbRequest()
        return try {
            if (!request.initialize(conn, ep)) return null
            val buffer = ByteBuffer.allocate(packetSize)
            if (!request.queue(buffer, packetSize)) return null
            if (conn.requestWait() !== request) return null
            val len = buffer.position()
            if (len <= 0) return null
            buffer.flip()
            ByteArray(len).also { buffer.get(it) }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { request.close() }
        }
    }
}
