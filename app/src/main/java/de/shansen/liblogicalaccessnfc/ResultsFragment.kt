package de.shansen.liblogicalaccessnfc

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import de.shansen.liblogicalaccessnfc.databinding.FragmentResultsBinding

class ResultsFragment : Fragment() {

    private var _binding: FragmentResultsBinding? = null
    private val binding get() = _binding!!

    private var scanHistory = mutableListOf<ScanHistoryItem>()
    private lateinit var store: ScanHistoryStore
    private lateinit var adapter: ScanHistoryAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentResultsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val main = requireActivity() as MainActivity
        store = ScanHistoryStore(requireContext())
        runCatching { store.load() }.onSuccess { scanHistory = it }.onFailure {
            android.widget.Toast.makeText(requireContext(), "Scan history could not be read.", android.widget.Toast.LENGTH_LONG).show()
        }

        adapter = ScanHistoryAdapter(
            onExportPdf = { main.exportScanPdf(it) },
            onToggleExpand = { item ->
                val idx = scanHistory.indexOfFirst { it.uid == item.uid && it.timestamp == item.timestamp }
                if (idx >= 0) {
                    scanHistory[idx].isExpanded = !scanHistory[idx].isExpanded
                    persistHistory()
                    submitHistory()
                }
            },
            onToggleRaw = { item ->
                val idx = scanHistory.indexOfFirst { it.uid == item.uid && it.timestamp == item.timestamp }
                if (idx >= 0) {
                    scanHistory[idx].isRawExpanded = !scanHistory[idx].isRawExpanded
                    submitHistory()
                }
            },
            onAddKey = { aid -> main.showAddQuickCheckKeyDialog(aid) },
            onRescanOnce = { main.rescanOnce() },
            onDismissSuggestions = { item ->
                val idx = scanHistory.indexOfFirst { it.uid == item.uid && it.timestamp == item.timestamp }
                if (idx >= 0) {
                    scanHistory[idx].suggestionsHidden = true
                    submitHistory()
                }
            }
        )
        submitHistory()

        binding.exportAllScans.setOnClickListener { main.exportAllScansPdf(scanHistory.toList()) }
        binding.clearHistory.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.confirm_clear_history_title)
                .setMessage(R.string.confirm_clear_history_message)
                .setNegativeButton(R.string.btn_cancel, null)
                .setPositiveButton(R.string.btn_clear) { _, _ ->
                    scanHistory.clear()
                    persistHistory()
                    adapter.submitList(emptyList())
                    updateEmptyState()
                }.show()
        }
        binding.scanHistoryList.layoutManager = LinearLayoutManager(requireContext())
        binding.scanHistoryList.adapter = adapter

        updateEmptyState()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    fun addScanResult(item: ScanHistoryItem) {
        scanHistory.forEach { it.isExpanded = false }
        item.isExpanded = true
        scanHistory.add(0, item)
        persistHistory()
        submitHistory()
        binding.scanHistoryList.scrollToPosition(0)
        updateEmptyState()
    }

    private fun submitHistory() {
        adapter.submitList(scanHistory.map { it.copy() })
    }

    private fun persistHistory() {
        runCatching { store.save(scanHistory) }.onFailure {
            android.widget.Toast.makeText(requireContext(), "History could not be saved. Check device storage.", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun updateEmptyState() {
        val b = _binding ?: return
        b.exportAllScans.isEnabled = scanHistory.isNotEmpty()
        b.clearHistory.isEnabled = scanHistory.isNotEmpty()
        b.emptyHint.visibility = if (scanHistory.isEmpty()) View.VISIBLE else View.GONE
    }
}
