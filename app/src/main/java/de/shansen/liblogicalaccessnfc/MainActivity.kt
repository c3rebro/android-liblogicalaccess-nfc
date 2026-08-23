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
import de.shansen.rfidgearruntime.RfidGearAction
import de.shansen.rfidgearruntime.RfidGearTaskCompiler
import de.shansen.rfproject.RfExecutionPlanCompiler
import de.shansen.rfproject.RfProjectReader
import de.shansen.rfproject.RfProjectValidator
import de.shansen.rfproject.RfValidationSeverity

class MainActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    private lateinit var binding: ActivityMainBinding
    private var adapter: NfcAdapter? = null
    private val projectReader = RfProjectReader()
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
            else -> "Ready. Hold an ISO-DEP card near the phone."
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
                                    when (val action = compiled.action) {
                                        is RfidGearAction.Execute -> "SUPPORTED ${action.command.javaClass.simpleName}"
                                        is RfidGearAction.CheckApplicationExists -> "SUPPORTED AppExistCheck"
                                        is RfidGearAction.CheckApplicationKeyCount -> "SUPPORTED CheckAppKeyCount"
                                        is RfidGearAction.Unsupported -> "UNSUPPORTED ${action.reason}"
                                    }
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
                    appendLine("Dry preview only: no card operation is executed from project tasks yet.")
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
        val uid = tag.id.toHex()
        val techs = tag.techList.joinToString()

        val isoDep = IsoDep.get(tag)
        if (isoDep == null) {
            runOnUiThread {
                binding.status.text = "Tag detected, but no ISO-DEP support."
                binding.details.text = "UID: $uid\nTechnologies: $techs"
            }
            return
        }

        try {
            isoDep.connect()
            isoDep.timeout = 3000

            val transport = AndroidIsoDepTransport(isoDep)
            NativeBridge.attachTransport(transport)

            runOnUiThread {
                binding.status.text = "ISO-DEP tag connected."
                binding.details.text =
                    "UID: $uid\n" +
                    "Technologies: $techs\n" +
                    "Max transceive: ${isoDep.maxTransceiveLength}\n" +
                    "Native bridge: ${NativeBridge.version()}"
            }
        } catch (e: Exception) {
            runOnUiThread {
                binding.status.text = "NFC error: ${e.message}"
            }
        } finally {
            try { isoDep.close() } catch (_: Exception) {}
            NativeBridge.detachTransport()
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02X".format(it) }
}
