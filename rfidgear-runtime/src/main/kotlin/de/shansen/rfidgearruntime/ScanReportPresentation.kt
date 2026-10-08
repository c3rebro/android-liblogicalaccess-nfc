package de.shansen.rfidgearruntime

/** Reflows persisted secret-free reports as well as newly generated reports. */
object ScanReportPresentation {
    fun withPiccMasterKeyProbe(text: String, label: String?): String {
        if (label == null) return text
        val lines = text.lines().filterNot { it.startsWith("PICC master key probe:") }.toMutableList()
        val directory = lines.indexOfFirst { it.startsWith("Application directory:") }
        if (directory < 0) return text
        lines.add(directory + 1, "PICC master key probe: $label")
        return lines.joinToString("\n").trimStart()
    }
}
