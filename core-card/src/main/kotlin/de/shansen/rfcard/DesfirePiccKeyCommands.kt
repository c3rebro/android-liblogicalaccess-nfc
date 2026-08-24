package de.shansen.rfcard

/**
 * Change the DESFire PICC master key (AID 0, key number 0).
 *
 * This is deliberately separate from the generic [DesfireChangeKey] command so built-in
 * destructive workflows do not need to manufacture application-key settings merely to change
 * the PICC master key. A native encoder backend is expected to implement this as:
 * selectApplication(0) -> authenticate(0, currentPiccMasterKey) -> changeKey(0, newPiccMasterKey).
 * The target key version, when relevant to the algorithm, is carried by [newPiccMasterKey].
 */
data class DesfireChangePiccMasterKey(
    val currentPiccMasterKey: DesfireKey,
    val newPiccMasterKey: DesfireKey
) : CardCommand {
    init {
        require(currentPiccMasterKey.number == 0) {
            "Current DESFire PICC master key must use key number 0."
        }
        require(newPiccMasterKey.number == 0) {
            "New DESFire PICC master key must use key number 0."
        }
    }

    /**
     * Compatibility constructor for callers that provide a version separately. The key object
     * remains the single source of truth; a conflicting version is rejected.
     */
    constructor(
        currentPiccMasterKey: DesfireKey,
        newPiccMasterKey: DesfireKey,
        newKeyVersion: Int
    ) : this(currentPiccMasterKey, newPiccMasterKey) {
        require(newKeyVersion in 0..0xFF) {
            "DESFire key version must be between 0 and 255."
        }
        require(newPiccMasterKey.version == null || newPiccMasterKey.version == newKeyVersion) {
            "New PICC master-key version must match newPiccMasterKey.version."
        }
    }
}

/** Factory defaults intentionally return fresh mutable key material for every call. */
object DesfireFactoryDefaults {
    const val PICC_MASTER_KEY_HEX_LENGTH: Int = 32

    /**
     * Factory PICC master key: DES / 2K3DES representation, key #0, 16 zero bytes
     * (= 32 hexadecimal zero characters), key version 0.
     */
    fun piccMasterKey(): DesfireKey = DesfireKey(
        bytes = ByteArray(16),
        type = DesfireKeyType.DES,
        number = 0,
        version = 0
    )
}
