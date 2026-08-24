package de.shansen.rfusecase

enum class BuiltInUseCaseRisk {
    READ_ONLY,
    MUTATING,
    DESTRUCTIVE
}

data class BuiltInUseCaseDescriptor(
    val id: String,
    val title: String,
    val description: String,
    val risk: BuiltInUseCaseRisk
)

object BuiltInUseCaseCatalog {
    val desfireQuickCheck = BuiltInUseCaseDescriptor(
        id = "desfire-quick-check",
        title = "DESFire Quick Check",
        description = "Inspect DESFire applications, file metadata and access settings without modifying the card.",
        risk = BuiltInUseCaseRisk.READ_ONLY
    )

    val desfireFormat = BuiltInUseCaseDescriptor(
        id = "desfire-format",
        title = "Format DESFire card",
        description = "Execute the destructive DESFire FORMAT_PICC operation after a read-only preflight and explicit confirmation of the card UID.",
        risk = BuiltInUseCaseRisk.DESTRUCTIVE
    )

    val desfireFactoryReset = BuiltInUseCaseDescriptor(
        id = "desfire-factory-reset",
        title = "Factory Reset DESFire card",
        description = "Format the DESFire PICC and restore PICC master key #0 to the factory DES zero key (16 zero bytes / 32 hex zeros).",
        risk = BuiltInUseCaseRisk.DESTRUCTIVE
    )

    val all: List<BuiltInUseCaseDescriptor> = listOf(
        desfireQuickCheck,
        desfireFormat,
        desfireFactoryReset
    )
}
