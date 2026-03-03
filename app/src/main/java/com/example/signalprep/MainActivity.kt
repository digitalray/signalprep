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
import com.google.android.material.tabs.TabLayout
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private data class FrequencyStep(
        val durationSeconds: Long,
        val device1Ch1SelectedFreq: Double?,
        val device1Ch2SelectedFreq: Double?,
        val device1Ch1FrequencyMode: String,
        val device1Ch2FrequencyMode: String,
        val device2Ch1SelectedFreq: Double?,
        val device2Ch2SelectedFreq: Double?,
        val device2Ch1FrequencyMode: String,
        val device2Ch2FrequencyMode: String
    )
    private data class DeviceClientContext(
        val connect: () -> Unit,
        val setOutput: (Int, Boolean) -> Unit,
        val setWaveShape: (Int, String) -> Unit,
        val setAmplitude: (Int, Double) -> Unit,
        val setOffset: (Int, Double) -> Unit,
        val setDutyCycle: (Int, Double) -> Unit,
        val setFrequency: (Int, Double) -> Unit,
        val closeClient: () -> Unit
    )
    @Volatile
    private var stopRequested: Boolean = false

    private lateinit var device1Ch1CarrierFrequencyInput: EditText
    private lateinit var device1Ch1UsedFrequencySpinner: Spinner
    private lateinit var device1Ch1WaveTypeSpinner: Spinner
    private lateinit var device1Ch1OffsetInput: EditText
    private lateinit var device1Ch1DutyCycleInput: EditText
    private lateinit var device1Ch1AmplitudeInput: EditText
    private lateinit var device1Ch2CarrierFrequencyInput: EditText
    private lateinit var device1Ch2UsedFrequencySpinner: Spinner
    private lateinit var device1Ch2WaveTypeSpinner: Spinner
    private lateinit var device1Ch2OffsetInput: EditText
    private lateinit var device1Ch2DutyCycleInput: EditText
    private lateinit var device1Ch2AmplitudeInput: EditText
    private lateinit var device2Ch1CarrierFrequencyInput: EditText
    private lateinit var device2Ch1UsedFrequencySpinner: Spinner
    private lateinit var device2Ch1WaveTypeSpinner: Spinner
    private lateinit var device2Ch1OffsetInput: EditText
    private lateinit var device2Ch1DutyCycleInput: EditText
    private lateinit var device2Ch1AmplitudeInput: EditText
    private lateinit var device2Ch2CarrierFrequencyInput: EditText
    private lateinit var device2Ch2UsedFrequencySpinner: Spinner
    private lateinit var device2Ch2WaveTypeSpinner: Spinner
    private lateinit var device2Ch2OffsetInput: EditText
    private lateinit var device2Ch2DutyCycleInput: EditText
    private lateinit var device2Ch2AmplitudeInput: EditText
    private lateinit var device1DeviceTypeSpinner: Spinner
    private lateinit var device2DeviceTypeSpinner: Spinner
    private lateinit var device1FunctionGeneratorIp: EditText
    private lateinit var device1FunctionGeneratorPort: EditText
    private lateinit var device2FunctionGeneratorIp: EditText
    private lateinit var device2FunctionGeneratorPort: EditText
    private lateinit var totalDurationMinutesInput: EditText
    private lateinit var device1EnabledCheckbox: CheckBox
    private lateinit var device2EnabledCheckbox: CheckBox
    private lateinit var selectAllCheckbox: CheckBox
    private lateinit var appSettingsTitle: TextView
    private lateinit var deviceTabs: TabLayout
    private lateinit var device1SettingsContainer: LinearLayout
    private lateinit var device2SettingsContainer: LinearLayout
    private lateinit var rowsContainer: LinearLayout
    private val importCsvLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importFrequenciesFromCsv(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        device1Ch1CarrierFrequencyInput = findViewById(R.id.carrierFrequencyInput)
        device1Ch1UsedFrequencySpinner = findViewById(R.id.usedFrequencySpinner)
        device1Ch1WaveTypeSpinner = findViewById(R.id.waveTypeSpinner)
        device1Ch1OffsetInput = findViewById(R.id.offsetInput)
        device1Ch1DutyCycleInput = findViewById(R.id.dutyCycleInput)
        device1Ch1AmplitudeInput = findViewById(R.id.amplitudeInput)
        device1Ch2CarrierFrequencyInput = findViewById(R.id.ch2CarrierFrequencyInput)
        device1Ch2UsedFrequencySpinner = findViewById(R.id.ch2UsedFrequencySpinner)
        device1Ch2WaveTypeSpinner = findViewById(R.id.ch2WaveTypeSpinner)
        device1Ch2OffsetInput = findViewById(R.id.ch2OffsetInput)
        device1Ch2DutyCycleInput = findViewById(R.id.ch2DutyCycleInput)
        device1Ch2AmplitudeInput = findViewById(R.id.ch2AmplitudeInput)
        device2Ch1CarrierFrequencyInput = findViewById(R.id.device2CarrierFrequencyInput)
        device2Ch1UsedFrequencySpinner = findViewById(R.id.device2UsedFrequencySpinner)
        device2Ch1WaveTypeSpinner = findViewById(R.id.device2WaveTypeSpinner)
        device2Ch1OffsetInput = findViewById(R.id.device2OffsetInput)
        device2Ch1DutyCycleInput = findViewById(R.id.device2DutyCycleInput)
        device2Ch1AmplitudeInput = findViewById(R.id.device2AmplitudeInput)
        device2Ch2CarrierFrequencyInput = findViewById(R.id.device2Ch2CarrierFrequencyInput)
        device2Ch2UsedFrequencySpinner = findViewById(R.id.device2Ch2UsedFrequencySpinner)
        device2Ch2WaveTypeSpinner = findViewById(R.id.device2Ch2WaveTypeSpinner)
        device2Ch2OffsetInput = findViewById(R.id.device2Ch2OffsetInput)
        device2Ch2DutyCycleInput = findViewById(R.id.device2Ch2DutyCycleInput)
        device2Ch2AmplitudeInput = findViewById(R.id.device2Ch2AmplitudeInput)
        device1DeviceTypeSpinner = findViewById(R.id.deviceTypeSpinner)
        device2DeviceTypeSpinner = findViewById(R.id.device2DeviceTypeSpinner)
        device1FunctionGeneratorIp = findViewById(R.id.functionGeneratorIpInput)
        device1FunctionGeneratorPort = findViewById(R.id.functionGeneratorPortInput)
        device2FunctionGeneratorIp = findViewById(R.id.device2FunctionGeneratorIpInput)
        device2FunctionGeneratorPort = findViewById(R.id.device2FunctionGeneratorPortInput)
        totalDurationMinutesInput = findViewById(R.id.totalDurationMinutesInput)
        selectAllCheckbox = findViewById(R.id.selectAllCheckbox)
        device1EnabledCheckbox = findViewById(R.id.device1EnabledCheckbox)
        device2EnabledCheckbox = findViewById(R.id.device2EnabledCheckbox)
        appSettingsTitle = findViewById(R.id.appSettingsTitle)
        deviceTabs = findViewById(R.id.deviceTabs)
        device1SettingsContainer = findViewById(R.id.device1SettingsContainer)
        device2SettingsContainer = findViewById(R.id.device2SettingsContainer)
        rowsContainer = findViewById(R.id.rowsContainer)

        device1Ch1CarrierFrequencyInput.setText("3100000")
        device1Ch1OffsetInput.setText("0")
        device1Ch1DutyCycleInput.setText("50")
        device1Ch1AmplitudeInput.setText("2")
        device1Ch2CarrierFrequencyInput.setText("3100000")
        device1Ch2OffsetInput.setText("0")
        device1Ch2DutyCycleInput.setText("50")
        device1Ch2AmplitudeInput.setText("2")
        device2Ch1CarrierFrequencyInput.setText("3100000")
        device2Ch1OffsetInput.setText("0")
        device2Ch1DutyCycleInput.setText("50")
        device2Ch1AmplitudeInput.setText("2")
        device2Ch2CarrierFrequencyInput.setText("3100000")
        device2Ch2OffsetInput.setText("0")
        device2Ch2DutyCycleInput.setText("50")
        device2Ch2AmplitudeInput.setText("2")
        device1DeviceTypeSpinner.setSelection(0)
        device2DeviceTypeSpinner.setSelection(1)
        device1FunctionGeneratorIp.setText("192.168.1.22")
        device1FunctionGeneratorPort.setText("5025")
        device2FunctionGeneratorIp.setText("192.168.1.53")
        device2FunctionGeneratorPort.setText("5025")
        device1Ch1UsedFrequencySpinner.setSelection(1)
        device1Ch2UsedFrequencySpinner.setSelection(0)
        device2Ch1UsedFrequencySpinner.setSelection(0)
        device2Ch2UsedFrequencySpinner.setSelection(0)
        device1Ch1WaveTypeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            Sdg1000xTelnetClient.SUPPORTED_WAVE_SHAPES
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        device1Ch2WaveTypeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            Sdg1000xTelnetClient.SUPPORTED_WAVE_SHAPES
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        device2Ch1WaveTypeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            Sdg1000xTelnetClient.SUPPORTED_WAVE_SHAPES
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        device2Ch2WaveTypeSpinner.adapter = ArrayAdapter(
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
        deviceTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val isDevice1 = tab.position == 0
                applyDeviceTabVisibility(isDevice1)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        applyDeviceTabVisibility(isDevice1 = true)
        selectAllCheckbox.setOnCheckedChangeListener { _, isChecked ->
            for (i in 0 until rowsContainer.childCount) {
                val row = rowsContainer.getChildAt(i)
                row.findViewById<CheckBox>(R.id.enabledCheck).isChecked = isChecked
            }
        }

        device1Ch1CarrierFrequencyInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                refreshAllRows()
            }
        })
        device1Ch2CarrierFrequencyInput.addTextChangedListener(object : TextWatcher {
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
        val device1Enabled = device1EnabledCheckbox.isChecked
        val device2Enabled = device2EnabledCheckbox.isChecked
        if (!device1Enabled && !device2Enabled) {
            Toast.makeText(this, "No device enabled.", Toast.LENGTH_SHORT).show()
            return
        }

        val device1Ch1Mode = device1Ch1UsedFrequencySpinner.selectedItem?.toString() ?: "Actual"
        val device1Ch2Mode = device1Ch2UsedFrequencySpinner.selectedItem?.toString() ?: "Actual"
        val device2Ch1Mode = device2Ch1UsedFrequencySpinner.selectedItem?.toString() ?: "Actual"
        val device2Ch2Mode = device2Ch2UsedFrequencySpinner.selectedItem?.toString() ?: "Actual"

        val steps = mutableListOf<FrequencyStep>()
        for (i in 0 until rowsContainer.childCount) {
            val row = rowsContainer.getChildAt(i)
            val enabled = row.findViewById<CheckBox>(R.id.enabledCheck).isChecked
            if (!enabled) continue

            val actualText = row.findViewById<EditText>(R.id.actualInput).text.toString().trim()
            val ch1CalculatedText = row.findViewById<TextView>(R.id.ch1CalculatedText).text.toString().trim()
            val ch2CalculatedText = row.findViewById<TextView>(R.id.ch2CalculatedText).text.toString().trim()
            val durationText = row.findViewById<EditText>(R.id.durationInput).text.toString().trim()

            val actualValue = actualText.toDoubleOrNull()
            val ch1CalculatedValue = ch1CalculatedText.toDoubleOrNull()
            val ch2CalculatedValue = ch2CalculatedText.toDoubleOrNull()

            val device1Ch1Freq = if (device1Enabled) {
                if (device1Ch1Mode.equals("Actual", true)) actualValue else ch1CalculatedValue
            } else null
            val device1Ch2Freq = if (device1Enabled) {
                if (device1Ch2Mode.equals("Actual", true)) actualValue else ch2CalculatedValue
            } else null
            val device2Ch1Freq = if (device2Enabled) {
                if (device2Ch1Mode.equals("Actual", true)) actualValue else ch1CalculatedValue
            } else null
            val device2Ch2Freq = if (device2Enabled) {
                if (device2Ch2Mode.equals("Actual", true)) actualValue else ch2CalculatedValue
            } else null

            if (device1Enabled && (device1Ch1Freq == null || device1Ch2Freq == null)) continue
            if (device2Enabled && (device2Ch1Freq == null || device2Ch2Freq == null)) continue

            val durationSeconds = parseDurationSeconds(durationText) ?: continue
            if (durationSeconds <= 0) continue

            steps.add(
                FrequencyStep(
                    durationSeconds = durationSeconds,
                    device1Ch1SelectedFreq = device1Ch1Freq,
                    device1Ch2SelectedFreq = device1Ch2Freq,
                    device1Ch1FrequencyMode = device1Ch1Mode,
                    device1Ch2FrequencyMode = device1Ch2Mode,
                    device2Ch1SelectedFreq = device2Ch1Freq,
                    device2Ch2SelectedFreq = device2Ch2Freq,
                    device2Ch1FrequencyMode = device2Ch1Mode,
                    device2Ch2FrequencyMode = device2Ch2Mode
                )
            )
        }

        if (steps.isEmpty()) {
            Toast.makeText(this, "No enabled rows with valid calculated frequency and duration.", Toast.LENGTH_SHORT).show()
            return
        }

        val device1Host = device1FunctionGeneratorIp.text.toString().trim()
        val device1Port = device1FunctionGeneratorPort.text.toString().trim().toIntOrNull()
        val device2Host = device2FunctionGeneratorIp.text.toString().trim()
        val device2Port = device2FunctionGeneratorPort.text.toString().trim().toIntOrNull()
        if (device1Enabled && (device1Host.isBlank() || device1Port == null || device1Port !in 1..65535)) {
            Toast.makeText(this, "Device 1 FG IP/Port is invalid.", Toast.LENGTH_SHORT).show()
            return
        }
        if (device2Enabled && (device2Host.isBlank() || device2Port == null || device2Port !in 1..65535)) {
            Toast.makeText(this, "Device 2 FG IP/Port is invalid.", Toast.LENGTH_SHORT).show()
            return
        }

        Thread {
            var device1Client: DeviceClientContext? = null
            var device2Client: DeviceClientContext? = null
            try {
                if (device1Enabled) {
                    device1Client = createDeviceClient(
                        deviceType = device1DeviceTypeSpinner.selectedItem?.toString() ?: "SDG1000X",
                        host = device1Host,
                        port = device1Port ?: 5025
                    )
                    device1Client.connect()
                    device1Client.setOutput(1, true)
                    device1Client.setOutput(2, true)
                    device1Client.setWaveShape(1, device1Ch1WaveTypeSpinner.selectedItem?.toString() ?: "SINE")
                    device1Client.setAmplitude(1, device1Ch1AmplitudeInput.text.toString().toDoubleOrNull() ?: 2.0)
                    device1Client.setOffset(1, device1Ch1OffsetInput.text.toString().toDoubleOrNull() ?: 0.0)
                    device1Client.setDutyCycle(1, device1Ch1DutyCycleInput.text.toString().toDoubleOrNull() ?: 50.0)
                    device1Client.setWaveShape(2, device1Ch2WaveTypeSpinner.selectedItem?.toString() ?: "SINE")
                    device1Client.setAmplitude(2, device1Ch2AmplitudeInput.text.toString().toDoubleOrNull() ?: 2.0)
                    device1Client.setOffset(2, device1Ch2OffsetInput.text.toString().toDoubleOrNull() ?: 0.0)
                    device1Client.setDutyCycle(2, device1Ch2DutyCycleInput.text.toString().toDoubleOrNull() ?: 50.0)
                }

                if (device2Enabled) {
                    device2Client = createDeviceClient(
                        deviceType = device2DeviceTypeSpinner.selectedItem?.toString() ?: "SDG7000A",
                        host = device2Host,
                        port = device2Port ?: 5025
                    )
                    device2Client.connect()
                    device2Client.setOutput(1, true)
                    device2Client.setOutput(2, true)
                    device2Client.setWaveShape(1, device2Ch1WaveTypeSpinner.selectedItem?.toString() ?: "SINE")
                    device2Client.setAmplitude(1, device2Ch1AmplitudeInput.text.toString().toDoubleOrNull() ?: 2.0)
                    device2Client.setOffset(1, device2Ch1OffsetInput.text.toString().toDoubleOrNull() ?: 0.0)
                    device2Client.setDutyCycle(1, device2Ch1DutyCycleInput.text.toString().toDoubleOrNull() ?: 50.0)
                    device2Client.setWaveShape(2, device2Ch2WaveTypeSpinner.selectedItem?.toString() ?: "SINE")
                    device2Client.setAmplitude(2, device2Ch2AmplitudeInput.text.toString().toDoubleOrNull() ?: 2.0)
                    device2Client.setOffset(2, device2Ch2OffsetInput.text.toString().toDoubleOrNull() ?: 0.0)
                    device2Client.setDutyCycle(2, device2Ch2DutyCycleInput.text.toString().toDoubleOrNull() ?: 50.0)
                }

                for (step in steps) {
                    if (stopRequested) break
                    if (device1Enabled) {
                        device1Client?.setOutput(1, true)
                        device1Client?.setOutput(2, true)
                        device1Client?.setFrequency(1, step.device1Ch1SelectedFreq ?: continue)
                        device1Client?.setFrequency(2, step.device1Ch2SelectedFreq ?: continue)
                    }
                    if (device2Enabled) {
                        device2Client?.setOutput(1, true)
                        device2Client?.setOutput(2, true)
                        device2Client?.setFrequency(1, step.device2Ch1SelectedFreq ?: continue)
                        device2Client?.setFrequency(2, step.device2Ch2SelectedFreq ?: continue)
                    }

                    val device1Status = if (device1Enabled) {
                        "Device 1 CH1 ${String.format("%.2f", step.device1Ch1SelectedFreq)} Hz (${step.device1Ch1FrequencyMode}), " +
                            "CH2 ${String.format("%.2f", step.device1Ch2SelectedFreq)} Hz (${step.device1Ch2FrequencyMode})"
                    } else null
                    val device2Status = if (device2Enabled) {
                        "Device 2 CH1 ${String.format("%.2f", step.device2Ch1SelectedFreq)} Hz (${step.device2Ch1FrequencyMode}), " +
                            "CH2 ${String.format("%.2f", step.device2Ch2SelectedFreq)} Hz (${step.device2Ch2FrequencyMode})"
                    } else null

                    val shouldContinue = runCountdownPopup(
                        durationSeconds = step.durationSeconds,
                        device1Status = device1Status,
                        device2Status = device2Status
                    )
                    if (!shouldContinue) break
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
            } finally {
                runCatching { device1Client?.setOutput(1, false) }
                runCatching { device1Client?.setOutput(2, false) }
                runCatching { device2Client?.setOutput(1, false) }
                runCatching { device2Client?.setOutput(2, false) }
                runCatching { device1Client?.closeClient() }
                runCatching { device2Client?.closeClient() }
            }
        }.start()
    }

    private fun runCountdownPopup(
        durationSeconds: Long,
        device1Status: String?,
        device2Status: String?
    ): Boolean {
        var countdownDialog: AlertDialog? = null
        runOnUiThread {
            val statusText = buildString {
                if (!device1Status.isNullOrBlank()) appendLine(device1Status)
                if (!device2Status.isNullOrBlank()) appendLine(device2Status)
            }.trim()
            countdownDialog = AlertDialog.Builder(this)
                .setTitle("Run Status")
                .setMessage("$statusText\n\nRemaining: $durationSeconds s")
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
                val statusText = buildString {
                    if (!device1Status.isNullOrBlank()) appendLine(device1Status)
                    if (!device2Status.isNullOrBlank()) appendLine(device2Status)
                }.trim()
                countdownDialog?.setMessage("$statusText\n\nRemaining: $remaining s")
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
                ch1CalculatedText.text = calculateFrequency(actualInput.text.toString(), device1Ch1CarrierFrequencyInput)
                ch2CalculatedText.text = calculateFrequency(actualInput.text.toString(), device1Ch2CarrierFrequencyInput)
            }
        })

        actualInput.setText(actualValue)
        durationInput.setText(durationValue)
        enabledCheck.isChecked = enabledValue || selectAllCheckbox.isChecked
        ch1CalculatedText.text = calculateFrequency(actualValue, device1Ch1CarrierFrequencyInput)
        ch2CalculatedText.text = calculateFrequency(actualValue, device1Ch2CarrierFrequencyInput)
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
            ch1CalculatedText.text = calculateFrequency(actualInput.text.toString(), device1Ch1CarrierFrequencyInput)
            ch2CalculatedText.text = calculateFrequency(actualInput.text.toString(), device1Ch2CarrierFrequencyInput)
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

    private fun applyDeviceTabVisibility(isDevice1: Boolean) {
        // device2SettingsContainer is currently nested in device1SettingsContainer.
        // Toggle sibling visibility so only the chosen device settings are visible.
        for (i in 0 until device1SettingsContainer.childCount) {
            val child = device1SettingsContainer.getChildAt(i)
            if (child.id == R.id.device2SettingsContainer) {
                child.visibility = if (isDevice1) android.view.View.GONE else android.view.View.VISIBLE
            } else {
                child.visibility = if (isDevice1) android.view.View.VISIBLE else android.view.View.GONE
            }
        }
        appSettingsTitle.text = if (isDevice1) "Device 1 Settings" else "Device 2 Settings"
    }

    private fun createDeviceClient(
        deviceType: String,
        host: String,
        port: Int
    ): DeviceClientContext {
        return if (deviceType.equals("SDG7000A", ignoreCase = true)) {
            val client = Sdg7000aTelnetClient(
                host = host,
                port = port,
                connectTimeoutMs = 10_000,
                readTimeoutMs = 10_000
            )
            DeviceClientContext(
                connect = { client.connect() },
                setOutput = { channel, enabled -> client.setOutput(channel, enabled) },
                setWaveShape = { channel, wave -> client.setWaveShape(channel, wave) },
                setAmplitude = { channel, amp -> client.setAmplitude(channel, amp) },
                setOffset = { channel, offset -> client.setOffset(channel, offset) },
                setDutyCycle = { channel, duty -> client.setDutyCycle(channel, duty) },
                setFrequency = { channel, freq -> client.setFrequency(channel, freq) },
                closeClient = { client.close() }
            )
        } else {
            val client = Sdg1000xTelnetClient(
                host = host,
                port = port,
                connectTimeoutMs = 10_000,
                readTimeoutMs = 10_000
            )
            DeviceClientContext(
                connect = { client.connect() },
                setOutput = { channel, enabled -> client.setOutput(channel, enabled) },
                setWaveShape = { channel, wave -> client.setWaveShape(channel, wave) },
                setAmplitude = { channel, amp -> client.setAmplitude(channel, amp) },
                setOffset = { channel, offset -> client.setOffset(channel, offset) },
                setDutyCycle = { channel, duty -> client.setDutyCycle(channel, duty) },
                setFrequency = { channel, freq -> client.setFrequency(channel, freq) },
                closeClient = { client.close() }
            )
        }
    }
}
