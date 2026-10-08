package de.shansen.liblogicalaccessnfc

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import de.shansen.liblogicalaccessnfc.databinding.FragmentActionsBinding
import de.shansen.rfidgearruntime.RfidGearAction
import de.shansen.rfidgearruntime.RfidGearActionSafetyPolicy
import de.shansen.rfidgearruntime.RfidGearTaskCompiler
import de.shansen.rfproject.RfExecutionPlanCompiler
import de.shansen.rfproject.RfProjectReader
import de.shansen.rfproject.RfProjectValidator
import de.shansen.rfproject.RfValidationSeverity
import de.shansen.rfusecase.BuiltInUseCaseCatalog

class ActionsFragment : Fragment() {

    private var _binding: FragmentActionsBinding? = null
    private val binding get() = _binding!!

    private val projectReader = RfProjectReader()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentActionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val main = requireActivity() as MainActivity

        binding.selectQuickCheckUseCase.setOnClickListener {
            main.selectQuickCheckUseCase()
        }
        binding.selectRestoreTransportUseCase.setOnClickListener {
            main.selectRestoreTransportUseCase()
        }
        binding.selectFormatUseCase.setOnClickListener {
            main.selectFormatUseCase()
        }
        binding.selectFactoryResetUseCase.setOnClickListener {
            main.selectFactoryResetUseCase()
        }
        binding.openProject.setOnClickListener {
            main.launchOpenProject()
        }

        updateUseCaseSummary()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    fun updateStatus(message: String) {
        _binding?.nfcStatus?.text = message
    }

    fun updateUseCaseSummary() {
        val main = activity as? MainActivity ?: return
        val b = _binding ?: return
        val ctx = context ?: return

        val errorColor = com.google.android.material.color.MaterialColors.getColor(
            b.activeUseCaseAccent, com.google.android.material.R.attr.colorError)
        val (accentColor, summaryText) = when (main.activeScanUseCase) {
            MainActivity.ActiveScanUseCase.QUICK_CHECK ->
                ContextCompat.getColor(ctx, R.color.brand_blue) to
                    "Quick Check  —  read-only"
            MainActivity.ActiveScanUseCase.RESTORE_TRANSPORT_CONFIG ->
                ContextCompat.getColor(ctx, R.color.brand_amber) to
                    "Restore Transport Config  —  write (non-destructive)"
            MainActivity.ActiveScanUseCase.FORMAT ->
                errorColor to "Format DESFire card  —  DESTRUCTIVE"
            MainActivity.ActiveScanUseCase.FACTORY_RESET ->
                errorColor to "Factory Reset DESFire card  —  DESTRUCTIVE"
        }
        b.activeUseCaseAccent.setBackgroundColor(accentColor)
        b.activeUseCaseSummary.text = summaryText

        val buttonDefs = listOf(
            MainActivity.ActiveScanUseCase.QUICK_CHECK to
                (b.selectQuickCheckUseCase to "Quick Check (read only)"),
            MainActivity.ActiveScanUseCase.RESTORE_TRANSPORT_CONFIG to
                (b.selectRestoreTransportUseCase to "Restore PICC transport config"),
            MainActivity.ActiveScanUseCase.FORMAT to
                (b.selectFormatUseCase to "Format DESFire card (destructive)"),
            MainActivity.ActiveScanUseCase.FACTORY_RESET to
                (b.selectFactoryResetUseCase to "Factory Reset DESFire card (destructive)")
        )
        buttonDefs.forEach { (useCase, pair) ->
            val (button, label) = pair
            button.text = if (useCase == main.activeScanUseCase) "▶  $label" else label
        }
    }

    fun updateProjectSummary(text: String) {
        _binding?.projectSummary?.text = text
    }

    fun loadProjectFromUri(uri: Uri) {
        val main = requireActivity() as MainActivity
        binding.projectSummary.text = "Loading project..."
        Thread {
            val result = runCatching {
                val sourceName = getDisplayName(uri)
                val project = main.contentResolver.openInputStream(uri)?.use {
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

            requireActivity().runOnUiThread {
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
        val main = requireActivity() as MainActivity
        main.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index)
        }
        return uri.lastPathSegment
    }
}
