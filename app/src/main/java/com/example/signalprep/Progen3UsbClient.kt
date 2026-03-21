package com.example.signalprep

import android.util.Log
import java.io.IOException

/**
 * ProGen III USB client.
 *
 * This class models the byte-command protocol found in the ProGen III programmer state machine.
 * It uses a pluggable [Transport] so the Android USB wiring can be implemented separately.
 */
class Progen3UsbClient(
    private val transport: Transport,
    private val expectAckByDefault: Boolean = true
) {
    companion object {
        private const val TAG = "Progen3UsbClient"
        const val MESSAGE_SIZE = 16
    }

    interface Transport {
        @Throws(IOException::class)
        fun connect()

        @Throws(IOException::class)
        fun send(packet: ByteArray)

        /**
         * Reads one raw packet from the device.
         * Return null when transport has no response packet.
         */
        @Throws(IOException::class)
        fun receive(): ByteArray?

        @Throws(IOException::class)
        fun close()

        fun debugInfo(): String = "transport=n/a"
    }

    enum class Command(val code: Byte) {
        READ(0x00),
        ETX(0x03),
        EOT(0x04),
        RTS(0x05),
        ACK(0x06),
        ERASE_FLASH(0x0A),
        ERASE_SECTOR(0x0B),
        SET_SERIAL(0x10),
        SEND_ONE_SET(0x11),
        SEND_ALL_SETS(0x12),
        LIST_ALL_ACTIVE(0x13),
        LIST_AVAILABLE(0x14),
        NAK(0x15),
        SAVE_SERIAL(0x16),
        RESTORE_SERIAL(0x17),
        SEND_REVISION(0x18),
        SEND_SERIAL(0x19),
        RECEIVE_SET(0x1A),
        RECEIVE_BULK(0x1B),
        SEND_PREFS(0x1C),
        SET_PREFS(0x1D),
        SEND_FIRMWARE(0x1E),
        SET_LABEL(0x1F),
        SET_GENERATOR(0x21),
        SET_TX_ON(0x2A),
        SET_TX_OFF(0x2B),
        SET_GENERATOR_ON(0x2C),
        SET_GENERATOR_OFF(0x2D),
        RELEASE_GENERATOR(0x2F),
        CTS(0xAA.toByte()),
        ERR(0xFE.toByte()),
        NULL(0xFF.toByte())
    }

    @Throws(IOException::class)
    fun connect() {
        transport.connect()
        Log.d(TAG, "connected ${transport.debugInfo()}")
    }

    @Throws(IOException::class)
    fun close() {
        transport.close()
    }

    /**
     * Sends a command-only packet (command byte + zero-filled payload).
     */
    @Throws(IOException::class)
    fun sendCommand(command: Command, expectAck: Boolean = expectAckByDefault): Reply {
        val packet = ByteArray(MESSAGE_SIZE)
        packet[0] = command.code
        logTx(packet)
        transport.send(packet)
        return if (expectAck) readReply() else Reply(null, null)
    }

    /**
     * Sends a raw payload packet used after command handshakes (example: control word buffer).
     */
    @Throws(IOException::class)
    fun sendPayload(payload: ByteArray, expectAck: Boolean = expectAckByDefault): Reply {
        val packet = ByteArray(MESSAGE_SIZE)
        val bytesToCopy = minOf(payload.size, MESSAGE_SIZE)
        System.arraycopy(payload, 0, packet, 0, bytesToCopy)
        logTx(packet)
        transport.send(packet)
        return if (expectAck) readReply() else Reply(null, null)
    }

    /**
     * Executes the generator control handshake:
     * 1) SET_GENERATOR
     * 2) wait for CTS
     * 3) send control word payload
     * 4) wait for ACK
     */
    @Throws(IOException::class)
    fun setGenerator(controlWord: ByteArray): Reply {
        var lastError: IOException? = null
        for (attempt in 1..2) {
            try {
                Log.d(TAG, "step=setGeneratorHandshake attempt=$attempt ${transport.debugInfo()}")
                val first = sendCommand(Command.SET_GENERATOR, expectAck = true)
                if (first.command != Command.CTS) {
                    throw IOException("Expected CTS after SET_GENERATOR, got ${first.command ?: "null"}")
                }

                Log.d(TAG, "step=setGeneratorPayload attempt=$attempt ${transport.debugInfo()}")
                val second = sendPayload(controlWord, expectAck = true)
                if (second.command != Command.ACK) {
                    throw IOException("Expected ACK after control word payload, got ${second.command ?: "null"}")
                }
                return second
            } catch (e: IOException) {
                lastError = e
                Log.w(TAG, "step=setGenerator failed attempt=$attempt error=${e.message}")
                if (attempt == 1) {
                    reconnectTransport("setGenerator recovery")
                }
            }
        }
        throw lastError ?: IOException("setGenerator failed with unknown error")
    }

    @Throws(IOException::class)
    fun setGeneratorOutput(enabled: Boolean): Reply {
        val command = if (enabled) Command.SET_GENERATOR_ON else Command.SET_GENERATOR_OFF
        return sendCommandWithRecovery(command, "setGeneratorOutput")
    }

    @Throws(IOException::class)
    fun setTx(enabled: Boolean): Reply {
        val command = if (enabled) Command.SET_TX_ON else Command.SET_TX_OFF
        return sendCommandWithRecovery(command, "setTx")
    }

    @Throws(IOException::class)
    fun releaseGenerator(): Reply {
        return sendCommandWithRecovery(Command.RELEASE_GENERATOR, "releaseGenerator")
    }

    @Throws(IOException::class)
    fun sendAck(): Reply {
        return sendCommand(Command.ACK, expectAck = false)
    }

    @Throws(IOException::class)
    fun sendNak(): Reply {
        return sendCommand(Command.NAK, expectAck = false)
    }

    @Throws(IOException::class)
    private fun readReply(): Reply {
        val raw = transport.receive()
        if (raw == null || raw.isEmpty()) {
            throw IOException("No reply received from ProGen III device")
        }

        val commandByte = raw[0]
        val command = Command.entries.firstOrNull { it.code == commandByte }
        logRx(raw)

        return Reply(
            command = command,
            raw = raw
        )
    }

    @Throws(IOException::class)
    private fun sendCommandWithRecovery(command: Command, step: String): Reply {
        var lastError: IOException? = null
        for (attempt in 1..2) {
            try {
                Log.d(TAG, "step=$step command=${command.name} attempt=$attempt ${transport.debugInfo()}")
                return sendCommand(command, expectAck = true)
            } catch (e: IOException) {
                lastError = e
                Log.w(TAG, "step=$step command=${command.name} failed attempt=$attempt error=${e.message}")
                if (attempt == 1) {
                    reconnectTransport("$step recovery")
                }
            }
        }
        throw lastError ?: IOException("Command ${command.name} failed with unknown error")
    }

    @Throws(IOException::class)
    private fun reconnectTransport(reason: String) {
        Log.w(TAG, "reconnect start reason=$reason")
        runCatching { transport.close() }
        transport.connect()
        Log.w(TAG, "reconnect complete ${transport.debugInfo()}")
    }

    private fun logTx(packet: ByteArray) {
        Log.d(TAG, "TX: ${packet.toHexString()}")
    }

    private fun logRx(packet: ByteArray) {
        Log.d(TAG, "RX: ${packet.toHexString()}")
    }

    data class Reply(
        val command: Command?,
        val raw: ByteArray?
    ) {
        val isAck: Boolean get() = command == Command.ACK
        val isErr: Boolean get() = command == Command.ERR
    }
}

private fun ByteArray.toHexString(): String =
    joinToString(separator = " ") { byte -> "%02X".format(byte.toInt() and 0xFF) }
