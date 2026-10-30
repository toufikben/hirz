package dz.hirz.sim

import java.net.Socket
import java.net.SocketTimeoutException

class Gt06AckTimeout(message: String) : Exception(message)

/** Talks GT06 over TCP. A real server ignores fixes until it has acknowledged the login, so `send` requires `login`. */
class Gt06Client(host: String, port: Int, private val ackTimeoutMs: Long = 5_000) : AutoCloseable {
    private val socket = Socket(host, port).apply { soTimeout = ackTimeoutMs.toInt() }
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()
    private var serial = 1
    private var loggedIn = false

    fun login(imei: String) {
        output.write(Gt06Encoder.login(imei, nextSerial()))
        output.flush()
        val deadline = System.currentTimeMillis() + ackTimeoutMs
        try {
            while (System.currentTimeMillis() < deadline) {
                val start = input.readNBytes(2)
                if (start.size < 2) break
                val length = input.read()
                if (length < 0) break
                val rest = input.readNBytes(length + 2)
                if ((rest.firstOrNull()?.toInt() ?: -1) == 0x01) {
                    loggedIn = true
                    return
                }
            }
        } catch (_: SocketTimeoutException) {
            // fall through to the timeout below
        }
        throw Gt06AckTimeout("no login ACK within ${ackTimeoutMs}ms")
    }

    fun send(fix: Gt06Fix) {
        check(loggedIn) { "login() must succeed before sending a fix" }
        output.write(Gt06Encoder.location(fix, nextSerial()))
        output.flush()
    }

    fun alarm(fix: Gt06Fix, alarm: Gt06Alarm) {
        check(loggedIn) { "login() must succeed before sending an alarm" }
        output.write(Gt06Encoder.alarm(fix, alarm, nextSerial()))
        output.flush()
    }

    fun heartbeat() {
        check(loggedIn) { "login() must succeed before sending a heartbeat" }
        output.write(Gt06Encoder.heartbeat(nextSerial(), batteryLevel0to6 = 6, gsmSignal0to4 = 4))
        output.flush()
    }

    private fun nextSerial(): Int = serial++.and(0xFFFF)

    override fun close() = socket.close()
}
