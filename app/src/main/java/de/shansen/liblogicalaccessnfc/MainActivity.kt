package de.shansen.liblogicalaccessnfc

import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import de.shansen.liblogicalaccessnfc.databinding.ActivityMainBinding
import de.shansen.liblogicalaccessnfc.databinding.DialogDesfireQuickCheckKeyBinding
import de.shansen.rfcard.DesfireKeyType
import de.shansen.rfidgearruntime.DesfireApplicationQuickCheck
import de.shansen.rfidgearruntime.DesfireQuickCheckAccess
import de.shansen.rfidgearruntime.DesfireQuickCheckConfig
import de.shansen.rfidgearruntime.DesfireQuickCheckKeyFactory
import de.shansen.rfidgearruntime.DesfireQuickCheckReport
import de.shansen.rfidgearruntime.DesfireQuickCheckService
import de.shansen.rfidgearruntime.RfidGearActionSafetyPolicy
import de.shansen.rfidgearruntime.RfidGearTaskCompiler
import de.shansen.rfproject.RfExecutionPlanCompiler
import de.shansen.rfproject.RfProjectReader
import de.shansen.rfproject.RfProjectValidator
import de.shansen.rfproject.RfValidationSeverity

class MainActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    private lateinit var binding: ActivityMainBinding
    private var adapter: NfcAdapter? = null
    private val projectReader = RfProjectReader()
    private val quickCheckService = DesfireQuickCheckService()
    private var quickCheckConfig = DesfireQuickCheckConfig()

    private val openProject = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadProject(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.openProject.setOnClickListener {
            openProject.launch(arrayOf("*/*"))
        }
        binding.addQuickCheckKey.setOnClickListener {
            showAddQuickCheckKeyDialog()
        }
        binding.clearQuickCheckKeys.setOnClickListener {
            clearSessionQuickCheckKeys()
        }
        updateQuickCheckKeySummary()

        adapter = NfcAdapter.getDefaultAdapter(this)
        binding.status.text = when {
            adapter == null -> "This device has no NFC adapter."
            adapter?.isEnabled != true -> "NFC is disabled."
            else -> "Ready. Hold a DESFire card near the phone for read-only Quick Check."
        }

        binding.details.text = "Native bridge: ${NativeBridge.version()}"
    }

    override fun onResume() {
        super.onResume()
        adapter?.enableReaderMode(
            this,
            this,
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            Bundle().apply {
                putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250)
            }
        )
    }

    override fun onPause() {
        adapter?.disableReaderMode(this)
        super.onPause()
    }

    private fun showAddQuickCheckKeyDialog(prefillAid: Int? = null) {
        val dialogBinding = DialogDesfireQuickCheckKeyBinding.inflate(layoutInflater)
        val keyTypes = listOf(
            DesfireKeyType.AES,
            DesfireKeyType.TDES_3K,
            DesfireKeyType.DES
        )
        dialogBinding.keyType.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            keyTypes.map(::keyTypeLabel)
        )
        prefillAid?.let { dialogBinding.aid.setText("0x%06X".format(it)) }

        val dialog = AlertDialog.Builder(this)
            .setTitle("DESFire application key")
            .setView(dialogBinding.root)
            .setPositiveButton("Add", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val result = runCatching {
                    val aid = DesfireQuickCheckKeyFactory.parseAid(dialogBinding.aid.text.toString())
                    val keyNo = dialogBinding.keyNumber.text.toString().trim().toIntOrNull()
                        ?: throw IllegalArgumentException("Key number must be a decimal number between 0 and 15.")
                    val type = keyTypes[dialogBinding.keyType.selectedItemPosition]
                    val key = DesfireQuickCheckKeyFactory.fromHex(
                        label = dialogBinding.label.text.toString(),
                        keyHex = dialogBinding.keyHex.text.toString(),
                        type = type,
                        keyNumber = keyNo
                    )
                    aid to key
                }

                result.onSuccess { (aid, key) ->
                    quickCheckConfig = quickCheckConfig.withApplicationKey(aid, key)
                    updateQuickCheckKeySummary()
                    dialogBinding.keyHex.text?.clear()
                    binding.status.text = "Key added for AID 0x%06X. Present the card again to retry Quick Check.".format(aid)
                    dialog.dismiss()
                }.onFailure { error ->
                    dialogBinding.keyHex.error = error.message ?: "Invalid DESFire key."
                }
            }
        }
        dialog.show()
    }

    private fun clearSessionQuickCheckKeys() {
        quickCheckConfig.applicationKeys.values.flatten().forEach { it.key.clear() }
        quickCheckConfig.defaultApplicationKeys.forEach { it.key.clear() }
        quickCheckConfig.piccKeys.forEach { it.key.clear() }
        quickCheckConfig = DesfireQuickCheckConfig()
        updateQuickCheckKeySummary()
    }

    private fun updateQuickCheckKeySummary() {
        if (quickCheckConfig.applicationKeys.isEmpty()) {
            binding.quickCheckKeySummary.text =
                "No application-specific quick-check keys configured.\nKeys are session-only."
            return
        }

        binding.quickCheckKeySummary.text = buildString {
            appendLine("Quick-check application keys (session-only):")
            quickCheckConfig.applicationKeys.toSortedMap().forEach { (aid, keys) ->
                appendLine("AID 0x%06X".format(aid))
                keys.forEach { key ->
                    appendLine("  - ${key.ref().label} [${keyTypeLabel(key.key.type)} key #${key.key.number}]")
                }
            }
        }.trimEnd()
    }

    private fun keyTypeLabel(type: DesfireKeyType): String = when (type) {
        DesfireKeyType.AES -> "AES"
        DesfireKeyType.TDES_3K -> "3K3DES"
        DesfireKeyType.DES -> "DES / 2K3DES"
    }

    private fun loadProject(uri: Uri) {
        binding.projectSummary.text = "Loading project..."
        Thread {
            val result = runCatching {
                val sourceName = getDisplayName(uri)
                val project = contentResolver.openInputStream(uri)?.use {
                    projectReader.read(it, sourceName)
                } ?: error("Unable to open selected project file.")

                val validation = RfProjectValidator.validate(project)
                val plan = if (!validation.hasErrors) RfExecutionPlanCompiler.compile(project) else null

                buildString {
                    appendLine("Project: ${sourceName ?: uri.lastPathSegment ?: "unknown"}")
                    appendLine("Container: ${project.container}")
                    appendLine("Manifest: ${project.manifestVersion ?: "missing"}")
                    appendLine("Tasks: ${project.tasks.size}")
                    appendLine()

                    plan?.steps?.forEach { step ->
                        val projectTask = project.tasks[step.position]
                        val compileStatus = runCatching { RfidGearTaskCompiler.compile(projectTask) }
                            .fold(
                                onSuccess = { compiled ->
                                    RfidGearActionSafetyPolicy.evaluate(compiled.action).previewLine()
                                },
                                onFailure = { error -> "INVALID ${error.message ?: error.javaClass.simpleName}" }
                            )

                        append("[${step.position}] id=${step.id} ${step.modelType}")
                        append(" :: ${step.operation ?: "(no operation)"}")
                        step.description?.takeIf { it.isNotBlank() }?.let { append(" :: $it") }
                        appendLine()
                        appendLine("    Android: $compileStatus")
                        step.condition?.let {
                            appendLine("    when task ${it.sourceTaskId} -> ${it.expectedError}")
                        }
                    }

                    if (validation.issues.isNotEmpty()) {
                        appendLine()
                        appendLine("Validation:")
                        validation.issues.forEach { issue ->
                            val prefix = when (issue.severity) {
                                RfValidationSeverity.ERROR -> "ERROR"
                                RfValidationSeverity.WARNING -> "WARN"
                                RfValidationSeverity.INFO -> "INFO"
                            }
                            appendLine("$prefix ${issue.code}: ${issue.message}")
                        }
                    } else {
                        appendLine()
                        appendLine("Validation: OK")
                    }

                    appendLine()
                    appendLine("Project execution is still disabled; DESFire Quick Check is read-only and independent of project execution.")
                }
            }

            runOnUiThread {
                binding.projectSummary.text = result.getOrElse { error ->
                    "Project load failed:\n${error.message ?: error.javaClass.simpleName}"
                }
            }
        }.start()
    }

    private fun getDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index)
        }
        return uri.lastPathSegment
    }

    override fun onTagDiscovered(tag: Tag) {
        val uidText = tag.id.toHex()
        val techs = tag.techList.joinToString()

        val isoDep = IsoDep.get(tag)
        if (isoDep == null) {
            runOnUiThread {
                binding.status.text = "Tag detected, but no ISO-DEP support."
                binding.details.text = "UID: $uidText\nTechnologies: $techs"
            }
            return
        }

        try {
            isoDep.connect()
            isoDep.timeout = 5000

            val transport = AndroidIsoDepTransport(isoDep)
            NativeBridge.attachTransport(transport)

            runOnUiThread {
                binding.status.text = "DESFire Quick Check running... keep the card in the NFC field."
            }

            val report = quickCheckService.run(
                backend = NativeDesfireCardBackend(tag.id),
                config = quickCheckConfig
            )

            runOnUiThread {
                binding.details.text = formatQuickCheckReport(report, uidText, techs, isoDep.maxTransceiveLength)
                val firstMissingKeyAid = report.needsKeys.firstOrNull()
                binding.status.text = when {
                    report.error != null -> "Quick Check failed: ${report.errorMessage ?: report.error.rfidGearName}"
                    firstMissingKeyAid != null -> "Quick Check partial: AID 0x%06X requires authentication.".format(firstMissingKeyAid)
                    else -> "Quick Check complete (read-only)."
                }

                if (firstMissingKeyAid != null && !isFinishing) {
                    showAddQuickCheckKeyDialog(firstMissingKeyAid)
                }
            }
        } catch (e: Exception) {
            runOnUiThread {
                binding.status.text = "NFC/Quick Check error: ${e.message}"
                binding.details.text = "UID: $uidText\nTechnologies: $techs\nNative bridge: ${NativeBridge.version()}"
            }
        } finally {
            NativeBridge.detachTransport()
            try { isoDep.close() } catch (_: Exception) {}
        }
    }

    private fun formatQuickCheckReport(
        report: DesfireQuickCheckReport,
        uidText: String,
        techs: String,
        maxTransceive: Int
    ): String = buildString {
        appendLine("DESFire Quick Check (READ ONLY)")
        appendLine("UID: $uidText")
        appendLine("Technologies: $techs")
        appendLine("Max transceive: $maxTransceive")
        appendLine("Native bridge: ${NativeBridge.version()}")

        report.version?.let { version ->
            appendLine(
                "Version: HW ${version.hardwareMajor}.${version.hardwareMinor}, " +
                    "SW ${version.softwareMajor}.${version.softwareMinor}, " +
                    "storage=0x%02X".format(version.hardwareStorageSize)
            )
            appendLine("Production: week=0x%02X year=%02d".format(version.productionWeek ?: 0, version.productionYear ?: 0))
        }
        report.freeMemoryBytes?.let { appendLine("Free memory: $it bytes") }

        append("Application directory: ${report.directoryAccess}")
        report.directoryAuthenticatedWith?.let { append(" using ${it.label} [${keyTypeLabel(it.type)} #${it.number}]") }
        appendLine()

        if (report.applications.isEmpty()) {
            appendLine("Applications: none visible")
        } else {
            appendLine("Applications: ${report.applications.size}")
            report.applications.forEach { app -> appendApplication(app) }
        }

        if (report.warnings.isNotEmpty()) {
            appendLine("Warnings:")
            report.warnings.forEach { appendLine("  - $it") }
        }
        report.error?.let {
            appendLine("ERROR ${it.rfidGearName}: ${report.errorMessage.orEmpty()}")
        }
    }.trimEnd()

    private fun StringBuilder.appendApplication(app: DesfireApplicationQuickCheck) {
        appendLine()
        appendLine("AID 0x%06X (%d)".format(app.aid, app.aid))
        appendLine("  Settings: ${app.settingsAccess}")
        app.settings?.let { settings ->
            appendLine("    Key settings: 0x%02X".format(settings.keySettings))
            settings.maxKeys?.let { appendLine("    Max keys: $it") }
            settings.keyType?.let { appendLine("    Key type: ${keyTypeLabel(it)}") }
        }
        append("  File listing: ${app.filesAccess}")
        app.authenticatedWith?.let { append(" using ${it.label} [${keyTypeLabel(it.type)} #${it.number}]") }
        appendLine()

        if (app.files.isEmpty()) {
            appendLine("    Files: none/read denied")
        } else {
            app.files.forEach { file ->
                append("    File ${file.fileNo}: ${file.access}")
                file.settings?.let { settings ->
                    append(" ${settings.fileType}")
                    settings.size?.let { append(" size=$it") }
                    append(" ${settings.communicationMode}")
                    append(" R=${accessRight(settings.accessRights.read)}")
                    append(" W=${accessRight(settings.accessRights.write)}")
                    append(" RW=${accessRight(settings.accessRights.readWrite)}")
                    append(" C=${accessRight(settings.accessRights.change)}")
                }
                file.message?.takeIf { it.isNotBlank() }?.let { append(" [$it]") }
                appendLine()
            }
        }

        if (app.attemptedKeys.isNotEmpty()) {
            appendLine("  Tried keys:")
            app.attemptedKeys.forEach { appendLine("    - ${it.label} [${keyTypeLabel(it.type)} #${it.number}]") }
        }
        app.message?.takeIf { it.isNotBlank() }?.let { appendLine("  Note: $it") }
    }

    private fun accessRight(value: Int): String = when (value) {
        0x0E -> "FREE"
        0x0F -> "NEVER"
        else -> "KEY$value"
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02X".format(it) }
}
