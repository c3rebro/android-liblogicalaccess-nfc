package de.shansen.liblogicalaccessnfc

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
import androidx.recyclerview.widget.RecyclerView
import de.shansen.liblogicalaccessnfc.databinding.ItemScanHistoryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScanHistoryAdapter(
    private val items: MutableList<ScanHistoryItem>,
    private val onExportPdf: (ScanHistoryItem) -> Unit,
    private val onChanged: () -> Unit
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
            val text = item.cardText()
            val styled = SpannableString(text)
            var offset = 0
            text.lines().forEach { line ->
                if (line == "Card" || line == "DESFire Quick Check Report" || line == "Warnings") {
                    val end = offset + line.length
                    styled.setSpan(StyleSpan(Typeface.BOLD), offset, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    styled.setSpan(TypefaceSpan("sans-serif"), offset, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    styled.setSpan(AbsoluteSizeSpan(14, true), offset, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    val color = if (line == "Warnings") com.google.android.material.color.MaterialColors.getColor(
                        b.details, com.google.android.material.R.attr.colorError)
                    else ContextCompat.getColor(b.root.context, R.color.brand_blue_text)
                    styled.setSpan(ForegroundColorSpan(color), offset, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                offset += line.length + 1
            }
            b.details.text = styled
            b.environmentDetails.text = item.environmentText()
            b.environmentSection.visibility = if (item.environmentText().isBlank()) View.GONE else View.VISIBLE
            b.exportPdf.visibility = View.VISIBLE
        }

        b.header.setOnClickListener {
            item.isExpanded = !item.isExpanded
            val index = holder.bindingAdapterPosition
            if (index != RecyclerView.NO_POSITION) notifyItemChanged(index)
            onChanged()
        }

        b.exportPdf.setOnClickListener {
            onExportPdf(item)
        }
    }

    override fun getItemCount(): Int = items.size

    private fun formatTime(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}
