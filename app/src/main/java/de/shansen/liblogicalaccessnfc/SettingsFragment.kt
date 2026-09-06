package de.shansen.liblogicalaccessnfc

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import de.shansen.liblogicalaccessnfc.databinding.DialogPiccMasterKeyBinding
import de.shansen.liblogicalaccessnfc.databinding.FragmentSettingsBinding
import de.shansen.rfcard.DesfireKey
import de.shansen.rfcard.DesfireKeyType
import de.shansen.rfidgearruntime.DesfireQuickCheckKeyFactory

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

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
        binding.versionInfo.text = "Native bridge: ${NativeBridge.version()}"

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
        dialogBinding.keyType.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            keyTypes.map { keyTypeLabel(it) }
        )

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("PICC master key")
            .setView(dialogBinding.root)
            .setPositiveButton("Set", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dialogBinding.label.error = null
                dialogBinding.keyHex.error = null

                val label = dialogBinding.label.text.toString()
                val keyHex = dialogBinding.keyHex.text.toString().trim()
                val type = keyTypes[dialogBinding.keyType.selectedItemPosition]

                val result = runCatching {
                    require(label.isNotBlank()) { "Key label is required." }
                    val hexBytes = hexToBytes(keyHex, type)
                    DesfireKey(bytes = hexBytes, type = type, number = 0, version = 0)
                }

                result.onSuccess { key ->
                    main.setPiccKey(label, key)
                    updateKeySummaries()
                    dialog.dismiss()
                }.onFailure { error ->
                    val msg = error.message ?: "Invalid key."
                    if (msg.contains("label", ignoreCase = true)) {
                        dialogBinding.label.error = msg
                    } else {
                        dialogBinding.keyHex.error = msg
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
}
