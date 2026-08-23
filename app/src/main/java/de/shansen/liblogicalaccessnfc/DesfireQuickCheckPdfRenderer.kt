package de.shansen.liblogicalaccessnfc

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import de.shansen.rfidgearruntime.DesfireQuickCheckReportDocument
import de.shansen.rfidgearruntime.DesfireQuickCheckTextRenderer
import java.io.OutputStream

/**
 * Lightweight Android-framework PDF renderer for secret-free Quick Check report documents.
 * No third-party PDF dependency is required.
 */
class DesfireQuickCheckPdfRenderer {
    fun write(document: DesfireQuickCheckReportDocument, output: OutputStream) {
        val pdf = PdfDocument()
        try {
            val lines = DesfireQuickCheckTextRenderer.lines(document)
            var pageNumber = 0
            var page: PdfDocument.Page? = null
            var y = 0f

            fun startPage() {
                page?.let(pdf::finishPage)
                pageNumber++
                val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
                page = pdf.startPage(info)
                y = TOP_MARGIN
            }

            fun ensureSpace(height: Float) {
                if (page == null || y + height > PAGE_HEIGHT - BOTTOM_MARGIN) startPage()
            }

            startPage()

            lines.forEachIndexed { index, rawLine ->
                if (rawLine.isBlank()) {
                    ensureSpace(BODY_LINE_HEIGHT)
                    y += BODY_LINE_HEIGHT / 2f
                    return@forEachIndexed
                }

                val style = styleFor(rawLine, index)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = style.textSize
                    typeface = style.typeface
                }
                val availableWidth = PAGE_WIDTH - LEFT_MARGIN - RIGHT_MARGIN
                val wrapped = wrap(rawLine, paint, availableWidth)
                wrapped.forEach { line ->
                    ensureSpace(style.lineHeight)
                    page!!.canvas.drawText(line, LEFT_MARGIN, y, paint)
                    y += style.lineHeight
                }
                if (style.addAfter > 0f) y += style.addAfter
            }

            page?.let {
                drawFooter(it, pageNumber)
                pdf.finishPage(it)
            }
            pdf.writeTo(output)
        } finally {
            pdf.close()
        }
    }

    private fun styleFor(line: String, index: Int): LineStyle = when {
        index == 0 -> LineStyle(18f, 24f, Typeface.create(Typeface.DEFAULT, Typeface.BOLD), 8f)
        line == "READ ONLY" -> LineStyle(11f, 16f, Typeface.create(Typeface.DEFAULT, Typeface.BOLD), 8f)
        line in SECTION_HEADINGS -> LineStyle(13f, 20f, Typeface.create(Typeface.DEFAULT, Typeface.BOLD), 2f)
        line.startsWith("AID 0x") -> LineStyle(12f, 18f, Typeface.create(Typeface.MONOSPACE, Typeface.BOLD), 2f)
        line.startsWith("Result:") || line.startsWith("Error:") ->
            LineStyle(11f, 17f, Typeface.create(Typeface.DEFAULT, Typeface.BOLD), 0f)
        else -> LineStyle(10f, BODY_LINE_HEIGHT, Typeface.MONOSPACE, 0f)
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (paint.measureText(text) <= maxWidth) return listOf(text)

        val result = mutableListOf<String>()
        var remaining = text
        val leadingSpaces = text.takeWhile { it == ' ' }
        while (remaining.isNotEmpty()) {
            var count = paint.breakText(remaining, true, maxWidth, null).coerceAtLeast(1)
            if (count < remaining.length) {
                val breakAt = remaining.substring(0, count).lastIndexOf(' ')
                if (breakAt > leadingSpaces.length) count = breakAt
            }
            val part = remaining.substring(0, count).trimEnd()
            result += part
            remaining = remaining.substring(count).trimStart().let {
                if (it.isEmpty() || leadingSpaces.isEmpty()) it else leadingSpaces + it
            }
        }
        return result
    }

    private fun drawFooter(page: PdfDocument.Page, pageNumber: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 8f
            typeface = Typeface.DEFAULT
        }
        page.canvas.drawText(
            "DESFire Quick Check - page $pageNumber",
            LEFT_MARGIN,
            PAGE_HEIGHT - 18f,
            paint
        )
    }

    private data class LineStyle(
        val textSize: Float,
        val lineHeight: Float,
        val typeface: Typeface,
        val addAfter: Float
    )

    companion object {
        private const val PAGE_WIDTH = 595
        private const val PAGE_HEIGHT = 842
        private const val LEFT_MARGIN = 40f
        private const val RIGHT_MARGIN = 40f
        private const val TOP_MARGIN = 48f
        private const val BOTTOM_MARGIN = 42f
        private const val BODY_LINE_HEIGHT = 14f

        private val SECTION_HEADINGS = setOf(
            "Card",
            "Environment",
            "Warnings"
        )
    }
}
