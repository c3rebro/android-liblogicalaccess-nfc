package de.shansen.rfidgearruntime

import de.shansen.rfcard.DesfireKeyType

/**
 * Secret-free presentation model for DESFire Quick Check output.
 *
 * This model deliberately contains key labels/types/numbers only. It never contains DESFire
 * key bytes, which makes it safe to hand to text/PDF/JSON renderers.
 */
data class DesfireQuickCheckReportDocument(
    val generatedAt: String? = null,
    val card: DesfireQuickCheckReportCard,
    val directory: DesfireQuickCheckReportDirectory,
    val applications: List<DesfireQuickCheckReportApplication>,
    val warnings: List<String>,
    val result: DesfireQuickCheckReportResult,
    val environment: DesfireQuickCheckReportEnvironment = DesfireQuickCheckReportEnvironment()
)

data class DesfireQuickCheckReportCard(
    val uid: String,
    val technology: String,
    val detail: String? = null,
    val hardwareVersion: String? = null,
    val softwareVersion: String? = null,
    val storageCode: Int? = null,
    val productionWeek: Int? = null,
    val productionYear: Int? = null,
    val freeMemoryBytes: Int? = null
)

data class DesfireQuickCheckReportDirectory(
    val access: DesfireQuickCheckAccess,
    val authenticatedWith: DesfireQuickCheckKeyRef? = null
)

data class DesfireQuickCheckReportApplication(
    val aid: Int,
    val settingsAccess: DesfireQuickCheckAccess,
    val keySettings: Int? = null,
    val maxKeys: Int? = null,
    val keyType: DesfireKeyType? = null,
    val filesAccess: DesfireQuickCheckAccess,
    val authenticatedWith: DesfireQuickCheckKeyRef? = null,
    val files: List<DesfireQuickCheckReportFile>,
    val attemptedKeys: List<DesfireQuickCheckKeyRef> = emptyList(),
    val note: String? = null
)

data class DesfireQuickCheckReportFile(
    val fileNo: Int,
    val access: DesfireQuickCheckAccess,
    val authenticatedWith: DesfireQuickCheckKeyRef? = null,
    val fileType: String? = null,
    val size: Int? = null,
    val communicationMode: String? = null,
    val readAccess: String? = null,
    val writeAccess: String? = null,
    val readWriteAccess: String? = null,
    val changeAccess: String? = null,
    val note: String? = null
)

data class DesfireQuickCheckReportEnvironment(
    val nfcTechnologies: List<String> = emptyList(),
    val maxTransceiveLength: Int? = null,
    val backendVersion: String? = null
)

data class DesfireQuickCheckReportResult(
    val status: DesfireQuickCheckReportStatus,
    val errorName: String? = null,
    val message: String? = null,
    val keyRequiredAids: List<Int> = emptyList()
)

enum class DesfireQuickCheckReportStatus {
    COMPLETE,
    PARTIAL,
    FAILED
}

object DesfireQuickCheckReportDocumentFactory {
    fun from(
        report: DesfireQuickCheckReport,
        generatedAt: String? = null,
        environment: DesfireQuickCheckReportEnvironment = DesfireQuickCheckReportEnvironment()
    ): DesfireQuickCheckReportDocument {
        val uid = report.identity?.uid?.toHex().orEmpty()
        val version = report.version
        val needsKeys = report.needsKeys.distinct().sorted()

        val status = when {
            report.error != null -> DesfireQuickCheckReportStatus.FAILED
            needsKeys.isNotEmpty() || report.warnings.isNotEmpty() ||
                report.applications.any { app ->
                    app.settingsAccess !in setOf(DesfireQuickCheckAccess.PUBLIC, DesfireQuickCheckAccess.AUTHENTICATED) ||
                        app.files.any { it.settings == null }
                } -> DesfireQuickCheckReportStatus.PARTIAL
            else -> DesfireQuickCheckReportStatus.COMPLETE
        }

        return DesfireQuickCheckReportDocument(
            generatedAt = generatedAt,
            card = DesfireQuickCheckReportCard(
                uid = uid,
                technology = report.identity?.technology?.name ?: "UNKNOWN",
                detail = report.identity?.detail,
                hardwareVersion = version?.let { "${it.hardwareMajor}.${it.hardwareMinor}" },
                softwareVersion = version?.let { "${it.softwareMajor}.${it.softwareMinor}" },
                storageCode = version?.hardwareStorageSize,
                productionWeek = version?.productionWeek,
                productionYear = version?.productionYear,
                freeMemoryBytes = report.freeMemoryBytes
            ),
            directory = DesfireQuickCheckReportDirectory(
                access = report.directoryAccess,
                authenticatedWith = report.directoryAuthenticatedWith
            ),
            applications = report.applications.map { app ->
                DesfireQuickCheckReportApplication(
                    aid = app.aid,
                    settingsAccess = app.settingsAccess,
                    keySettings = app.settings?.keySettings,
                    maxKeys = app.settings?.maxKeys,
                    keyType = app.settings?.keyType,
                    filesAccess = app.filesAccess,
                    authenticatedWith = app.authenticatedWith,
                    files = app.files.map { file ->
                        val settings = file.settings
                        DesfireQuickCheckReportFile(
                            fileNo = file.fileNo,
                            access = file.access,
                            authenticatedWith = file.authenticatedWith,
                            fileType = settings?.fileType?.name,
                            size = settings?.size,
                            communicationMode = settings?.communicationMode?.name,
                            readAccess = settings?.accessRights?.read?.toAccessRight(),
                            writeAccess = settings?.accessRights?.write?.toAccessRight(),
                            readWriteAccess = settings?.accessRights?.readWrite?.toAccessRight(),
                            changeAccess = settings?.accessRights?.change?.toAccessRight(),
                            note = file.message
                        )
                    },
                    attemptedKeys = app.attemptedKeys,
                    note = app.message
                )
            },
            warnings = report.warnings,
            result = DesfireQuickCheckReportResult(
                status = status,
                errorName = report.error?.rfidGearName,
                message = report.errorMessage,
                keyRequiredAids = needsKeys
            ),
            environment = environment
        )
    }
}

object DesfireQuickCheckTextRenderer {
    fun render(document: DesfireQuickCheckReportDocument): String = lines(document).joinToString("\n")

    fun lines(document: DesfireQuickCheckReportDocument): List<String> = buildList {
        add("DESFire Quick Check Report")
        add("READ ONLY")
        document.generatedAt?.let { add("Generated: $it") }
        add("")
        add("Card")
        add("UID: ${document.card.uid.ifBlank { "unknown" }}")
        add("Technology: ${document.card.technology}")
        document.card.detail?.let { add("Backend: $it") }
        document.card.hardwareVersion?.let { add("Hardware version: $it") }
        document.card.softwareVersion?.let { add("Software version: $it") }
        document.card.storageCode?.let { add("Storage code: 0x%02X".format(it)) }
        document.card.productionWeek?.let { add("Production week: $it") }
        document.card.productionYear?.let { add("Production year: $it") }
        document.card.freeMemoryBytes?.let { add("Free memory: $it bytes") }

        if (document.environment.nfcTechnologies.isNotEmpty() ||
            document.environment.maxTransceiveLength != null ||
            document.environment.backendVersion != null
        ) {
            add("")
            add("Environment")
            if (document.environment.nfcTechnologies.isNotEmpty()) {
                add("NFC technologies: ${document.environment.nfcTechnologies.joinToString()}")
            }
            document.environment.maxTransceiveLength?.let { add("Max transceive: $it") }
            document.environment.backendVersion?.let { add("Native bridge: $it") }
        }

        add("")
        add("Application directory: ${document.directory.access}")
        document.directory.authenticatedWith?.let { add("Directory key: ${it.display()}") }

        add("")
        add("Applications: ${document.applications.size}")
        if (document.applications.isEmpty()) add("  none visible")

        document.applications.forEach { app ->
            add("")
            add("AID 0x%06X (%d)".format(app.aid, app.aid))
            add("  Settings: ${app.settingsAccess}")
            app.keySettings?.let { add("    Key settings: 0x%02X".format(it)) }
            app.maxKeys?.let { add("    Max keys: $it") }
            app.keyType?.let { add("    Key type: ${it.display()}") }
            add("  File listing: ${app.filesAccess}")
            app.authenticatedWith?.let { add("  Application key: ${it.display()}") }

            if (app.files.isEmpty()) {
                add("    Files: none/read denied")
            } else {
                app.files.forEach { file ->
                    val description = buildString {
                        append("    File ${file.fileNo}: ${file.access}")
                        file.fileType?.let { append(" $it") }
                        file.size?.let { append(" size=$it") }
                        file.communicationMode?.let { append(" $it") }
                        file.readAccess?.let { append(" R=$it") }
                        file.writeAccess?.let { append(" W=$it") }
                        file.readWriteAccess?.let { append(" RW=$it") }
                        file.changeAccess?.let { append(" C=$it") }
                    }
                    add(description)
                    file.authenticatedWith?.let { add("      Key: ${it.display()}") }
                    file.note?.takeIf(String::isNotBlank)?.let { add("      Note: $it") }
                }
            }

            if (app.attemptedKeys.isNotEmpty()) {
                add("  Tried keys:")
                app.attemptedKeys.forEach { add("    - ${it.display()}") }
            }
            app.note?.takeIf(String::isNotBlank)?.let { add("  Note: $it") }
        }

        if (document.warnings.isNotEmpty()) {
            add("")
            add("Warnings")
            document.warnings.forEach { add("- $it") }
        }

        add("")
        add("Result: ${document.result.status}")
        if (document.result.keyRequiredAids.isNotEmpty()) {
            add("Keys required for: ${document.result.keyRequiredAids.joinToString { "0x%06X".format(it) }}")
        }
        document.result.errorName?.let { add("Error: $it") }
        document.result.message?.takeIf(String::isNotBlank)?.let { add("Message: $it") }
    }
}

private fun DesfireQuickCheckKeyRef.display(): String =
    "$label [${type.display()} #$number]"

private fun DesfireKeyType.display(): String = when (this) {
    DesfireKeyType.AES -> "AES"
    DesfireKeyType.TDES_3K -> "3K3DES"
    DesfireKeyType.DES -> "DES / 2K3DES"
}

private fun Int.toAccessRight(): String = when (this) {
    0x0E -> "FREE"
    0x0F -> "NEVER"
    else -> "KEY$this"
}

private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
