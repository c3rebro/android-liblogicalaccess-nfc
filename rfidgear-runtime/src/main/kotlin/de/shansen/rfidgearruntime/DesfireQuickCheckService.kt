package de.shansen.rfidgearruntime

import de.shansen.rfcard.*

/**
 * Non-destructive DESFire inspection service modelled after RFIDGear's Quick Check.
 *
 * The service always tries public metadata access first. Application keys are only
 * attempted when the application exists but its metadata/file listing cannot be
 * obtained without authentication.
 */
class DesfireQuickCheckService {

    fun run(backend: CardBackend, config: DesfireQuickCheckConfig): DesfireQuickCheckReport {
        val warnings = mutableListOf<String>()

        val connect = backend.connect()
        if (!connect.isSuccess) {
            return DesfireQuickCheckReport.failed(connect.error, connect.message ?: "Unable to connect to card backend.")
        }

        return try {
            val identityResult = backend.identify()
            if (!identityResult.isSuccess || identityResult.value == null) {
                return DesfireQuickCheckReport.failed(
                    identityResult.error,
                    identityResult.message ?: "Unable to identify card."
                )
            }

            val identity = identityResult.value
            if (identity.technology != CardTechnology.MIFARE_DESFIRE) {
                return DesfireQuickCheckReport.failed(
                    CardError.PROTOCOL_CONSTRAINT,
                    "Detected card is not a MIFARE DESFire card."
                )
            }

            val version = backend.execute(DesfireGetVersion)
                .responseAs<CardResponse.DesfireVersion>()
                .also { if (it == null) warnings += "DESFire version information could not be read." }

            val freeMemory = backend.execute(DesfireGetFreeMemory)
                .responseAs<CardResponse.DesfireFreeMemory>()
                ?.bytes
                .also { if (it == null) warnings += "Free-memory information could not be read." }

            val directory = readApplicationDirectory(backend, config.piccKeys)
            if (directory.aids == null) {
                val reportWarnings = warnings.toMutableList()
                directory.message?.takeIf { it.isNotBlank() }?.let(reportWarnings::add)
                return DesfireQuickCheckReport(
                    identity = identity,
                    version = version,
                    freeMemoryBytes = freeMemory,
                    directoryAccess = directory.access,
                    directoryAuthenticatedWith = directory.key,
                    applications = emptyList(),
                    warnings = reportWarnings,
                    error = directory.error,
                    errorMessage = directory.message
                )
            }

            val applications = directory.aids
                .filter { it != 0 }
                .distinct()
                .sorted()
                .map { aid -> inspectApplication(backend, aid, config.keysForApplication(aid)) }

            DesfireQuickCheckReport(
                identity = identity,
                version = version,
                freeMemoryBytes = freeMemory,
                directoryAccess = directory.access,
                directoryAuthenticatedWith = directory.key,
                applications = applications,
                warnings = warnings
            )
        } finally {
            backend.disconnect()
        }
    }

    /**
     * Re-inspects a single known application with a caller supplied key set.
     * Useful for the UI flow "application exists -> listing denied -> define key -> retry".
     * The caller must keep the backend/card session connected for the whole call.
     */
    fun inspectApplication(
        backend: CardBackend,
        aid: Int,
        keys: List<DesfireQuickCheckKey>
    ): DesfireApplicationQuickCheck {
        require(aid in 1..0xFFFFFF) { "DESFire application ID must be 1..0xFFFFFF." }

        val publicSettings = backend.execute(
            DesfireReadApplicationSettings(
                appId = aid,
                key = null,
                authenticateBeforeRead = false
            )
        )
        val publicFiles = backend.execute(
            DesfireListFiles(
                appId = aid,
                key = null,
                authenticateBeforeRead = false
            )
        )

        val publicSettingsValue = publicSettings.responseAs<CardResponse.DesfireApplicationSettings>()
        val publicFileIds = publicFiles.responseAs<CardResponse.DesfireFileIds>()

        if (publicFileIds != null) {
            return buildApplicationResult(
                backend = backend,
                aid = aid,
                settings = publicSettingsValue,
                settingsAccess = if (publicSettingsValue != null) DesfireQuickCheckAccess.PUBLIC else DesfireQuickCheckAccess.UNAVAILABLE,
                fileIds = publicFileIds.fileIds,
                filesAccess = DesfireQuickCheckAccess.PUBLIC,
                selectedKey = null
            )
        }

        var lastError = publicFiles.error
        var lastMessage = publicFiles.message
        val attempted = mutableListOf<DesfireQuickCheckKeyRef>()

        for (candidate in keys.distinctBySecret()) {
            attempted += candidate.ref()

            val auth = backend.execute(DesfireAuthenticate(aid, candidate.key))
            if (!auth.isSuccess) {
                lastError = auth.error
                lastMessage = auth.message
                continue
            }

            val settingsWithKey = backend.execute(
                DesfireReadApplicationSettings(
                    appId = aid,
                    key = candidate.key,
                    authenticateBeforeRead = true
                )
            )
            val listWithKey = backend.execute(
                DesfireListFiles(
                    appId = aid,
                    key = candidate.key,
                    authenticateBeforeRead = true
                )
            )
            val fileIds = listWithKey.responseAs<CardResponse.DesfireFileIds>()
            if (fileIds != null) {
                val authenticatedSettings = settingsWithKey.responseAs<CardResponse.DesfireApplicationSettings>()
                return buildApplicationResult(
                    backend = backend,
                    aid = aid,
                    settings = authenticatedSettings ?: publicSettingsValue,
                    settingsAccess = when {
                        authenticatedSettings?.wasAuthenticated == true -> DesfireQuickCheckAccess.AUTHENTICATED
                        publicSettingsValue != null -> DesfireQuickCheckAccess.PUBLIC
                        else -> DesfireQuickCheckAccess.UNAVAILABLE
                    },
                    fileIds = fileIds.fileIds,
                    filesAccess = DesfireQuickCheckAccess.AUTHENTICATED,
                    selectedKey = candidate,
                    attemptedKeys = attempted
                )
            }

            lastError = listWithKey.error
            lastMessage = listWithKey.message
        }

        return DesfireApplicationQuickCheck(
            aid = aid,
            settings = publicSettingsValue?.toSnapshot(),
            settingsAccess = if (publicSettingsValue != null) DesfireQuickCheckAccess.PUBLIC else DesfireQuickCheckAccess.UNAVAILABLE,
            filesAccess = DesfireQuickCheckAccess.KEY_REQUIRED,
            authenticatedWith = null,
            files = emptyList(),
            attemptedKeys = attempted,
            error = lastError,
            message = lastMessage ?: if (keys.isEmpty()) {
                "Application exists, but file listing requires authentication. Define a key for this AID."
            } else {
                "Application exists, but none of the configured keys allowed file listing."
            }
        )
    }

    private fun buildApplicationResult(
        backend: CardBackend,
        aid: Int,
        settings: CardResponse.DesfireApplicationSettings?,
        settingsAccess: DesfireQuickCheckAccess,
        fileIds: List<Int>,
        filesAccess: DesfireQuickCheckAccess,
        selectedKey: DesfireQuickCheckKey?,
        attemptedKeys: List<DesfireQuickCheckKeyRef> = emptyList()
    ): DesfireApplicationQuickCheck {
        val files = fileIds.distinct().sorted().map { fileNo ->
            val result = backend.execute(
                DesfireReadFileSettings(
                    appId = aid,
                    fileNo = fileNo,
                    key = selectedKey?.key,
                    authenticateBeforeRead = selectedKey != null
                )
            )
            val value = result.responseAs<CardResponse.DesfireFileSettings>()
            if (value != null) {
                DesfireFileQuickCheck(
                    fileNo = fileNo,
                    settings = value,
                    access = when {
                        value.wasAuthenticated == true || selectedKey != null -> DesfireQuickCheckAccess.AUTHENTICATED
                        else -> DesfireQuickCheckAccess.PUBLIC
                    }
                )
            } else {
                DesfireFileQuickCheck(
                    fileNo = fileNo,
                    settings = null,
                    access = if (selectedKey == null) DesfireQuickCheckAccess.KEY_REQUIRED else DesfireQuickCheckAccess.DENIED,
                    error = result.error,
                    message = result.message
                )
            }
        }

        return DesfireApplicationQuickCheck(
            aid = aid,
            settings = settings?.toSnapshot(),
            settingsAccess = settingsAccess,
            filesAccess = filesAccess,
            authenticatedWith = selectedKey?.ref(),
            files = files,
            attemptedKeys = attemptedKeys
        )
    }

    private fun readApplicationDirectory(
        backend: CardBackend,
        keys: List<DesfireQuickCheckKey>
    ): DirectoryResult {
        val publicResult = backend.execute(DesfireListApplications())
        val publicIds = publicResult.responseAs<CardResponse.ApplicationIds>()
        if (publicIds != null) {
            return DirectoryResult(
                aids = publicIds.aids,
                access = DesfireQuickCheckAccess.PUBLIC,
                key = null
            )
        }

        var lastError = publicResult.error
        var lastMessage = publicResult.message

        for (candidate in keys.distinctBySecret()) {
            val result = backend.execute(DesfireListApplications(candidate.key))
            val ids = result.responseAs<CardResponse.ApplicationIds>()
            if (ids != null) {
                return DirectoryResult(
                    aids = ids.aids,
                    access = DesfireQuickCheckAccess.AUTHENTICATED,
                    key = candidate.ref()
                )
            }
            lastError = result.error
            lastMessage = result.message
        }

        return DirectoryResult(
            aids = null,
            access = DesfireQuickCheckAccess.KEY_REQUIRED,
            key = null,
            error = lastError,
            message = lastMessage ?: "Application directory listing requires a PICC key."
        )
    }

    private data class DirectoryResult(
        val aids: List<Int>?,
        val access: DesfireQuickCheckAccess,
        val key: DesfireQuickCheckKeyRef?,
        val error: CardError? = null,
        val message: String? = null
    )
}

enum class DesfireQuickCheckAccess {
    PUBLIC,
    AUTHENTICATED,
    KEY_REQUIRED,
    DENIED,
    UNAVAILABLE
}

/**
 * Human-labelled runtime key. toString intentionally never exposes key material.
 */
class DesfireQuickCheckKey(
    val label: String,
    val key: DesfireKey
) {
    init {
        require(label.isNotBlank()) { "Quick-check key label must not be blank." }
    }

    fun ref() = DesfireQuickCheckKeyRef(label, key.type, key.number)

    override fun toString(): String = "DesfireQuickCheckKey(label=$label, type=${key.type}, number=${key.number})"
}

data class DesfireQuickCheckKeyRef(
    val label: String,
    val type: DesfireKeyType,
    val number: Int
)

data class DesfireQuickCheckConfig(
    /** Candidate PICC keys used only if public application-directory listing is denied. */
    val piccKeys: List<DesfireQuickCheckKey> = emptyList(),
    /** Candidate application keys used for every AID after public access fails. */
    val defaultApplicationKeys: List<DesfireQuickCheckKey> = emptyList(),
    /** AID-specific candidates. These are always tried before global defaults. */
    val applicationKeys: Map<Int, List<DesfireQuickCheckKey>> = emptyMap()
) {
    init {
        require(applicationKeys.keys.all { it in 1..0xFFFFFF }) {
            "Application-key map contains an invalid DESFire AID."
        }
    }

    fun keysForApplication(aid: Int): List<DesfireQuickCheckKey> =
        (applicationKeys[aid].orEmpty() + defaultApplicationKeys).distinctBySecret()

    fun withApplicationKey(aid: Int, key: DesfireQuickCheckKey): DesfireQuickCheckConfig =
        copy(applicationKeys = applicationKeys + (aid to (applicationKeys[aid].orEmpty() + key).distinctBySecret()))
}

data class DesfireQuickCheckReport(
    val identity: CardIdentity?,
    val version: CardResponse.DesfireVersion?,
    val freeMemoryBytes: Int?,
    val directoryAccess: DesfireQuickCheckAccess,
    val directoryAuthenticatedWith: DesfireQuickCheckKeyRef?,
    val applications: List<DesfireApplicationQuickCheck>,
    val warnings: List<String> = emptyList(),
    val error: CardError? = null,
    val errorMessage: String? = null
) {
    val needsKeys: List<Int>
        get() = applications.filter { it.filesAccess == DesfireQuickCheckAccess.KEY_REQUIRED }.map { it.aid }

    companion object {
        fun failed(error: CardError, message: String) = DesfireQuickCheckReport(
            identity = null,
            version = null,
            freeMemoryBytes = null,
            directoryAccess = DesfireQuickCheckAccess.UNAVAILABLE,
            directoryAuthenticatedWith = null,
            applications = emptyList(),
            error = error,
            errorMessage = message
        )
    }
}

data class DesfireApplicationQuickCheck(
    val aid: Int,
    val settings: DesfireApplicationSettingsSnapshot?,
    val settingsAccess: DesfireQuickCheckAccess,
    val filesAccess: DesfireQuickCheckAccess,
    val authenticatedWith: DesfireQuickCheckKeyRef?,
    val files: List<DesfireFileQuickCheck>,
    val attemptedKeys: List<DesfireQuickCheckKeyRef> = emptyList(),
    val error: CardError? = null,
    val message: String? = null
)

data class DesfireApplicationSettingsSnapshot(
    val keySettings: Int,
    val maxKeys: Int?,
    val keyType: DesfireKeyType?
)

data class DesfireFileQuickCheck(
    val fileNo: Int,
    val settings: CardResponse.DesfireFileSettings?,
    val access: DesfireQuickCheckAccess,
    val error: CardError? = null,
    val message: String? = null
)

private inline fun <reified T : CardResponse> CardResult<CardResponse>.responseAs(): T? =
    if (isSuccess) value as? T else null

private fun CardResponse.DesfireApplicationSettings.toSnapshot() =
    DesfireApplicationSettingsSnapshot(keySettings, maxKeys, keyType)

private fun List<DesfireQuickCheckKey>.distinctBySecret(): List<DesfireQuickCheckKey> {
    val result = mutableListOf<DesfireQuickCheckKey>()
    for (candidate in this) {
        val duplicate = result.any { existing ->
            existing.key.type == candidate.key.type &&
                existing.key.number == candidate.key.number &&
                existing.key.bytes.contentEquals(candidate.key.bytes)
        }
        if (!duplicate) result += candidate
    }
    return result
}
