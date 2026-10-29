package dz.hirz.app.state

import dz.hirz.app.AppMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModeStoreTest {
    @Test
    fun persistsMode() = runTest {
        val kv = InMemoryKeyValueStore()
        assertNull(ModeStore(kv).mode.first())
        ModeStore(kv).set(AppMode.COMPANION)
        assertEquals(AppMode.COMPANION, ModeStore(kv).mode.first())
    }

    @Test
    fun unknownStoredValueReadsAsNull() = runTest {
        val kv = InMemoryKeyValueStore()
        kv.put(ModeStore.KEY, "something-old")
        assertNull(ModeStore(kv).mode.first())
    }

    @Test
    fun baseUrlDefaultsAndPersists() = runTest {
        val kv = InMemoryKeyValueStore()
        val settings = DevSettings(kv, defaultBaseUrl = "https://default.test")
        assertEquals("https://default.test", settings.baseUrl.first())
        settings.setBaseUrl("https://tunnel.trycloudflare.com/")
        assertEquals("https://tunnel.trycloudflare.com", settings.baseUrl.first())
    }
}
