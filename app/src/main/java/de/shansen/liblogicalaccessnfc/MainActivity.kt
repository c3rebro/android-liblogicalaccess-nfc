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
import de.shansen.rfidgearruntime.DesfireQuickCheckConfig
import de.shansen.rfidgearruntime.DesfireQuickCheckKeyFactory
import de.shansen.rfidgearruntime.DesfireQuickCheckReportDocument
import de.shansen.rfidgearruntime.DesfireQuickCheckReportDocumentFactory
import de.shansen.rfidgearruntime.DesfireQuickCheckReportEnvironment
import de.shansen.rfidgearruntime.DesfireQuickCheckService
import de.shansen.rfidgearruntime.DesfireQuickCheckTextRenderer
import de.shansen.rfidgearruntime.RfidGearAction
import de.shansen.rfidgearruntime.RfidGearActionSafetyPolicy
import de.shansen.rfidgearruntime.RfidGearTaskCompiler
import de.shansen.rfproject.RfExecutionPlanCompiler
import de.shansen.rfproject.RfProjectReader
import de.shansen.rfproject.RfProjectValidator
import de.shansen.rfproject.RfValidationSeverity
import de.shansen.rfusecase.BuiltInUseCaseCatalog
import de.shansen.rfusecase.DesfireFormatPreflight
import de.shansen.rfusecase.DesfireFormatUseCase
import java.time.OffsetDateTime

class MainActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    private enum class ActiveScanUseCase {
        QUICK_CHECK,
        FORMAT_PREFLIGHT
    }

    private lateinit var binding: ActivityMainBinding
    private var adapter: NfcAdapter? = null
    private val projectReader = RfProjectReader()
    private val quickCheckService = DesfireQuickCheckService()
    private val formatUseCase = DesfireFormatUseCase()
    private var quickCheckConfig = DesfireQuickCheckConfig()
    private var lastQuickCheckDocument: DesfireQuickCheckReportDocument? = null
    private var lastFormatPreflight: DesfireFormatPreflight? = null
    private var activeScanUseCase = ActiveScanUseCase.QUICK_CHECK

    private val openProject = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadProject(uri)
    }

    private val createQuickCheckPdf = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val document = lastQuickCheckDocument ?: return@registerForActivityResult

        Thread {
            val result = runCatching {
                contentResolver.openOutputStream(uri)?.use { output ->
                    DesfireQuickCheckPdfRenderer().write(document, output)
                } ?: error("Unable to open the selected PDF destination.")
            }
            runOnUiThread {
                result.onSuccess {
                    binding.status.text = "Quick Check PDF exported."
                }.onFailure { error ->
                    binding.status.text = "PDF export failed: ${error.message ?: error.javaClass.simpleName}"
                }
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.selectQuickCheckUseCase.setOnClickListener {
            selectQuickCheckUseCase()
        }
        binding.selectFormatUseCase.setOnClickListener {
            selectFormatPreflightUseCase()
        }
        binding.openProject.setOnClickListener {
            openProject.launch(arrayOf("*/*"))
        }
        binding.addQuickCheckKey.setOnClickListener {
            showAddQuickCheckKeyDialog()
        }
        binding.clearQuickCheckKeys.setOnClickListener {
            clearSessionQuickCheckKeys()
        }
        binding.exportQuickCheckPdf.setOnClickListener {
            val document = lastQuickCheckDocument
            if (document == null) {
                binding.status.text = "Run a DESFire Quick Check before exporting a PDF."
            } else {
                val uid = document.card.uid.ifBlank { "unknown" }
                createQuickCheckPdf.launch("desfire-quick-check-$uid.pdf")
            }
        }
        updateQuickCheckKeySummary()
        updateActiveUseCaseSummary()

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

    private fun selectQuickCheckUseCase() {
        activeScanUseCase = ActiveScanUseCase.QUICK_CHECK
        updateActiveUseCaseSummary()
        binding.status.text = "Quick Check selected. Hold a DESFire card near the phone."
    }

    private fun selectFormatPreflightUseCase() {
        activeScanUseCase = ActiveScanUseCase.FORMAT_PREFLIGHT
        lastFormatPreflight = null
        updateActiveUseCaseSummary()
        binding.status.text =
            "Format preflight selected. Present the DESFire card to inspect it. No format command will be sent."
    }

    private fun updateActiveUseCaseSummary() {
        binding.activeUseCaseSummary.text = when (activeScanUseCase) {
            ActiveScanUseCase.QUICK_CHECK ->
                "Active use case: ${BuiltInUseCaseCatalog.desfireQuickCheck.title} [READ ONLY]"
            ActiveScanUseCase.FORMAT_PREFLIGHT ->
                "Active use case: ${BuiltInUseCaseCatalog.desfireFormat.title} [DESTRUCTIVE] - preflight only"
        }
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
                                    RfidGearActionSafetyPolicy.evaluate(
                                        compiled.action,
                                        ::currentAndroidBackendSupports
                                    ).previewLine()
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
                    appendLine("Project execution is still disabled; built-in DESFire use cases are independent of .rfPrj execution.")
                }
            }

            runOnUiThread {
                binding.projectSummary.text = result.getOrElse { error ->
                    "Project load failed:\n${error.message ?: error.javaClass.simpleName}"
                }
            }
        }.start()
    }

    private fun currentAndroidBackendSupports(action: RfidGearAction): Boolean = when (action) {
        is RfidGearAction.Execute -> NativeDesfireCardBackend.supports(action.command)
        else -> false
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
        val techList = tag.techList.toList()
        val techs = techList.joinToString()

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

            if (activeScanUseCase == ActiveScanUseCase.FORMAT_PREFLIGHT) {
                runFormatPreflight(tag, uidText)
                return
            }

            runQuickCheck(tag, isoDep, techList)
        } catch (e: Exception) {
            runOnUiThread {
                binding.status.text = "NFC/use-case error: ${e.message}"
                binding.details.text = "UID: $uidText\nTechnologies: $techs\nNative bridge: ${NativeBridge.version()}"
            }
        } finally {
            NativeBridge.detachTransport()
            try { isoDep.close() } catch (_: Exception) {}
        }
    }

    private fun runQuickCheck(tag: Tag, isoDep: IsoDep, techList: List<String>) {
        runOnUiThread {
            binding.status.text = "DESFire Quick Check running... keep the card in the NFC field."
        }

        val report = quickCheckService.run(
            backend = NativeDesfireCardBackend(tag.id),
            config = quickCheckConfig
        )
        val document = DesfireQuickCheckReportDocumentFactory.from(
            report = report,
            generatedAt = OffsetDateTime.now().toString(),
            environment = DesfireQuickCheckReportEnvironment(
                nfcTechnologies = techList,
                maxTransceiveLength = isoDep.maxTransceiveLength,
                backendVersion = NativeBridge.version()
            )
        )

        runOnUiThread {
            lastQuickCheckDocument = document
            binding.exportQuickCheckPdf.isEnabled = true
            binding.details.text = DesfireQuickCheckTextRenderer.render(document)

            val firstMissingKeyAid = report.needsKeys.firstOrNull()
            val reportError = report.error
            binding.status.text = when {
                reportError != null -> "Quick Check failed: ${report.errorMessage ?: reportError.rfidGearName}"
                firstMissingKeyAid != null -> "Quick Check partial: AID 0x%06X requires authentication.".format(firstMissingKeyAid)
                else -> "Quick Check complete (read-only)."
            }

            if (firstMissingKeyAid != null && !isFinishing) {
                showAddQuickCheckKeyDialog(firstMissingKeyAid)
            }
        }
    }

    private fun runFormatPreflight(tag: Tag, uidText: String) {
        runOnUiThread {
            binding.status.text = "DESFire format preflight running (read only)... keep the card in the NFC field."
        }

        val result = formatUseCase.preflight(NativeDesfireCardBackend(tag.id))

        runOnUiThread {
            // A destructive use case must never stay armed after one scan. A future execution
            // stage will require a fresh explicit confirmation after this preflight.
            activeScanUseCase = ActiveScanUseCase.QUICK_CHECK
            updateActiveUseCaseSummary()

            if (!result.isSuccess) {
                binding.status.text = "Format preflight failed: ${result.message ?: result.error.rfidGearName}"
                binding.details.text = "UID: $uidText\nNo format command was sent."
                return@runOnUiThread
            }

            val preflight = result.value
            if (preflight == null) {
                binding.status.text = "Format preflight failed: backend returned no preflight result."
                binding.details.text = "UID: $uidText\nNo format command was sent."
                return@runOnUiThread
            }

            lastFormatPreflight = preflight
            binding.status.text = "Format preflight complete (read only). No format command was sent."
            binding.details.text = formatFormatPreflight(preflight)
            showFormatPreflightDialog(preflight)
        }
    }

    private fun formatFormatPreflight(preflight: DesfireFormatPreflight): String = buildString {
        appendLine("DESFire Format Preflight (READ ONLY)")
        appendLine("UID: ${preflight.identity.uid.toHex()}")
        val version = preflight.version
        appendLine("Version: HW ${version.hardwareMajor}.${version.hardwareMinor}, SW ${version.softwareMajor}.${version.softwareMinor}")

        val applicationIds = preflight.visibleApplicationIds
        when {
            applicationIds == null ->
                appendLine("Applications: protected/unavailable during public preflight")
            applicationIds.isEmpty() ->
                appendLine("Applications: none visible")
            else -> {
                appendLine("Applications: ${applicationIds.size}")
                applicationIds.sorted().forEach { aid ->
                    appendLine("  - AID 0x%06X".format(aid))
                }
            }
        }

        if (preflight.warnings.isNotEmpty()) {
            appendLine("Warnings:")
            preflight.warnings.forEach { appendLine("  - $it") }
        }
        appendLine()
        appendLine("Confirmation phrase for a future format step:")
        appendLine(preflight.confirmationPhrase)
        appendLine()
        appendLine("FORMAT_PICC execution is not enabled in the Android native backend yet.")
    }.trimEnd()

    private fun showFormatPreflightDialog(preflight: DesfireFormatPreflight) {
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle("DESFire format preflight")
            .setMessage(
                "Read-only preflight completed for UID ${preflight.identity.uid.toHex()}.\n\n" +
                    "Future destructive confirmation phrase:\n${preflight.confirmationPhrase}\n\n" +
                    "The native FORMAT_PICC execution path is intentionally not enabled yet. No card data was changed."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02X".format(it) }
}
