package dz.hirz.shared.devices

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DeviceCatalogTest {
    private val values = mapOf(
        "password" to "123456", "newPassword" to "654321", "host" to "staging.hirz.test", "port" to "5023",
        "apn" to "internet", "seconds" to "60", "phone" to "0661000000",
    )

    @Test
    fun everyCommandRendersWithoutPlaceholders() {
        DeviceCatalog.models.filter { it.level != SupportLevel.REJECTED }.forEach { m ->
            val out = DeviceCatalog.render(m, values + ("port" to m.port.toString()))
            assertTrue(out.isNotEmpty(), m.id)
            out.forEach { assertTrue('{' !in it.text && '}' !in it.text, "${m.id}: ${it.text}") }
        }
    }

    @Test
    fun renderRefusesMissingValue() {
        val gt06 = DeviceCatalog.byId("gt06n")!!
        val e = assertFailsWith<IllegalArgumentException> { DeviceCatalog.render(gt06, values - "apn") }
        assertTrue("apn" in e.message!!)
    }

    @Test
    fun passwordChangeComesFirst() {
        DeviceCatalog.models.filter { m -> m.commands.any { it.purpose == "password" } }.forEach { m ->
            assertEquals("password", DeviceCatalog.render(m, values + ("port" to m.port.toString())).first().purpose, m.id)
        }
    }

    @Test
    fun nothingIsCertifiedBeforeTheTrial() {
        assertTrue(DeviceCatalog.models.none { it.level == SupportLevel.CERTIFIED })
        assertTrue(DeviceCatalog.models.flatMap { it.commands }.none { it.verified })
    }

    @Test
    fun rejectedModelsSayWhy() {
        val rejected = DeviceCatalog.models.filter { it.level == SupportLevel.REJECTED }
        assertTrue(rejected.isNotEmpty())
        rejected.forEach { assertTrue(!it.rejectedReason.isNullOrBlank(), it.id) }
    }
}
