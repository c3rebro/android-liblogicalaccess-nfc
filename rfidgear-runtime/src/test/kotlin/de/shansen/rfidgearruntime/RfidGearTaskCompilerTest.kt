package de.shansen.rfidgearruntime

import de.shansen.rfcard.DesfireAuthenticate
import de.shansen.rfcard.DesfireCreateApplication
import de.shansen.rfcard.DesfireKeyType
import de.shansen.rfproject.RfProjectReader
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class RfidGearTaskCompilerTest {
    @Test
    fun compilesAuthenticateApplication() {
        val task = readSingleTask(
            operation = "AuthenticateApplication",
            body = """
                <AppNumberCurrent>0x112233</AppNumberCurrent>
                <DesfireAppKeyCurrent>000102030405060708090A0B0C0D0E0F</DesfireAppKeyCurrent>
                <SelectedDesfireAppKeyEncryptionTypeCurrent>DF_KEY_AES</SelectedDesfireAppKeyEncryptionTypeCurrent>
                <SelectedDesfireAppKeyNumberCurrent>3</SelectedDesfireAppKeyNumberCurrent>
            """
        )

        val action = RfidGearTaskCompiler.compile(task).action
        val command = assertIs<RfidGearAction.Execute>(action).command
        val auth = assertIs<DesfireAuthenticate>(command)

        assertEquals(0x112233, auth.appId)
        assertEquals(3, auth.key.number)
        assertEquals(DesfireKeyType.AES, auth.key.type)
        assertEquals(16, auth.key.bytes.size)
    }

    @Test
    fun bareApplicationIdIsDecimalLikeRfidGear() {
        val task = readSingleTask(
            operation = "AuthenticateApplication",
            body = """
                <AppNumberCurrent>112233</AppNumberCurrent>
                <DesfireAppKeyCurrent>00000000000000000000000000000000</DesfireAppKeyCurrent>
                <SelectedDesfireAppKeyEncryptionTypeCurrent>DF_KEY_AES</SelectedDesfireAppKeyEncryptionTypeCurrent>
                <SelectedDesfireAppKeyNumberCurrent>0</SelectedDesfireAppKeyNumberCurrent>
            """
        )

        val action = assertIs<RfidGearAction.Execute>(RfidGearTaskCompiler.compile(task).action)
        assertEquals(112233, assertIs<DesfireAuthenticate>(action.command).appId)
    }

    @Test
    fun rejectsApplicationIdAbove24Bits() {
        val task = readSingleTask(
            operation = "AuthenticateApplication",
            body = """
                <AppNumberCurrent>0x1000000</AppNumberCurrent>
                <DesfireAppKeyCurrent>00000000000000000000000000000000</DesfireAppKeyCurrent>
                <SelectedDesfireAppKeyEncryptionTypeCurrent>DF_KEY_AES</SelectedDesfireAppKeyEncryptionTypeCurrent>
                <SelectedDesfireAppKeyNumberCurrent>0</SelectedDesfireAppKeyNumberCurrent>
            """
        )

        assertFailsWith<RfidGearCompileException> { RfidGearTaskCompiler.compile(task) }
    }

    @Test
    fun enforcesRfidGear3k3desKeyLength() {
        val task = readSingleTask(
            operation = "AuthenticateApplication",
            body = """
                <AppNumberCurrent>1</AppNumberCurrent>
                <DesfireAppKeyCurrent>00000000000000000000000000000000</DesfireAppKeyCurrent>
                <SelectedDesfireAppKeyEncryptionTypeCurrent>DF_KEY_3K3DES</SelectedDesfireAppKeyEncryptionTypeCurrent>
                <SelectedDesfireAppKeyNumberCurrent>0</SelectedDesfireAppKeyNumberCurrent>
            """
        )

        assertFailsWith<RfidGearCompileException> { RfidGearTaskCompiler.compile(task) }
    }

    @Test
    fun compilesCreateApplicationKeySettingsLikeDesktop() {
        val task = readSingleTask(
            operation = "CreateApplication",
            body = """
                <AppNumberNew>42</AppNumberNew>
                <DesfireMasterKeyCurrent>00000000000000000000000000000000</DesfireMasterKeyCurrent>
                <SelectedDesfireMasterKeyEncryptionTypeCurrent>DF_KEY_AES</SelectedDesfireMasterKeyEncryptionTypeCurrent>
                <SelectedDesfireAppKeyEncryptionTypeCreateNewApp>DF_KEY_AES</SelectedDesfireAppKeyEncryptionTypeCreateNewApp>
                <SelectedDesfireAppMaxNumberOfKeys>5</SelectedDesfireAppMaxNumberOfKeys>
                <SelectedDesfireAppKeySettingsCreateNewApp>ChangeKeyUsingKeyNo</SelectedDesfireAppKeySettingsCreateNewApp>
                <IsAllowChangeMKChecked>true</IsAllowChangeMKChecked>
                <IsAllowListingWithoutMKChecked>true</IsAllowListingWithoutMKChecked>
                <IsAllowCreateDelWithoutMKChecked>false</IsAllowCreateDelWithoutMKChecked>
                <IsAllowConfigChangableChecked>true</IsAllowConfigChangableChecked>
            """
        )

        val action = assertIs<RfidGearAction.Execute>(RfidGearTaskCompiler.compile(task).action)
        val command = assertIs<DesfireCreateApplication>(action.command)

        assertEquals(42, command.appId)
        assertEquals(5, command.maxKeys)
        assertEquals(0xEB, command.keySettings)
    }

    @Test
    fun writeDataRemainsExplicitlyUnsupportedUntilPayloadTreeIsMapped() {
        val task = readSingleTask(operation = "WriteData", body = "")
        val action = RfidGearTaskCompiler.compile(task).action
        assertIs<RfidGearAction.Unsupported>(action)
    }

    private fun readSingleTask(operation: String, body: String) =
        RfProjectReader().read(
            ByteArrayInputStream(
                """
                    <ChipTaskHandlerModel xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                      <ManifestVersion>1.0.0</ManifestVersion>
                      <TaskCollection>
                        <anyType xsi:type="MifareDesfireSetupViewModel">
                          <SelectedExecuteConditionTaskIndex>0</SelectedExecuteConditionTaskIndex>
                          <SelectedExecuteConditionErrorLevel>Empty</SelectedExecuteConditionErrorLevel>
                          <CurrentTaskIndex>10</CurrentTaskIndex>
                          <SelectedTaskType>$operation</SelectedTaskType>
                          $body
                        </anyType>
                      </TaskCollection>
                    </ChipTaskHandlerModel>
                """.trimIndent().toByteArray()
            ),
            "test.xml"
        ).tasks.single()
}
