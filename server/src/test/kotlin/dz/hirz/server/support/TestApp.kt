package dz.hirz.server.support

import dz.hirz.server.Deps
import dz.hirz.server.auth.TokenVerifier
import dz.hirz.server.auth.VerifiedIdentity
import dz.hirz.server.ingest.IngestListener
import dz.hirz.server.module
import dz.hirz.server.notify.AlertMessage
import dz.hirz.server.notify.Notifier
import dz.hirz.server.notify.PushTarget
import dz.hirz.server.notify.SendResult
import dz.hirz.shared.rules.RuleConfig
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import com.zaxxer.hikari.HikariDataSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Accepts tokens of the form `test.<uid>` (bearer token68 has no colon); anything else is rejected. */
class FakeTokenVerifier : TokenVerifier {
    override suspend fun verify(idToken: String): VerifiedIdentity? =
        idToken.removePrefix("test.").takeIf { idToken.startsWith("test.") && it.isNotBlank() }
            ?.let { VerifiedIdentity(uid = it, email = "$it@hirz.test", name = it) }
}

/** A clock tests can move. */
class MutableClock(var now: Instant = Instant.parse("2026-11-01T08:00:00Z")) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = now
    fun advance(d: Duration) { now = now.plusMillis(d.inWholeMilliseconds) }
}

/** Records every send; [delay] simulates provider latency, [failTokens] simulate invalid tokens. */
class FakeNotifier(
    private val delay: Duration = 0.milliseconds,
    private val failTokens: Set<String> = emptySet(),
) : Notifier {
    val sent = CopyOnWriteArrayList<Pair<PushTarget, AlertMessage>>()
    override suspend fun send(to: PushTarget, message: AlertMessage): SendResult {
        if (delay > Duration.ZERO) delay(delay)
        if (to.token in failTokens) return SendResult.Failed("UNREGISTERED", tokenInvalid = true)
        sent += to to message
        return SendResult.Ok("fake-${sent.size}")
    }

    /** Only incident alerts, without device commands such as `high_rate`. */
    val alerts get() = sent.filter { it.second.kind == "incident" }
}

const val TRACCAR_SECRET = "test-traccar-secret-0123456789abcdef"

class TestEnv(
    val db: HikariDataSource = TestDb.fresh(),
    val clock: MutableClock = MutableClock(),
    val notifier: FakeNotifier = FakeNotifier(),
    val rules: RuleConfig = RuleConfig.DEFAULT,
    val ingestListeners: List<IngestListener> = emptyList(),
    val notifierOverride: Notifier? = null,
    val traccar: FakeTraccarAdmin = FakeTraccarAdmin(),
    /** "" models a server started without HIRZ_TRACCAR_SECRET. */
    val traccarSecretOverride: String? = null,
) {
    val deps = Deps(
        db = db, verifier = FakeTokenVerifier(), notifier = notifierOverride ?: notifier, clock = clock, rules = rules,
        dataKey = ByteArray(32) { it.toByte() }, startJobs = false, extraIngestListeners = ingestListeners,
        traccarSecret = traccarSecretOverride ?: TRACCAR_SECRET, traccar = traccar,
    )
}

val testJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Runs a Ktor test application wired to [env]; [block] gets a JSON-aware client. */
fun withApp(env: TestEnv = TestEnv(), block: suspend ApplicationTestBuilder.(HttpClient, TestEnv) -> Unit) {
    try {
        testApplication {
            application { module(env.deps) }
            val client = createClient {
                install(ContentNegotiation) { json(testJson) }
                install(WebSockets)
            }
            block(client, env)
        }
    } finally {
        env.db.close()
    }
}
