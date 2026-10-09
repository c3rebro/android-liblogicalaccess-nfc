package de.shansen.liblogicalaccessnfc

import de.shansen.rfidgearruntime.DesfireQuickCheckTextRenderer
import de.shansen.rfidgearruntime.DesfireQuickCheckReportDocument
import de.shansen.rfusecase.DesfireFactoryResetResult
import de.shansen.rfusecase.DesfireFormatResult

data class ScanHistoryItem(
    val uid: String,
    val cardLabel: String,
    val timestamp: Long,
    val document: DesfireQuickCheckReportDocument? = null,
    val formatResult: DesfireFormatResult? = null,
    val factoryResetResult: DesfireFactoryResetResult? = null,
    val detectedPiccKeyLabel: String? = null,
    val savedCardText: String? = null,
    val savedEnvironmentText: String? = null,
    var isExpanded: Boolean = false,
    var isRawExpanded: Boolean = false,
    var suggestionsHidden: Boolean = false
)

fun ScanHistoryItem.cardText(): String {
    val item = this
    val raw = savedCardText ?: when {
        item.document != null -> DesfireQuickCheckTextRenderer.lines(item.document, includeReaderDetails = false).joinToString("\n")
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
    return de.shansen.rfidgearruntime.ScanReportPresentation.withPiccMasterKeyProbe(raw, detectedPiccKeyLabel)
}

fun ScanHistoryItem.environmentText(): String = savedEnvironmentText ?: document?.let { doc ->
    buildString {
        doc.card.detail?.let { appendLine("Backend: $it") }
        appendLine("NFC technologies: ${doc.environment.nfcTechnologies.joinToString()}")
        doc.environment.maxTransceiveLength?.let { appendLine("Max transceive: $it bytes") }
        doc.environment.backendVersion?.let { appendLine("Native bridge: $it") }
    }.trimEnd()
}.orEmpty()

fun ScanHistoryItem.pdfLines(): List<String> = buildList {
    add("${cardLabel} - ${uid}")
    add("Scanned: ${java.time.Instant.ofEpochMilli(timestamp)}")
    add("")
    add("Card results")
    addAll(cardText().lines())
    if (environmentText().isNotBlank()) {
        add(""); add("Reader / environment"); addAll(environmentText().lines())
    }
}
