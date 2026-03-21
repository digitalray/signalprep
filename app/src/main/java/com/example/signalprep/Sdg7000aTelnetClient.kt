package com.example.signalprep

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * SDG7000A Telnet/Socket SCPI client.
 *
 * Based on SDG Programming Guide PG02-E04A examples:
 * - Telnet / Socket communication
 * - default instrument service port 5025
 * - sample commands: *IDN?, C1:OUTP ON, C1:BSWV..., WVDT..., SYSTem:COMMunicate:LAN:IPADdress
 */
class Sdg7000aTelnetClient(
    private val host: String,
    private val port: Int = 5025,
    private val connectTimeoutMs: Int = 4_000,
    private val readTimeoutMs: Int = 4_000
) : AutoCloseable {
    private val logTag = "Sdg7000aTelnet"

    companion object {
        val SUPPORTED_WAVE_SHAPES = listOf(
            "SINE", "SQUARE", "RAMP", "PULSE", "NOISE", "ARB", "DC", "PRBS", "IQ"
        )
    }

    private var socket: Socket? = null
    private var input: BufferedInputStream? = null
    private var output: BufferedOutputStream? = null

    @Synchronized
    @Throws(IOException::class)
    fun connect() {
        if (socket?.isConnected == true) return

        val s = Socket()
        s.soTimeout = readTimeoutMs
        s.connect(InetSocketAddress(host, port), connectTimeoutMs)
        Log.d(logTag, "Connected to $host:$port")
        socket = s
        input = BufferedInputStream(s.getInputStream())
        output = BufferedOutputStream(s.getOutputStream())
    }

    @Synchronized
    @Throws(IOException::class)
    fun send(command: String) {
        var lastError: IOException? = null
        for (attempt in 1..2) {
            try {
                ensureConnected()
                Log.d(logTag, "TX attempt=$attempt: ${command.trimEnd()}")
                val bytes = (command.trimEnd() + "\n").toByteArray(StandardCharsets.US_ASCII)
                output!!.write(bytes)
                output!!.flush()
                return
            } catch (e: IOException) {
                lastError = e
                Log.w(logTag, "TX failed attempt=$attempt: ${e.message}")
                if (attempt == 1) {
                    reconnect()
                }
            }
        }
        throw lastError ?: IOException("Failed to send command")
    }

    @Synchronized
    @Throws(IOException::class)
    fun query(command: String): String {
        var lastError: IOException? = null
        for (attempt in 1..2) {
            try {
                send(command)
                val response = readLine()
                Log.d(logTag, "RX attempt=$attempt: $response")
                return response
            } catch (e: IOException) {
                lastError = e
                Log.w(logTag, "Query failed attempt=$attempt: ${e.message}")
                if (attempt == 1) {
                    reconnect()
                }
            }
        }
        throw lastError ?: IOException("Failed to query command")
    }

    @Throws(IOException::class)
    fun identify(): String = query("*IDN?")

    @Throws(IOException::class)
    fun setOutput(channel: Int, enabled: Boolean) {
        validateChannel(channel)
        val state = if (enabled) "ON" else "OFF"
        send("C$channel:OUTP $state")

        // SDG7000A can be strict about output command form; verify and retry with fallbacks.
        val outputStateAfterPrimary = runCatching { queryOutputState(channel) }.getOrNull()
        val isInRequestedState = if (enabled) {
            outputStateAfterPrimary?.contains("ON", ignoreCase = true) == true
        } else {
            outputStateAfterPrimary?.contains("OFF", ignoreCase = true) == true
        }
        if (isInRequestedState) return

        if (enabled) {
            // Fallback forms seen across SDG families.
            send("C$channel:OUTP ON,LOAD,HZ,PLRT,NOR")
            val fallbackState = runCatching { queryOutputState(channel) }.getOrNull()
            if (fallbackState?.contains("ON", ignoreCase = true) == true) return

            send("C$channel:OUTP ON")
        } else {
            send("C$channel:OUTP OFF")
        }
    }

    @Throws(IOException::class)
    fun setLoadImpedance(channel: Int, ohms: Int = 50) {
        validateChannel(channel)
        require(ohms > 0) { "Load impedance must be positive." }
        send("C$channel:OUTP LOAD,$ohms")
    }

    @Throws(IOException::class)
    fun setLoadMode(channel: Int, mode: String) {
        validateChannel(channel)
        val normalized = mode.trim().uppercase()
        require(normalized == "50" || normalized == "HZ") {
            "Unsupported load mode '$mode'. Use '50' or 'HZ'."
        }
        send("C$channel:OUTP LOAD,$normalized")
    }

    @Throws(IOException::class)
    fun queryOutputState(channel: Int): String {
        validateChannel(channel)
        return query("C$channel:OUTP?")
    }

    @Throws(IOException::class)
    fun setBasicWave(
        channel: Int,
        frequencyHz: Double,
        amplitudeVpp: Double,
        offsetV: Double = 0.0,
        phaseDeg: Double = 0.0,
        waveType: String = "SINE"
    ) {
        validateChannel(channel)
        send(
            "C$channel:BSWV WVTP,$waveType,FRQ,$frequencyHz,AMP,$amplitudeVpp,OFST,$offsetV,PHSE,$phaseDeg"
        )
    }

    @Throws(IOException::class)
    fun queryBasicWave(channel: Int): String {
        validateChannel(channel)
        return query("C$channel:BSWV?")
    }

    @Throws(IOException::class)
    fun setAmplitude(channel: Int, amplitudeVpp: Double) {
        validateChannel(channel)
        send("C$channel:BSWV AMP,$amplitudeVpp")
    }

    @Throws(IOException::class)
    fun setOffset(channel: Int, offsetV: Double) {
        validateChannel(channel)
        send("C$channel:BSWV OFST,$offsetV")
    }

    @Throws(IOException::class)
    fun setDutyCycle(channel: Int, dutyPercent: Double) {
        validateChannel(channel)
        require(dutyPercent in 0.0..100.0) { "Duty cycle must be between 0 and 100." }
        send("C$channel:BSWV DUTY,$dutyPercent")
    }

    @Throws(IOException::class)
    fun setFrequency(channel: Int, frequencyHz: Double) {
        validateChannel(channel)
        require(frequencyHz > 0.0) { "Frequency must be greater than 0." }
        send("C$channel:BSWV FRQ,$frequencyHz")
    }

    @Throws(IOException::class)
    fun setWaveShape(channel: Int, waveShape: String) {
        validateChannel(channel)
        val normalized = waveShape.trim().uppercase()
        require(normalized in SUPPORTED_WAVE_SHAPES) {
            "Unsupported wave shape '$waveShape'. Supported: $SUPPORTED_WAVE_SHAPES"
        }
        send("C$channel:BSWV WVTP,$normalized")
    }

    @Throws(IOException::class)
    fun setLanIpAddress(ipAddress: String) {
        send("SYSTem:COMMunicate:LAN:IPADdress $ipAddress")
    }

    /**
     * Sends raw WVDT command payload as documented by SDG examples.
     * Example: "WVNM,wave1,FREQ,2000.0,AMPL,4.0,OFST,0.0,PHASE,0.0,WAVEDATA,<binary-or-ascii>"
     */
    @Throws(IOException::class)
    fun sendWaveData(channel: Int, payload: String) {
        validateChannel(channel)
        send("C$channel:WVDT $payload")
    }

    @Synchronized
    @Throws(IOException::class)
    override fun close() {
        try {
            input?.close()
        } catch (_: IOException) {
        }
        try {
            output?.close()
        } catch (_: IOException) {
        }
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        input = null
        output = null
        socket = null
        Log.d(logTag, "Connection closed")
    }

    @Throws(IOException::class)
    private fun readLine(): String {
        val buf = StringBuilder()
        while (true) {
            val b = input!!.read()
            if (b == -1) throw IOException("Connection closed by instrument")
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.append(b.toChar())
        }
        return buf.toString().trim()
    }

    private fun ensureConnected() {
        if (socket?.isConnected != true || input == null || output == null) {
            throw IllegalStateException("Not connected. Call connect() first.")
        }
    }

    private fun validateChannel(channel: Int) {
        require(channel == 1 || channel == 2) { "Channel must be 1 or 2 for SDG7000A." }
    }

    @Synchronized
    @Throws(IOException::class)
    private fun reconnect() {
        runCatching { close() }
        connect()
    }
}
