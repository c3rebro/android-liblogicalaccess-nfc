package de.shansen.liblogicalaccessnfc

import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import de.shansen.liblogicalaccessnfc.databinding.DialogPiccMasterKeyBinding
import de.shansen.liblogicalaccessnfc.databinding.FragmentSettingsBinding
import de.shansen.rfcard.DesfireKey
import de.shansen.rfcard.DesfireKeyType
import de.shansen.rfidgearruntime.DesfireQuickCheckKeyFactory
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val sessionState by lazy { ViewModelProvider(requireActivity())[AppSessionState::class.java] }
    private var suppressSwitchListener = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val main = requireActivity() as MainActivity

        binding.addQuickCheckKey.setOnClickListener {
            main.showAddQuickCheckKeyDialog()
        }
        binding.clearQuickCheckKeys.setOnClickListener {
            main.clearSessionQuickCheckKeys()
            updateKeySummaries()
        }
        binding.setPiccKey.setOnClickListener {
            showSetPiccKeyDialog()
        }
        binding.clearPiccKey.setOnClickListener {
            main.clearPiccKey()
            updateKeySummaries()
        }
        binding.autorunSwitch.isChecked = main.autorunEnabled
        binding.autorunSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressSwitchListener) return@setOnCheckedChangeListener
            if (isChecked) {
                val warning = main.checkAutorunReadiness()
                if (warning != null) {
                    android.widget.Toast.makeText(requireContext(), warning, android.widget.Toast.LENGTH_LONG).show()
                }
            }
            main.applyAutorun(isChecked, onDeclined = {
                suppressSwitchListener = true
                binding.autorunSwitch.isChecked = false
                suppressSwitchListener = false
            })
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                sessionState.uiState.collect { state ->
                    suppressSwitchListener = true
                    binding.autorunSwitch.isChecked = state.autorunEnabled
                    suppressSwitchListener = false
                    binding.autorunCurrentAction.text = "Current action: ${actionDisplayLabel(state.selectedAction)}"
                }
            }
        }

        binding.soundSwitch.isChecked = main.soundEnabled
        binding.soundSwitch.setOnCheckedChangeListener { _, isChecked ->
            main.soundEnabled = isChecked
        }

        binding.versionInfo.text = buildString {
            appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Commit: ${BuildConfig.GIT_COMMIT}")
            append("Native bridge: ${NativeBridge.version()}")
        }

        binding.exportLog.setOnClickListener { main.exportLog() }

        updateKeySummaries()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    fun updateKeySummaries() {
        val main = activity as? MainActivity ?: return
        val b = _binding ?: return
        b.quickCheckKeySummary.text = main.buildQuickCheckKeySummary()
        b.piccKeySummary.text = main.buildPiccKeySummary()
    }

    private fun showSetPiccKeyDialog() {
        val main = requireActivity() as MainActivity
        val dialogBinding = DialogPiccMasterKeyBinding.inflate(layoutInflater)
        val keyTypes = listOf(DesfireKeyType.AES, DesfireKeyType.TDES_3K, DesfireKeyType.DES)
        val keyTypeLabels = keyTypes.map { keyTypeLabel(it) }

        dialogBinding.keyType.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, keyTypeLabels)
        )
        dialogBinding.keyType.setText(keyTypeLabels[0], false)

        fun selectedKeyType() = keyTypes[keyTypeLabels.indexOf(dialogBinding.keyType.text.toString()).coerceAtLeast(0)]

        val hexFilter = InputFilter { source, _, _, _, _, _ ->
            val filtered = source.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
            if (filtered.length == source.length) null else filtered.toString()
        }
        fun updateHexCounter() {
            val max = if (selectedKeyType() == DesfireKeyType.TDES_3K) 48 else 32
            val len = dialogBinding.keyHex.text?.length ?: 0
            dialogBinding.keyHex.filters = arrayOf(hexFilter, InputFilter.LengthFilter(max))
            dialogBinding.hexCounter.text = "$len / $max hex chars"
            dialogBinding.hexCounter.setTypeface(null,
                if (len == max) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        updateHexCounter()
        dialogBinding.keyHex.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { updateHexCounter() }
        })
        dialogBinding.keyType.setOnItemClickListener { _, _, _, _ -> updateHexCounter() }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_title_picc_key)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.btn_set, null)
            .setNegativeButton(R.string.btn_cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dialogBinding.tilLabel.error = null
                dialogBinding.tilKeyHex.error = null

                val label = dialogBinding.label.text.toString()
                val keyHex = dialogBinding.keyHex.text.toString().trim()
                val type = selectedKeyType()

                val result = runCatching {
                    require(label.isNotBlank()) { "Key label is required." }
                    val hexBytes = hexToBytes(keyHex, type)
                    DesfireKey(bytes = hexBytes, type = type, number = 0, version = 0)
                }

                result.onSuccess { key ->
                    val saved = runCatching { main.setPiccKey(label, key, dialogBinding.savePermanently.isChecked) }
                    if (saved.isFailure) {
                        key.clear()
                        dialogBinding.tilKeyHex.error = "Unable to save securely. Try a session key instead."
                        return@onSuccess
                    }
                    updateKeySummaries()
                    dialog.dismiss()
                }.onFailure { error ->
                    val msg = error.message ?: "Invalid key."
                    if (msg.contains("label", ignoreCase = true)) {
                        dialogBinding.tilLabel.error = msg
                    } else {
                        dialogBinding.tilKeyHex.error = msg
                    }
                }
            }
        }
        dialog.show()
    }

    private fun hexToBytes(hex: String, type: DesfireKeyType): ByteArray {
        val clean = hex.replace("\\s".toRegex(), "")
        require(clean.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            "Key contains non-hexadecimal characters."
        }
        val expected = when (type) {
            DesfireKeyType.TDES_3K -> 48
            else -> 32
        }
        require(clean.length == expected) {
            "${keyTypeLabel(type)} key must contain exactly ${expected / 2} bytes ($expected hexadecimal characters)."
        }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun keyTypeLabel(type: DesfireKeyType): String = when (type) {
        DesfireKeyType.AES -> "AES"
        DesfireKeyType.TDES_3K -> "3K3DES"
        DesfireKeyType.DES -> "DES / 2K3DES"
    }

    private fun actionDisplayLabel(action: ActiveScanUseCase): String = when (action) {
        ActiveScanUseCase.QUICK_CHECK -> "Quick Check (read-only)"
        ActiveScanUseCase.RESTORE_TRANSPORT_CONFIG -> "Restore Transport Config"
        ActiveScanUseCase.FORMAT -> "Format DESFire card (destructive)"
        ActiveScanUseCase.FACTORY_RESET -> "Factory Reset (destructive)"
    }
}
