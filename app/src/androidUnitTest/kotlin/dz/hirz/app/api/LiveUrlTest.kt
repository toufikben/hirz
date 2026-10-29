package dz.hirz.app.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LiveUrlTest {
    /** #21: the token is URL-encoded, and no socket is opened without one (never "?token=null"). */
    @Test
    fun wsTokenIsUrlEncodedAndNeverNull() {
        assertEquals("wss://h.example/v1/live?token=a%2Bb%2Fc%3D", HirzClient.liveUrl("https://h.example", "a+b/c="))
        assertEquals("ws://10.0.2.2:8080/v1/live?token=abc", HirzClient.liveUrl("http://10.0.2.2:8080", "abc"))
        assertNull(HirzClient.liveUrl("https://h.example", null))
        assertNull(HirzClient.liveUrl("https://h.example", ""))
    }
}
