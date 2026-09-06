package de.shansen.liblogicalaccessnfc

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import de.shansen.liblogicalaccessnfc.databinding.ItemScanHistoryBinding
import de.shansen.rfidgearruntime.DesfireQuickCheckTextRenderer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScanHistoryAdapter(
    private val items: MutableList<ScanHistoryItem>,
    private val onExportPdf: (ScanHistoryItem) -> Unit
) : RecyclerView.Adapter<ScanHistoryAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemScanHistoryBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemScanHistoryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val b = holder.binding

        b.headerTitle.text = "${item.cardLabel} · ${item.uid}"
        b.headerSubtitle.text = formatTime(item.timestamp)

        b.chevron.text = if (item.isExpanded) "▼" else "►"
        b.body.visibility = if (item.isExpanded) View.VISIBLE else View.GONE

        if (item.isExpanded) {
            b.details.text = buildDetailsText(item)
            b.exportPdf.visibility = if (item.document != null) View.VISIBLE else View.GONE
        }

        b.header.setOnClickListener {
            item.isExpanded = !item.isExpanded
            notifyItemChanged(holder.bindingAdapterPosition)
        }

        b.exportPdf.setOnClickListener {
            onExportPdf(item)
        }
    }

    override fun getItemCount(): Int = items.size

    fun prepend(item: ScanHistoryItem) {
        items.add(0, item)
        notifyItemInserted(0)
    }

    private fun buildDetailsText(item: ScanHistoryItem): String = when {
        item.document != null -> buildString {
            item.detectedPiccKeyLabel?.let {
                appendLine("PICC master key: $it [auto-detected]")
                appendLine()
            }
            append(DesfireQuickCheckTextRenderer.render(item.document))
        }
        item.formatResult != null -> buildString {
            appendLine("Format result: ${item.formatResult.status.name}")
            item.formatResult.message?.let { appendLine(it) }
            if (item.formatResult.destructiveOperationInvoked) {
                appendLine("FORMAT_PICC was invoked.")
            }
            item.formatResult.remainingApplicationIds?.let { aids ->
                if (aids.isEmpty()) appendLine("Application directory: empty (verified).")
                else appendLine("Remaining AIDs: ${aids.map { "0x%06X".format(it) }}")
            }
        }.trimEnd()
        item.factoryResetResult != null -> buildString {
            appendLine("Factory Reset result: ${item.factoryResetResult.status.name}")
            item.factoryResetResult.message?.let { appendLine(it) }
            appendLine("FORMAT_PICC invoked: ${item.factoryResetResult.formatOperationInvoked}")
            appendLine("Key reset invoked: ${item.factoryResetResult.keyResetOperationInvoked}")
            item.factoryResetResult.remainingApplicationIds?.let { aids ->
                if (aids.isEmpty()) appendLine("Application directory: empty (verified).")
                else appendLine("Remaining AIDs: ${aids.map { "0x%06X".format(it) }}")
            }
        }.trimEnd()
        else -> "No details available."
    }

    private fun formatTime(timestamp: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}
