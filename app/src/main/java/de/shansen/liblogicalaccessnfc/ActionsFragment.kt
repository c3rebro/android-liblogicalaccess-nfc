package de.shansen.liblogicalaccessnfc

import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.color.MaterialColors
import de.shansen.liblogicalaccessnfc.databinding.FragmentActionsBinding
import de.shansen.rfidgearruntime.RfidGearAction
import de.shansen.rfidgearruntime.RfidGearActionSafetyPolicy
import de.shansen.rfidgearruntime.RfidGearTaskCompiler
import de.shansen.rfproject.RfExecutionPlanCompiler
import de.shansen.rfproject.RfProjectReader
import de.shansen.rfproject.RfProjectValidator
import de.shansen.rfproject.RfValidationSeverity
import kotlinx.coroutines.launch

class ActionsFragment : Fragment() {

    private var _binding: FragmentActionsBinding? = null
    private val binding get() = _binding!!

    private val sessionState by lazy { ViewModelProvider(requireActivity())[AppSessionState::class.java] }
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

        binding.rowQuickCheck.setOnClickListener { main.selectQuickCheckUseCase() }
        binding.rowRestoreTransport.setOnClickListener { main.selectRestoreTransportUseCase() }
        binding.rowFormat.setOnClickListener { main.selectFormatUseCase() }
        binding.rowFactoryReset.setOnClickListener { main.selectFactoryResetUseCase() }
        binding.openProject.setOnClickListener { main.launchOpenProject() }

        binding.armButton.setOnClickListener {
            val state = sessionState.uiState.value
            when (state.armState) {
                is ArmState.Armed -> main.disarm()
                is ArmState.Disarmed, is ArmState.PausedAfterBackground -> main.arm()
                else -> {} // Running / WaitingForRemoval: button disabled
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                sessionState.uiState.collect { state ->
                    binding.nfcStatus.text = state.nfcStatus
                    updateActionRows(state)
                    updateArmButton(state)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun updateActionRows(state: MainUiState) {
        val b = _binding ?: return
        b.radioQuickCheck.isChecked = state.selectedAction == ActiveScanUseCase.QUICK_CHECK
        b.radioRestoreTransport.isChecked = state.selectedAction == ActiveScanUseCase.RESTORE_TRANSPORT_CONFIG
        b.radioFormat.isChecked = state.selectedAction == ActiveScanUseCase.FORMAT
        b.radioFactoryReset.isChecked = state.selectedAction == ActiveScanUseCase.FACTORY_RESET
    }

    private fun updateArmButton(state: MainUiState) {
        val b = _binding ?: return
        val ctx = context ?: return
        val main = activity as? MainActivity ?: return

        val runMode = if (state.autorunEnabled) RunMode.AUTO_REPEAT else RunMode.MANUAL_ONE_SHOT
        val isDestructive = main.isDestructiveAction(state.selectedAction)

        val primaryColor = MaterialColors.getColor(b.armButton, com.google.android.material.R.attr.colorPrimary)
        val errorColor = MaterialColors.getColor(b.armButton, com.google.android.material.R.attr.colorError)
        val secondaryColor = MaterialColors.getColor(b.armButton, com.google.android.material.R.attr.colorSecondary)

        when (val armState = state.armState) {
            is ArmState.Running -> {
                b.armButton.isEnabled = false
                b.armButton.text = "Scanning…"
                b.armButton.backgroundTintList = ColorStateList.valueOf(primaryColor)
            }
            is ArmState.WaitingForRemoval -> {
                b.armButton.isEnabled = false
                b.armButton.text = "Remove card from field…"
                b.armButton.backgroundTintList = ColorStateList.valueOf(primaryColor)
            }
            is ArmState.Armed -> {
                b.armButton.isEnabled = true
                if (runMode == RunMode.AUTO_REPEAT) {
                    b.armButton.text = "Auto mode active · Stop"
                    b.armButton.backgroundTintList = ColorStateList.valueOf(errorColor)
                } else {
                    b.armButton.text = "Waiting for card… Cancel"
                    b.armButton.backgroundTintList = ColorStateList.valueOf(secondaryColor)
                }
            }
            is ArmState.PausedAfterBackground -> {
                b.armButton.isEnabled = true
                b.armButton.text = if (runMode == RunMode.AUTO_REPEAT) "Resume auto mode" else "Wait for next card"
                b.armButton.backgroundTintList = ColorStateList.valueOf(primaryColor)
            }
            is ArmState.Disarmed -> {
                b.armButton.isEnabled = true
                b.armButton.text = when {
                    runMode == RunMode.AUTO_REPEAT && isDestructive -> "Start auto mode — confirms each scan"
                    runMode == RunMode.AUTO_REPEAT -> "Start auto mode"
                    isDestructive -> "Wait for card — will confirm"
                    else -> "Wait for next card"
                }
                b.armButton.backgroundTintList = ColorStateList.valueOf(primaryColor)
            }
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
