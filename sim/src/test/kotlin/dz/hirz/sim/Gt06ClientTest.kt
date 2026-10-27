package dz.hirz.sim

import java.net.ServerSocket
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Minimal fake GT06 server: records each packet's protocol byte; acks login after [ackDelayMs] unless null. */
private class FakeGt06Server(private val ackDelayMs: Long?) : AutoCloseable {
    private val server = ServerSocket(0)
    val protocols = CopyOnWriteArrayList<Int>()
    private val worker = thread(isDaemon = true) {
        runCatching {
            server.accept().use { socket ->
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                while (true) {
                    val start = input.readNBytes(2)
                    if (start.size < 2) break
                    val length = input.read()
                    val rest = input.readNBytes(length + 2) // protocol .. crc, then 0D 0A
                    val protocol = rest[0].toInt() and 0xFF
                    protocols += protocol
                    if (protocol == 0x01 && ackDelayMs != null) {
                        Thread.sleep(ackDelayMs)
                        val body = byteArrayOf(0x78, 0x78, 0x05, 0x01, rest[rest.size - 6], rest[rest.size - 5])
                        val crc = Crc16X25.compute(body, 2, body.size)
                        output.write(body + byteArrayOf((crc shr 8).toByte(), crc.toByte(), 0x0D, 0x0A))
                        output.flush()
                    }
                }
            }
        }
    }
    val port get() = server.localPort
    override fun close() {
        server.close()
        worker.join(2_000)
    }
}

class Gt06ClientTest {
    private val fix = Gt06Fix(36.7538, 3.0588, 0, 0, Instant.parse("2026-10-27T13:00:00Z"), 8)

    @Test
    fun waitsForLoginAckBeforeLocation() {
        FakeGt06Server(ackDelayMs = 300).use { fake ->
            Gt06Client("127.0.0.1", fake.port).use { client ->
                assertFailsWith<IllegalStateException> { client.send(fix) }
                client.login("863000000000001")
                client.send(fix)
                Thread.sleep(300)
                assertEquals(listOf(0x01, 0x12), fake.protocols.toList())
            }
        }
    }

    @Test
    fun failsWhenAckNeverArrives() {
        FakeGt06Server(ackDelayMs = null).use { fake ->
            Gt06Client("127.0.0.1", fake.port, ackTimeoutMs = 1_000).use { client ->
                val started = System.nanoTime()
                assertFailsWith<Gt06AckTimeout> { client.login("863000000000001") }
                assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
            }
        }
    }
}
