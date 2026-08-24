package de.shansen.rfidgearruntime

import de.shansen.rfcard.DesfireChangePiccMasterKey
import de.shansen.rfcard.DesfireFactoryDefaults
import de.shansen.rfcard.DesfireKey
import de.shansen.rfcard.DesfireKeyType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DesfirePiccKeySafetyTest {
    @Test
    fun piccMasterKeyChangeIsWriteDisabledEvenIfBackendClaimsSupport() {
        val currentKey = DesfireKey(ByteArray(16) { 0x5A.toByte() }, DesfireKeyType.AES, 0)
        val factoryKey = DesfireFactoryDefaults.piccMasterKey()
        try {
            val evaluation = RfidGearActionSafetyPolicy.evaluate(
                RfidGearAction.Execute(
                    DesfireChangePiccMasterKey(
                        currentPiccMasterKey = currentKey,
                        newPiccMasterKey = factoryKey
                    )
                ),
                backendSupportsAction = { true }
            )

            assertEquals(RfidGearActionSafetyKind.MUTATING, evaluation.safety.kind)
            assertEquals("WRITE_DISABLED", evaluation.previewStatus)
            assertFalse(evaluation.canRunOnCurrentAndroidBackend)
        } finally {
            currentKey.clear()
            factoryKey.clear()
        }
    }
}
