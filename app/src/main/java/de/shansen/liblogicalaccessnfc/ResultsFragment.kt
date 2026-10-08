package de.shansen.liblogicalaccessnfc

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
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
        adapter = ScanHistoryAdapter(scanHistory, { main.exportScanPdf(it) }, { persistHistory() })
        binding.exportAllScans.setOnClickListener { main.exportAllScansPdf(scanHistory.toList()) }
        binding.clearHistory.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Clear scan history?")
                .setMessage("All stored scan results will be deleted. Saved keys are kept.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear") { _, _ ->
                    scanHistory.clear(); persistHistory(); adapter.notifyDataSetChanged(); updateEmptyState()
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
        adapter.notifyDataSetChanged()
        binding.scanHistoryList.scrollToPosition(0)
        updateEmptyState()
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
