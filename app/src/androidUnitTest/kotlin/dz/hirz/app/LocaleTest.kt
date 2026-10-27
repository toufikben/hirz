package dz.hirz.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Android unit tests run with the module directory (`app/`) as the working directory. */
class LocaleTest {
    private val composeValues = File("src/commonMain/composeResources")
    private val androidRes = File("src/androidMain/res")
    private val manifest = File("src/androidMain/AndroidManifest.xml").readText()

    private fun strings(file: File): Map<String, String> =
        Regex("""<string name="([^"]+)">([^<]*)</string>""").findAll(file.readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    @Test
    fun defaultLocaleIsArabicRtl() {
        assertEquals("حِرز", strings(File(composeValues, "values/strings.xml"))["app_name"])
        assertEquals("حِرز", strings(File(androidRes, "values/strings.xml"))["app_name"])
        val application = Regex("""<application[^>]*>""").find(manifest)?.value.orEmpty()
        assertTrue(application.contains("""android:supportsRtl="true""""), application)
    }

    @Test
    fun everyKeyExistsInAllLocales() {
        for (root in listOf(composeValues, androidRes)) {
            val default = strings(File(root, "values/strings.xml")).keys
            assertTrue(default.isNotEmpty(), "no strings in $root/values")
            for (locale in listOf("values-fr", "values-en")) {
                assertEquals(default, strings(File(root, "$locale/strings.xml")).keys, "$root/$locale")
            }
        }
    }

    @Test
    fun manifestDeclaresMonitoringTool() {
        assertTrue(
            Regex("""<meta-data\s+android:name="isMonitoringTool"\s+android:value="child_monitoring"\s*/>""")
                .containsMatchIn(manifest),
        )
    }
}
