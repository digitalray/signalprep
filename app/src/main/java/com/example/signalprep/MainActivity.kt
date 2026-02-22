package com.example.signalprep

import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private data class FrequencyStep(val calculatedFreq: Double, val durationSeconds: Long)
    @Volatile
    private var stopRequested: Boolean = false

    private lateinit var carrierFrequencyInput: EditText
    private lateinit var waveTypeSpinner: Spinner
    private lateinit var offsetInput: EditText
    private lateinit var dutyCycleInput: EditText
    private lateinit var amplitudeInput: EditText
    private lateinit var functionGeneratorIp: EditText
    private lateinit var functionGeneratorPort: EditText
    private lateinit var totalDurationMinutesInput: EditText
    private lateinit var rowsContainer: LinearLayout
    private val importCsvLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importFrequenciesFromCsv(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        carrierFrequencyInput = findViewById(R.id.carrierFrequencyInput)
        waveTypeSpinner = findViewById(R.id.waveTypeSpinner)
        offsetInput = findViewById(R.id.offsetInput)
        dutyCycleInput = findViewById(R.id.dutyCycleInput)
        amplitudeInput = findViewById(R.id.amplitudeInput)
        functionGeneratorIp = findViewById(R.id.functionGeneratorIpInput)
        functionGeneratorPort = findViewById(R.id.functionGeneratorPortInput)
        totalDurationMinutesInput = findViewById(R.id.totalDurationMinutesInput)
        rowsContainer = findViewById(R.id.rowsContainer)

        carrierFrequencyInput.setText("3100000")
        offsetInput.setText("0")
        dutyCycleInput.setText("50")
        amplitudeInput.setText("2")
        functionGeneratorIp.setText("192.168.1.22")
        functionGeneratorPort.setText("5025")
        waveTypeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            Sdg1000xTelnetClient.SUPPORTED_WAVE_SHAPES
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        findViewById<Button>(R.id.addRowButton).setOnClickListener {
            addRow()
        }
        findViewById<Button>(R.id.applyDurationButton).setOnClickListener {
            applyDurationAcrossEnabledRows()
        }
        findViewById<Button>(R.id.importCsvButton).setOnClickListener {
            importCsvLauncher.launch(arrayOf("text/*", "text/comma-separated-values", "application/csv"))
        }
        findViewById<Button>(R.id.runButton).setOnClickListener {
            runEnabledFrequencies()
        }

        carrierFrequencyInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                refreshAllRows()
            }
        })

        addRow()
    }

    private fun runEnabledFrequencies() {
        refreshAllRows()
        stopRequested = false

        val steps = mutableListOf<FrequencyStep>()
        for (i in 0 until rowsContainer.childCount) {
            val row = rowsContainer.getChildAt(i)
            val enabled = row.findViewById<CheckBox>(R.id.enabledCheck).isChecked
            if (!enabled) continue

            val calculatedText = row.findViewById<TextView>(R.id.calculatedText).text.toString().trim()
            val durationText = row.findViewById<EditText>(R.id.durationInput).text.toString().trim()

            val calculatedFreq = calculatedText.toDoubleOrNull() ?: continue
            val durationSeconds = parseDurationSeconds(durationText) ?: continue
            if (durationSeconds <= 0) continue

            steps.add(FrequencyStep(calculatedFreq = calculatedFreq, durationSeconds = durationSeconds))
        }

        if (steps.isEmpty()) {
            Toast.makeText(this, "No enabled rows with valid calculated frequency and duration.", Toast.LENGTH_SHORT).show()
            return
        }

        val host = functionGeneratorIp.text.toString().trim()
        if (host.isBlank()) {
            Toast.makeText(this, "FG IP is required.", Toast.LENGTH_SHORT).show()
            return
        }
        val port = functionGeneratorPort.text.toString().trim().toIntOrNull()
        if (port == null || port !in 1..65535) {
            Toast.makeText(this, "FG Port must be a number between 1 and 65535.", Toast.LENGTH_SHORT).show()
            return
        }
        val waveShape = waveTypeSpinner.selectedItem?.toString() ?: "SINE"
        val amplitude = amplitudeInput.text.toString().toDoubleOrNull() ?: 2.0
        val offset = offsetInput.text.toString().toDoubleOrNull() ?: 0.0
        val dutyCycle = dutyCycleInput.text.toString().toDoubleOrNull() ?: 50.0

        Thread {
            try {
                Sdg1000xTelnetClient(
                    host = host,
                    port = port,
                    connectTimeoutMs = 10_000,
                    readTimeoutMs = 10_000
                ).use { client ->
                    client.connect()
                    client.setOutput(channel = 1, enabled = true)
                    client.setWaveShape(channel = 1, waveShape = waveShape)
                    client.setAmplitude(channel = 1, amplitudeVpp = amplitude)
                    client.setOffset(channel = 1, offsetV = offset)
                    client.setDutyCycle(channel = 1, dutyPercent = dutyCycle)
                    client.setOutput(channel = 1, enabled = true)

                    for (step in steps) {
                        if (stopRequested) break
                        client.setFrequency(channel = 1, frequencyHz = step.calculatedFreq)
                        val shouldContinue = runCountdownPopup(step.calculatedFreq, step.durationSeconds)
                        if (!shouldContinue) break
                    }

                    client.setOutput(channel = 1, enabled = false)
                }
                runOnUiThread {
                    if (stopRequested) {
                        Toast.makeText(this, "Run stopped.", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Run completed.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("SignalPrep", "Run failed", e)
                runOnUiThread {
                    val reason = "${e::class.java.simpleName}: ${e.message}"
                    Toast.makeText(this, "Run failed: $reason", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun runCountdownPopup(frequencyHz: Double, durationSeconds: Long): Boolean {
        var countdownDialog: AlertDialog? = null
        runOnUiThread {
            countdownDialog = AlertDialog.Builder(this)
                .setTitle("Running ${String.format("%.2f", frequencyHz)} Hz")
                .setMessage("Remaining: $durationSeconds s")
                .setNegativeButton("Stop") { _, _ ->
                    stopRequested = true
                }
                .setCancelable(false)
                .create()
            countdownDialog?.show()
        }

        for (remaining in durationSeconds downTo 0) {
            if (stopRequested) {
                runOnUiThread {
                    countdownDialog?.dismiss()
                }
                return false
            }
            runOnUiThread {
                countdownDialog?.setMessage("Remaining: $remaining s")
            }
            if (remaining > 0) {
                Thread.sleep(1000)
            }
        }

        runOnUiThread {
            countdownDialog?.dismiss()
        }
        return true
    }

    private fun addRow(
        actualValue: String = "",
        durationValue: String = "",
        enabledValue: Boolean = false
    ) {
        val rowView = LayoutInflater.from(this).inflate(R.layout.item_frequency_row, rowsContainer, false)

        val actualInput = rowView.findViewById<EditText>(R.id.actualInput)
        val calculatedText = rowView.findViewById<TextView>(R.id.calculatedText)
        val durationInput = rowView.findViewById<EditText>(R.id.durationInput)
        val enabledCheck = rowView.findViewById<CheckBox>(R.id.enabledCheck)
        val deleteRowButton = rowView.findViewById<Button>(R.id.deleteRowButton)

        actualInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                calculatedText.text = calculateFrequency(actualInput.text.toString())
            }
        })

        actualInput.setText(actualValue)
        durationInput.setText(durationValue)
        enabledCheck.isChecked = enabledValue
        calculatedText.text = calculateFrequency(actualValue)
        deleteRowButton.setOnClickListener {
            rowsContainer.removeView(rowView)
        }
        rowsContainer.addView(rowView)
    }

    private fun importFrequenciesFromCsv(uri: Uri) {
        try {
            val importedFrequencies = mutableListOf<Double>()
            val rawBytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (rawBytes == null) {
                Toast.makeText(this, "Unable to open CSV file.", Toast.LENGTH_SHORT).show()
                return
            }

            val charset = detectCharset(rawBytes)
            BufferedReader(InputStreamReader(ByteArrayInputStream(rawBytes), charset)).use { reader ->
                val lines = reader.lineSequence().toList().filter { it.isNotBlank() }
                if (lines.isEmpty()) {
                    Toast.makeText(this, "CSV file is empty.", Toast.LENGTH_SHORT).show()
                    return
                }

                val delimiter = detectDelimiter(lines.first())
                val headerColumns = splitColumns(lines.first(), delimiter)
                val frequencyIndex = headerColumns.indexOfFirst {
                    it.trim().replace("\"", "").equals("Frequency", ignoreCase = true)
                }

                if (frequencyIndex == -1) {
                    Toast.makeText(this, "Frequency column not found.", Toast.LENGTH_SHORT).show()
                    return
                }

                lines.drop(1).forEach { line ->
                    val columns = splitColumns(line, delimiter)
                    if (frequencyIndex < columns.size) {
                        val parsed = columns[frequencyIndex].trim().replace("\"", "").toDoubleOrNull()
                        if (parsed != null && parsed > 0.0) {
                            importedFrequencies.add(parsed)
                        }
                    }
                }
            }

            if (importedFrequencies.isEmpty()) {
                Toast.makeText(this, "No valid frequencies found in CSV.", Toast.LENGTH_SHORT).show()
                return
            }

            rowsContainer.removeAllViews()
            importedFrequencies.forEach { frequency ->
                addRow(actualValue = frequency.toString())
            }
            Toast.makeText(this, "Imported ${importedFrequencies.size} frequencies.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("SignalPrep", "CSV import failed", e)
            Toast.makeText(this, "CSV import failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun detectCharset(bytes: ByteArray): Charset {
        return when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charsets.UTF_16LE
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Charsets.UTF_16BE
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> Charsets.UTF_8
            else -> Charsets.UTF_8
        }
    }

    private fun detectDelimiter(headerLine: String): Char {
        val semicolons = headerLine.count { it == ';' }
        val commas = headerLine.count { it == ',' }
        return if (semicolons >= commas) ';' else ','
    }

    private fun splitColumns(line: String, delimiter: Char): List<String> {
        return line.split(delimiter)
    }

    private fun refreshAllRows() {
        for (i in 0 until rowsContainer.childCount) {
            val row = rowsContainer.getChildAt(i)
            val actualInput = row.findViewById<EditText>(R.id.actualInput)
            val calculatedText = row.findViewById<TextView>(R.id.calculatedText)
            calculatedText.text = calculateFrequency(actualInput.text.toString())
        }
    }

    private fun calculateFrequency(actualText: String): String {
        val actualFreq = actualText.toDoubleOrNull() ?: return ""
        val carrierFreq = carrierFrequencyInput.text.toString().toDoubleOrNull() ?: 3_100_000.0
        if (actualFreq <= 0.0 || carrierFreq <= 0.0) return ""

        // 1) freqMultiplier ≈ (carrierFreq / actualFreq), rounded
        val freqMultiplier = (carrierFreq / actualFreq).roundToInt().toDouble()
        Log.d(
            "SignalPrep ### OUTPUT ####: ",
            "freqMultiplier=$freqMultiplier (carrierFreq=$carrierFreq, actualFreq=$actualFreq)"
        )
        // 2) calculatedFreq = |(freqMultiplier * actualFreq) - carrierFreq|
        val calculatedFreq = abs((freqMultiplier * actualFreq) - carrierFreq)
        // 3) return calculatedFreq for Calculated column
        return String.format("%.2f", calculatedFreq)
    }

    private fun parseDurationSeconds(value: String): Long? {
        if (value.isBlank()) return null

        if (!value.contains(":")) {
            return value.toLongOrNull()
        }

        val parts = value.split(":")
        return when (parts.size) {
            2 -> {
                val minutes = parts[0].toLongOrNull() ?: return null
                val seconds = parts[1].toLongOrNull() ?: return null
                minutes * 60 + seconds
            }

            3 -> {
                val hours = parts[0].toLongOrNull() ?: return null
                val minutes = parts[1].toLongOrNull() ?: return null
                val seconds = parts[2].toLongOrNull() ?: return null
                hours * 3600 + minutes * 60 + seconds
            }

            else -> null
        }
    }

    private fun applyDurationAcrossEnabledRows() {
        val totalMinutes = totalDurationMinutesInput.text.toString().trim().toDoubleOrNull()
        if (totalMinutes == null || totalMinutes <= 0.0) {
            Toast.makeText(this, "Enter a valid total duration in minutes.", Toast.LENGTH_SHORT).show()
            return
        }

        val enabledRows = mutableListOf<android.view.View>()
        for (i in 0 until rowsContainer.childCount) {
            val row = rowsContainer.getChildAt(i)
            val enabled = row.findViewById<CheckBox>(R.id.enabledCheck).isChecked
            if (enabled) enabledRows.add(row)
        }

        if (enabledRows.isEmpty()) {
            Toast.makeText(this, "No enabled rows to apply duration.", Toast.LENGTH_SHORT).show()
            return
        }

        val totalSeconds = (totalMinutes * 60.0)
        val secondsPerRow = (totalSeconds / enabledRows.size).toLong().coerceAtLeast(1L)

        enabledRows.forEach { row ->
            row.findViewById<EditText>(R.id.durationInput).setText(secondsPerRow.toString())
        }

        Toast.makeText(this, "Applied $secondsPerRow seconds to ${enabledRows.size} enabled rows.", Toast.LENGTH_SHORT).show()
    }
}
