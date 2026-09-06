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

    private val scanHistory = mutableListOf<ScanHistoryItem>()
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
        adapter = ScanHistoryAdapter(scanHistory) { item ->
            item.document?.let { main.exportQuickCheckPdf(it) }
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
        scanHistory.add(0, item)
        adapter.notifyItemInserted(0)
        binding.scanHistoryList.scrollToPosition(0)
        updateEmptyState()
    }

    private fun updateEmptyState() {
        val b = _binding ?: return
        b.emptyHint.visibility = if (scanHistory.isEmpty()) View.VISIBLE else View.GONE
    }
}
