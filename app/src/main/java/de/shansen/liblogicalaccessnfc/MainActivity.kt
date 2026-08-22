package de.shansen.liblogicalaccessnfc

import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import de.shansen.liblogicalaccessnfc.databinding.ActivityMainBinding
import de.shansen.rfproject.RfExecutionPlanCompiler
import de.shansen.rfproject.RfProjectReader
import de.shansen.rfproject.RfProjectValidator
import de.shansen.rfproject.RfValidationSeverity

class MainActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    private lateinit var binding: ActivityMainBinding
    private var adapter: NfcAdapter? = null
    private val projectReader = RfProjectReader()

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
                        append("[${step.position}] id=${step.id} ${step.modelType}")
                        append(" :: ${step.operation ?: "(no operation)"}")
                        step.description?.takeIf { it.isNotBlank() }?.let { append(" :: $it") }
                        appendLine()
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
