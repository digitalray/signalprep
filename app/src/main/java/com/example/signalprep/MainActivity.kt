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
    private data class FrequencyStep(
        val ch1SelectedFreq: Double,
        val ch2SelectedFreq: Double,
        val durationSeconds: Long,
        val ch1FrequencyMode: String,
        val ch2FrequencyMode: String
    )
    @Volatile
    private var stopRequested: Boolean = false

    private lateinit var ch1CarrierFrequencyInput: EditText
    private lateinit var ch1UsedFrequencySpinner: Spinner
    private lateinit var ch1WaveTypeSpinner: Spinner
    private lateinit var ch1OffsetInput: EditText
    private lateinit var ch1DutyCycleInput: EditText
    private lateinit var ch1AmplitudeInput: EditText
    private lateinit var ch2CarrierFrequencyInput: EditText
    private lateinit var ch2UsedFrequencySpinner: Spinner
    private lateinit var ch2WaveTypeSpinner: Spinner
    private lateinit var ch2OffsetInput: EditText
    private lateinit var ch2DutyCycleInput: EditText
    private lateinit var ch2AmplitudeInput: EditText
    private lateinit var functionGeneratorIp: EditText
    private lateinit var functionGeneratorPort: EditText
    private lateinit var totalDurationMinutesInput: EditText
    private lateinit var selectAllCheckbox: CheckBox
    private lateinit var rowsContainer: LinearLayout
    private val importCsvLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importFrequenciesFromCsv(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ch1CarrierFrequencyInput = findViewById(R.id.carrierFrequencyInput)
        ch1UsedFrequencySpinner = findViewById(R.id.usedFrequencySpinner)
        ch1WaveTypeSpinner = findViewById(R.id.waveTypeSpinner)
        ch1OffsetInput = findViewById(R.id.offsetInput)
        ch1DutyCycleInput = findViewById(R.id.dutyCycleInput)
        ch1AmplitudeInput = findViewById(R.id.amplitudeInput)
        ch2CarrierFrequencyInput = findViewById(R.id.ch2CarrierFrequencyInput)
        ch2UsedFrequencySpinner = findViewById(R.id.ch2UsedFrequencySpinner)
        ch2WaveTypeSpinner = findViewById(R.id.ch2WaveTypeSpinner)
        ch2OffsetInput = findViewById(R.id.ch2OffsetInput)
        ch2DutyCycleInput = findViewById(R.id.ch2DutyCycleInput)
        ch2AmplitudeInput = findViewById(R.id.ch2AmplitudeInput)
        functionGeneratorIp = findViewById(R.id.functionGeneratorIpInput)
        functionGeneratorPort = findViewById(R.id.functionGeneratorPortInput)
        totalDurationMinutesInput = findViewById(R.id.totalDurationMinutesInput)
        selectAllCheckbox = findViewById(R.id.selectAllCheckbox)
        rowsContainer = findViewById(R.id.rowsContainer)

        ch1CarrierFrequencyInput.setText("3100000")
        ch1OffsetInput.setText("0")
        ch1DutyCycleInput.setText("50")
        ch1AmplitudeInput.setText("2")
        ch2CarrierFrequencyInput.setText("3100000")
        ch2OffsetInput.setText("0")
        ch2DutyCycleInput.setText("50")
        ch2AmplitudeInput.setText("2")
        functionGeneratorIp.setText("192.168.1.22")
        functionGeneratorPort.setText("5025")
        ch1UsedFrequencySpinner.setSelection(1)
        ch2UsedFrequencySpinner.setSelection(0)
        ch1WaveTypeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            Sdg1000xTelnetClient.SUPPORTED_WAVE_SHAPES
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        ch2WaveTypeSpinner.adapter = ArrayAdapter(
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
        selectAllCheckbox.setOnCheckedChangeListener { _, isChecked ->
            for (i in 0 until rowsContainer.childCount) {
                val row = rowsContainer.getChildAt(i)
                row.findViewById<CheckBox>(R.id.enabledCheck).isChecked = isChecked
            }
        }

        ch1CarrierFrequencyInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                refreshAllRows()
            }
        })
        ch2CarrierFrequencyInput.addTextChangedListener(object : TextWatcher {
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
        val ch1SelectedMode = ch1UsedFrequencySpinner.selectedItem?.toString() ?: "Actual"
        val ch2SelectedMode = ch2UsedFrequencySpinner.selectedItem?.toString() ?: "Actual"
        val ch1UseActual = ch1SelectedMode.equals("Actual", ignoreCase = true)
        val ch2UseActual = ch2SelectedMode.equals("Actual", ignoreCase = true)

        val steps = mutableListOf<FrequencyStep>()
        for (i in 0 until rowsContainer.childCount) {
            val row = rowsContainer.getChildAt(i)
            val enabled = row.findViewById<CheckBox>(R.id.enabledCheck).isChecked
            if (!enabled) continue

            val actualText = row.findViewById<EditText>(R.id.actualInput).text.toString().trim()
            val ch1CalculatedText = row.findViewById<TextView>(R.id.ch1CalculatedText).text.toString().trim()
            val ch2CalculatedText = row.findViewById<TextView>(R.id.ch2CalculatedText).text.toString().trim()
            val durationText = row.findViewById<EditText>(R.id.durationInput).text.toString().trim()

            val ch1SelectedFreq = if (ch1UseActual) {
                actualText.toDoubleOrNull()
            } else {
                ch1CalculatedText.toDoubleOrNull()
            } ?: continue
            val ch2SelectedFreq = if (ch2UseActual) {
                actualText.toDoubleOrNull()
            } else {
                ch2CalculatedText.toDoubleOrNull()
            } ?: continue

            val durationSeconds = parseDurationSeconds(durationText) ?: continue
            if (durationSeconds <= 0) continue

            steps.add(
                FrequencyStep(
                    ch1SelectedFreq = ch1SelectedFreq,
                    ch2SelectedFreq = ch2SelectedFreq,
                    durationSeconds = durationSeconds,
                    ch1FrequencyMode = ch1SelectedMode,
                    ch2FrequencyMode = ch2SelectedMode
                )
            )
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
        val ch1WaveShape = ch1WaveTypeSpinner.selectedItem?.toString() ?: "SINE"
        val ch1Amplitude = ch1AmplitudeInput.text.toString().toDoubleOrNull() ?: 2.0
        val ch1Offset = ch1OffsetInput.text.toString().toDoubleOrNull() ?: 0.0
        val ch1DutyCycle = ch1DutyCycleInput.text.toString().toDoubleOrNull() ?: 50.0
        val ch2WaveShape = ch2WaveTypeSpinner.selectedItem?.toString() ?: "SINE"
        val ch2Amplitude = ch2AmplitudeInput.text.toString().toDoubleOrNull() ?: 2.0
        val ch2Offset = ch2OffsetInput.text.toString().toDoubleOrNull() ?: 0.0
        val ch2DutyCycle = ch2DutyCycleInput.text.toString().toDoubleOrNull() ?: 50.0

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
                    client.setOutput(channel = 2, enabled = true)
                    client.setWaveShape(channel = 1, waveShape = ch1WaveShape)
                    client.setAmplitude(channel = 1, amplitudeVpp = ch1Amplitude)
                    client.setOffset(channel = 1, offsetV = ch1Offset)
                    client.setDutyCycle(channel = 1, dutyPercent = ch1DutyCycle)
                    client.setWaveShape(channel = 2, waveShape = ch2WaveShape)
                    client.setAmplitude(channel = 2, amplitudeVpp = ch2Amplitude)
                    client.setOffset(channel = 2, offsetV = ch2Offset)
                    client.setDutyCycle(channel = 2, dutyPercent = ch2DutyCycle)
                    client.setOutput(channel = 1, enabled = true)
                    client.setOutput(channel = 2, enabled = true)

                    for (step in steps) {
                        if (stopRequested) break
                        client.setFrequency(channel = 1, frequencyHz = step.ch1SelectedFreq)
                        client.setFrequency(channel = 2, frequencyHz = step.ch2SelectedFreq)
                        val shouldContinue = runCountdownPopup(
                            step.ch1SelectedFreq,
                            step.ch2SelectedFreq,
                            step.durationSeconds,
                            step.ch1FrequencyMode,
                            step.ch2FrequencyMode
                        )
                        if (!shouldContinue) break
                    }

                    client.setOutput(channel = 1, enabled = false)
                    client.setOutput(channel = 2, enabled = false)
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

    private fun runCountdownPopup(
        ch1FrequencyHz: Double,
        ch2FrequencyHz: Double,
        durationSeconds: Long,
        ch1FrequencyMode: String,
        ch2FrequencyMode: String
    ): Boolean {
        var countdownDialog: AlertDialog? = null
        runOnUiThread {
            countdownDialog = AlertDialog.Builder(this)
                .setTitle(
                    "CH1 ${String.format("%.2f", ch1FrequencyHz)} Hz ($ch1FrequencyMode) | " +
                        "CH2 ${String.format("%.2f", ch2FrequencyHz)} Hz ($ch2FrequencyMode)"
                )
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
        val ch1CalculatedText = rowView.findViewById<TextView>(R.id.ch1CalculatedText)
        val ch2CalculatedText = rowView.findViewById<TextView>(R.id.ch2CalculatedText)
        val durationInput = rowView.findViewById<EditText>(R.id.durationInput)
        val enabledCheck = rowView.findViewById<CheckBox>(R.id.enabledCheck)
        val deleteRowButton = rowView.findViewById<Button>(R.id.deleteRowButton)

        actualInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                ch1CalculatedText.text = calculateFrequency(actualInput.text.toString(), ch1CarrierFrequencyInput)
                ch2CalculatedText.text = calculateFrequency(actualInput.text.toString(), ch2CarrierFrequencyInput)
            }
        })

        actualInput.setText(actualValue)
        durationInput.setText(durationValue)
        enabledCheck.isChecked = enabledValue || selectAllCheckbox.isChecked
        ch1CalculatedText.text = calculateFrequency(actualValue, ch1CarrierFrequencyInput)
        ch2CalculatedText.text = calculateFrequency(actualValue, ch2CarrierFrequencyInput)
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
            val ch1CalculatedText = row.findViewById<TextView>(R.id.ch1CalculatedText)
            val ch2CalculatedText = row.findViewById<TextView>(R.id.ch2CalculatedText)
            ch1CalculatedText.text = calculateFrequency(actualInput.text.toString(), ch1CarrierFrequencyInput)
            ch2CalculatedText.text = calculateFrequency(actualInput.text.toString(), ch2CarrierFrequencyInput)
        }
    }

    private fun calculateFrequency(actualText: String, carrierInput: EditText): String {
        val actualFreq = actualText.toDoubleOrNull() ?: return ""
        val carrierFreq = carrierInput.text.toString().toDoubleOrNull() ?: 3_100_000.0
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
