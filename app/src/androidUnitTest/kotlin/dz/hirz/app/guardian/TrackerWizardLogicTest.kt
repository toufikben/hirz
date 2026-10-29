package dz.hirz.app.guardian

import dz.hirz.shared.api.TrackerClaimDto
import dz.hirz.shared.devices.DeviceCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackerWizardLogicTest {
    @Test
    fun imeiValidationMatchesServer() {
        assertNull(TrackerWizardLogic.imeiError("359339075123450".let { TrackerWizardLogic.withCheckDigit(it.take(14)) }))
        assertEquals("length", TrackerWizardLogic.imeiError("12345"))
        assertEquals("length", TrackerWizardLogic.imeiError("35933907512345A"))
        val valid = TrackerWizardLogic.withCheckDigit("35933907512345")
        val wrong = valid.dropLast(1) + ((valid.last() - '0' + 1) % 10)
        assertEquals("checksum", TrackerWizardLogic.imeiError(wrong))
        assertNull(TrackerWizardLogic.imeiError(" $valid "), "spaces around a pasted IMEI are fine")
    }

    @Test
    fun wizardWaitsThenFinishes() {
        val gt06 = DeviceCatalog.byId("gt06n")!!
        val pending = TrackerClaimDto("c1", "pending", 0)
        assertEquals(WizardStep.PICK_MODEL, TrackerWizardLogic.step(null, null, commandsSent = false))
        assertEquals(WizardStep.ENTER_IDS, TrackerWizardLogic.step(gt06, null, commandsSent = false))
        assertEquals(WizardStep.SEND_COMMANDS, TrackerWizardLogic.step(gt06, pending, commandsSent = false))
        assertEquals(WizardStep.WAITING, TrackerWizardLogic.step(gt06, pending, commandsSent = true))
        assertEquals(WizardStep.DONE, TrackerWizardLogic.step(gt06, pending.copy(state = "active"), commandsSent = true))
        assertEquals(WizardStep.EXPIRED, TrackerWizardLogic.step(gt06, pending.copy(state = "expired"), commandsSent = true))
    }

    @Test
    fun missingCapabilitiesAreSaidPlainly() {
        val st901 = DeviceCatalog.byId("sinotrack-st901")!!
        val ar = TrackerWizardLogic.capabilitySummary(st901, "ar")
        assertEquals(2, ar.size)
        assertTrue(ar.any { "نزع" in it } && ar.any { "البطارية" in it }, ar.toString())
        assertTrue(TrackerWizardLogic.capabilitySummary(st901, "fr").any { "retrait" in it })
        assertTrue(TrackerWizardLogic.capabilitySummary(st901, "en").any { "removal" in it })
        assertTrue(TrackerWizardLogic.untestedWarning(st901, "en")!!.contains("not tested"))
    }

    @Test
    fun rejectedModelsAreNotSelectable() {
        val selectable = TrackerWizardLogic.selectable(DeviceCatalog.models)
        assertTrue(selectable.none { it.id == "setracker-class" || it.id == "wonlex-kt12" })
        assertTrue(selectable.any { it.id == "gt06n" })
    }
}
