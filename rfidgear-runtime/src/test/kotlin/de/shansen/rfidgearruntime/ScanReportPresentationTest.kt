package de.shansen.rfidgearruntime

import org.junit.Test
import kotlin.test.assertEquals

class ScanReportPresentationTest {
    @Test fun oldHistoryProbeMovesBelowDirectoryAndIsNotDuplicated() {
        val old = "PICC master key probe: DES zeros (factory)\n\nDESFire Quick Check Report\nCard\nUID: 0123\n\nApplication directory: PUBLIC\nDirectory key: label\nApplications: 0"
        val expected = "DESFire Quick Check Report\nCard\nUID: 0123\n\nApplication directory: PUBLIC\nPICC master key probe: DES zeros (factory)\nDirectory key: label\nApplications: 0"
        assertEquals(expected, ScanReportPresentation.withPiccMasterKeyProbe(old, "DES zeros (factory)"))
        assertEquals(expected, ScanReportPresentation.withPiccMasterKeyProbe(expected, "DES zeros (factory)"))
    }
    @Test fun unrelatedReportsStayUnchanged() {
        assertEquals("Card\nTechnology: MIFARE Plus", ScanReportPresentation.withPiccMasterKeyProbe("Card\nTechnology: MIFARE Plus", null))
    }
}
