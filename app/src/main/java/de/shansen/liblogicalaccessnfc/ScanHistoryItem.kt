package de.shansen.liblogicalaccessnfc

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
    var isExpanded: Boolean = false
)
