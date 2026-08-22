package de.shansen.rfproject

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RfProjectReaderTest {
    @Test
    fun readsPlainRfidGearXmlAndCompilesControlFlow() {
        val project = RfProjectReader().read(ByteArrayInputStream(sampleXml.toByteArray()), "sample.xml")

        assertEquals("1.2.3", project.manifestVersion)
        assertEquals(RfProjectContainer.XML, project.container)
        assertEquals(2, project.tasks.size)
        assertEquals("GenericChipTaskViewModel", project.tasks[0].typeName)
        assertEquals("MifareDesfireSetupViewModel", project.tasks[1].typeName)

        val plan = RfExecutionPlanCompiler.compile(project)
        assertEquals("10", plan.steps[0].id)
        assertEquals("ChipIsOfType", plan.steps[0].operation)
        assertEquals("20", plan.steps[1].id)
        assertEquals("WriteData", plan.steps[1].operation)
        assertEquals("10", plan.steps[1].condition?.sourceTaskId)
        assertEquals("AuthFailure", plan.steps[1].condition?.expectedError)
    }

    @Test
    fun readsRfPrjZipContainer() {
        val archive = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("taskdatabase.xml"))
                zip.write(sampleXml.toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()

        val project = RfProjectReader().read(ByteArrayInputStream(archive), "job.rfPrj")
        assertEquals(RfProjectContainer.RFPRJ_ARCHIVE, project.container)
        assertEquals(2, project.tasks.size)
    }

    @Test
    fun normalizesLegacyAuthenticationErrorValue() {
        val legacy = sampleXml.replace("AuthFailure", "AuthenticationError")
        val project = RfProjectReader().read(ByteArrayInputStream(legacy.toByteArray()), "legacy.xml")
        assertEquals("AuthFailure", project.tasks[1].control().executeConditionError)
    }

    @Test
    fun rejectsDtdAndEntityDeclarations() {
        val dangerous = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY y SYSTEM="file:///tmp/x">]><ChipTaskHandlerModel><ManifestVersion>1</ManifestVersion><TaskCollection /></ChipTaskHandlerModel>"""
        assertFailsWith<RfProjectFormatException> {
            RfProjectReader().read(ByteArrayInputStream(dangerous.toByteArray()), "bad.xml")
        }
    }

    @Test
    fun validatorRejectsDuplicateStableTaskIds() {
        val duplicate = sampleXml.replace("<CurrentTaskIndex>20</CurrentTaskIndex>", "<CurrentTaskIndex>10</CurrentTaskIndex>")
        val project = RfProjectReader().read(ByteArrayInputStream(duplicate.toByteArray()), "duplicate.xml")
        val report = RfProjectValidator.validate(project)
        assertTrue(report.hasErrors)
        assertTrue(report.issues.any { it.code == "task.id.duplicate" })
    }

    @Test
    fun unconditionalTaskHasNoRuntimeCondition() {
        val project = RfProjectReader().read(ByteArrayInputStream(sampleXml.toByteArray()), "sample.xml")
        val control = project.tasks[0].control()
        assertFalse(control.isConditional)
    }

    private val sampleXml = """
        <?xml version="1.0" encoding="utf-8"?>
        <ChipTaskHandlerModel xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
          <ManifestVersion>1.2.3</ManifestVersion>
          <TaskCollection>
            <anyType xsi:type="GenericChipTaskViewModel">
              <SelectedUIDOfChip>04AABBCCDD</SelectedUIDOfChip>
              <SelectedExecuteConditionTaskIndex>0</SelectedExecuteConditionTaskIndex>
              <SelectedExecuteConditionErrorLevel>Empty</SelectedExecuteConditionErrorLevel>
              <CurrentTaskIndex>10</CurrentTaskIndex>
              <SelectedTaskType>ChipIsOfType</SelectedTaskType>
              <SelectedTaskDescription>Check card type</SelectedTaskDescription>
            </anyType>
            <anyType xsi:type="MifareDesfireSetupViewModel">
              <SelectedExecuteConditionTaskIndex>10</SelectedExecuteConditionTaskIndex>
              <SelectedExecuteConditionErrorLevel>AuthFailure</SelectedExecuteConditionErrorLevel>
              <CurrentTaskIndex>20</CurrentTaskIndex>
              <SelectedTaskType>WriteData</SelectedTaskType>
              <SelectedTaskDescription>Conditional write</SelectedTaskDescription>
              <FutureFieldFromRfidGear>preserved</FutureFieldFromRfidGear>
            </anyType>
          </TaskCollection>
        </ChipTaskHandlerModel>
    """.trimIndent()
}
