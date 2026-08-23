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
        description = "Delete all applications and files from the selected DESFire PICC. PICC key configuration is retained by DESFire format semantics.",
        risk = BuiltInUseCaseRisk.DESTRUCTIVE
    )

    val all: List<BuiltInUseCaseDescriptor> = listOf(desfireQuickCheck, desfireFormat)
}
