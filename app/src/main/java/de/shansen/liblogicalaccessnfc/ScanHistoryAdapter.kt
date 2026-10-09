package de.shansen.liblogicalaccessnfc

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import de.shansen.liblogicalaccessnfc.databinding.ItemScanHistoryBinding
import de.shansen.rfidgearruntime.DesfireQuickCheckReportStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScanHistoryAdapter(
    private val onExportPdf: (ScanHistoryItem) -> Unit,
    private val onToggleExpand: (ScanHistoryItem) -> Unit,
    private val onToggleRaw: (ScanHistoryItem) -> Unit,
    private val onAddKey: (Int) -> Unit = {},
    private val onRescanOnce: () -> Unit = {},
    private val onDismissSuggestions: (ScanHistoryItem) -> Unit = {},
) : ListAdapter<ScanHistoryItem, ScanHistoryAdapter.ViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ScanHistoryItem>() {
            override fun areItemsTheSame(old: ScanHistoryItem, new: ScanHistoryItem) =
                old.uid == new.uid && old.timestamp == new.timestamp
            override fun areContentsTheSame(old: ScanHistoryItem, new: ScanHistoryItem) =
                old == new
        }
    }

    inner class ViewHolder(val binding: ItemScanHistoryBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemScanHistoryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val b = holder.binding
        val ctx = b.root.context

        b.headerTitle.text = "${item.cardLabel} · ${item.uid}"
        b.headerSubtitle.text = formatTime(item.timestamp)
        b.chevron.text = if (item.isExpanded) "▼" else "▶"
        b.body.visibility = if (item.isExpanded) View.VISIBLE else View.GONE

        // Status chip in header (Quick Check items only)
        val doc = item.document
        if (doc != null) {
            val status = doc.result.status
            val (chipText, chipBg, chipText2) = when (status) {
                DesfireQuickCheckReportStatus.COMPLETE -> Triple(
                    "COMPLETE",
                    MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorTertiary),
                    MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorOnTertiary)
                )
                DesfireQuickCheckReportStatus.PARTIAL -> Triple(
                    "PARTIAL",
                    ContextCompat.getColor(ctx, R.color.color_warning),
                    ContextCompat.getColor(ctx, R.color.color_on_warning)
                )
                DesfireQuickCheckReportStatus.FAILED -> Triple(
                    "FAILED",
                    MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorError),
                    MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorOnError)
                )
            }
            b.statusChip.text = chipText
            b.statusChip.setTextColor(chipText2)
            b.statusChip.background = GradientDrawable().apply {
                setColor(chipBg)
                cornerRadius = ctx.resources.displayMetrics.density * 10
            }
            b.statusChip.visibility = View.VISIBLE
        } else {
            b.statusChip.visibility = View.GONE
        }

        if (item.isExpanded) {
            bindExpandedBody(item, b)
        }

        b.header.setOnClickListener { onToggleExpand(item) }
        b.exportPdf.setOnClickListener { onExportPdf(item) }
    }

    private fun bindExpandedBody(item: ScanHistoryItem, b: ItemScanHistoryBinding) {
        val ctx = b.root.context
        val doc = item.document

        if (doc != null) {
            // --- Structured section ---
            b.structuredSection.visibility = View.VISIBLE

            // STATUS row
            val (statusText, statusColor) = when (doc.result.status) {
                DesfireQuickCheckReportStatus.COMPLETE -> "COMPLETE" to
                    MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorTertiary)
                DesfireQuickCheckReportStatus.PARTIAL -> "PARTIAL — ${doc.result.keyRequiredAids.size} application(s) need a key" to
                    ContextCompat.getColor(ctx, R.color.color_partial)
                DesfireQuickCheckReportStatus.FAILED -> "FAILED: ${doc.result.errorName ?: "unknown error"}" to
                    MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorError)
            }
            b.rowStatus.text = statusText
            b.rowStatus.setTextColor(statusColor)

            // UID row
            b.rowUid.text = doc.card.uid.ifBlank { "—" }

            // TECHNOLOGY row
            b.rowTech.text = doc.card.technology

            // FREE MEM row
            val freeMemBytes = doc.card.freeMemoryBytes
            if (freeMemBytes != null) {
                b.rowMemory.text = "%,d bytes".format(freeMemBytes)
                b.rowMemoryGroup.visibility = View.VISIBLE
            } else {
                b.rowMemoryGroup.visibility = View.GONE
            }

            // APPLICATIONS row
            val appCount = doc.applications.size
            b.rowApps.text = when {
                appCount == 0 -> "none visible"
                appCount == 1 -> "1 found"
                else -> "$appCount found"
            }

            // AUTH REQUIRED row
            val keysNeeded = doc.result.keyRequiredAids
            b.rowAuth.text = if (keysNeeded.isEmpty()) "No" else "Yes — ${keysNeeded.size} application(s)"
            b.rowAuth.setTextColor(
                if (keysNeeded.isEmpty()) MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorOnSurface)
                else ContextCompat.getColor(ctx, R.color.color_partial)
            )

            // PICC KEY row
            val piccLabel = item.detectedPiccKeyLabel
            if (piccLabel != null) {
                b.rowPiccKey.text = piccLabel
                b.rowPiccKeyGroup.visibility = View.VISIBLE
            } else {
                b.rowPiccKeyGroup.visibility = View.GONE
            }

            // WARNINGS row
            if (doc.warnings.isNotEmpty()) {
                b.rowWarnings.text = doc.warnings.joinToString("\n") { "• $it" }
                b.rowWarningsGroup.visibility = View.VISIBLE
            } else {
                b.rowWarningsGroup.visibility = View.GONE
            }

            // --- KEY_REQUIRED suggestions ---
            val missingAids = doc.result.keyRequiredAids
            if (missingAids.isNotEmpty() && !item.suggestionsHidden) {
                b.suggestionsSection.visibility = View.VISIBLE
                b.suggestionsText.text = when {
                    missingAids.size == 1 ->
                        "AID 0x%06X could not be read: no matching key is configured.".format(missingAids[0])
                    else ->
                        "${missingAids.size} applications could not be read (AID ${missingAids.joinToString { "0x%06X".format(it) }}): no matching keys configured."
                }
                b.btnAddKey.setOnClickListener { onAddKey(missingAids[0]) }
                b.btnRescanOnce.setOnClickListener { onRescanOnce() }
                b.btnDismissSuggestions.setOnClickListener { onDismissSuggestions(item) }
            } else {
                b.suggestionsSection.visibility = View.GONE
            }

            // Raw toggle
            b.rawToggle.visibility = View.VISIBLE
            b.rawToggleLabel.text = if (item.isRawExpanded) "▲  Raw technical report" else "▶  Raw technical report"
            b.rawSection.visibility = if (item.isRawExpanded) View.VISIBLE else View.GONE

            b.rawToggle.setOnClickListener { onToggleRaw(item) }

            if (item.isRawExpanded) {
                bindRawSection(item, b)
            }
        } else {
            // Non-QC item: no structured section, show raw directly
            b.structuredSection.visibility = View.GONE
            b.suggestionsSection.visibility = View.GONE
            b.rawToggle.visibility = View.GONE
            b.rawSection.visibility = View.VISIBLE
            bindRawSection(item, b)
        }

        b.exportPdf.visibility = View.VISIBLE
    }

    private fun bindRawSection(item: ScanHistoryItem, b: ItemScanHistoryBinding) {
        val text = item.cardText()
        val styled = SpannableString(text)
        var offset = 0
        text.lines().forEach { line ->
            if (line == "Card" || line == "DESFire Quick Check Report" || line == "Warnings") {
                val end = offset + line.length
                styled.setSpan(StyleSpan(Typeface.BOLD), offset, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                styled.setSpan(TypefaceSpan("sans-serif"), offset, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                styled.setSpan(AbsoluteSizeSpan(14, true), offset, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val color = if (line == "Warnings") MaterialColors.getColor(b.details, com.google.android.material.R.attr.colorError)
                else ContextCompat.getColor(b.root.context, R.color.brand_blue_text)
                styled.setSpan(ForegroundColorSpan(color), offset, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            offset += line.length + 1
        }
        b.details.text = styled

        val envText = item.environmentText()
        b.environmentDetails.text = envText
        b.environmentSection.visibility = if (envText.isBlank()) View.GONE else View.VISIBLE
    }

    private fun formatTime(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}
