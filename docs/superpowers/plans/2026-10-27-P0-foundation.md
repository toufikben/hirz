# Hirz P0 — Foundation Implementation Plan

> **ملخص بالعربية:** المرحلة P0 تنشئ المستودع الخاص `hirz` وتنقل إليه البحث والمعرفة السابقة. ثم تبني هيكل الكود (تطبيق KMP، وخادم Ktor، ونماذج مشتركة)، ومحاكي أجهزة GT06/OsmAnd، وإعداد Traccar محروسًا، وCI على GitHub. **بوابة الخروج:** كل شيء أخضر في CI، ومحاكي GT06 يرسل موقعًا يظهر في Traccar حقيقي داخل CI. كل البناء الثقيل يجري في CI لأن الحاسوب المحلي ضعيف.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the private `hirz` monorepo with migrated knowledge, a compiling KMP app shell, a Ktor server shell, the shared signal contract, a GT06/OsmAnd device simulator, a guarded Traccar config, and CI that proves a simulated GT06 fix lands in a real Traccar.

**Architecture:** One Gradle monorepo:
- `shared` is the KMP domain module (signal contract, asset types).
- `server` is Ktor/JVM.
- `app` is Compose Multiplatform, Android target only for now.
- `sim` is a JVM CLI and library that speaks device protocols.
- `infra/traccar` holds the Traccar config and its Node checker, reused from v2.
- CI runs unit tests, builds the debug APK, checks the Traccar config, and runs an integration job with a `traccar/traccar:6.16.0` service container.

**Tech Stack:** Kotlin (Multiplatform + JVM), Gradle Kotlin DSL with a version catalog, Ktor server, Compose Multiplatform, kotlinx.serialization, JUnit5/kotlin.test, Node 22 (config checker only), GitHub Actions, Traccar 6.16.0 (Docker).

**Spec:** `docs/05-hirz-design.md` (approved 2026-10-27). Supporting: `docs/04-decisions.md`, `docs/03-local-repo-assessment.md`.

## Global Constraints

- Repo: GitHub `toufikben/hirz`, **private**. Local path: `C:\Users\Administrator\Documents\hirz`.
- The old repo `toufikben/KidGuard-core-dz-ultimate-` and the local `kidguard-core` are **read-only sources**. Never modify, push to, or delete anything there.
- Traccar version ≥ 6.12 (CVE-2025-68930). Pin exactly `traccar/traccar:6.16.0`.
- Traccar config must contain:
  - `geocoder.enable=false`
  - `database.registerUnknown=false`
  - `protocols.enable=gt06,osmand`
  - `web.address=127.0.0.1` for the local and production template. The CI integration job may override only `web.address`.
- JDK 21 (Temurin) in CI. Android `minSdk 26`, `compileSdk`/`targetSdk 36`. Provisional `applicationId` `dz.hirz.app`. It is not final until the first store upload (spec §14.3).
- UI languages: `ar` (default, RTL), `fr`, `en`. No hard-coded user-visible strings in code.
- Forbidden claims must never appear in app strings. The full list is in Task 7.
- Heavy builds (Gradle, APK) run in CI. The owner's PC (2 cores, 4 GB, HDD) is used only for git and light Node work.
- Library versions: the latest stable at execution time, pinned in `gradle/libs.versions.toml`. No `+` or dynamic versions.
- Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Out of P0 (spec §2 `sim` lists Teltonika): the Teltonika encoder arrives in P3 with the first real devices.

## Review Focus

1. **A GT06 coordinate in the south or west hemisphere** must encode correctly. The hemisphere bits are easy to invert. Pinned by `encodesSouthWestHemisphereBits` in Task 4.
2. **The simulator talks to Traccar before it accepts the login.** Traccar ignores location packets from an unauthenticated connection, so the sim must wait for the login ACK. Pinned by `waitsForLoginAckBeforeLocation` in Task 4.
3. **A duplicated or double-quoted config key** must not silently re-enable the geocoder. This was the v2 bug. Pinned by the copied v2 tests in Task 5.
4. **Arabic layout** must render RTL by default, with French and English falling back cleanly when a key is missing. Pinned by `defaultLocaleIsArabicRtl` and `everyKeyExistsInAllLocales` in Task 6.
5. **The integration job must fail loudly when Traccar never records the position.** It must not pass on timeout. Pinned by `failsWhenPositionNeverArrives` in Task 4 and the CI assertion in Task 8.

---

## File Structure

```
hirz/
  settings.gradle.kts, build.gradle.kts, gradle.properties, gradle/libs.versions.toml, gradlew*
  shared/src/commonMain/kotlin/dz/hirz/shared/signal/{Signal.kt, FixSource.kt, AlarmKind.kt}
  shared/src/commonMain/kotlin/dz/hirz/shared/asset/AssetType.kt
  shared/src/commonTest/kotlin/dz/hirz/shared/signal/SignalTest.kt
  server/src/main/kotlin/dz/hirz/server/{Application.kt, HealthRoutes.kt}
  server/src/test/kotlin/dz/hirz/server/HealthRoutesTest.kt
  sim/src/main/kotlin/dz/hirz/sim/{Crc16X25.kt, Gt06Encoder.kt, Gt06Client.kt, OsmAndClient.kt, Main.kt}
  sim/src/test/kotlin/dz/hirz/sim/{Crc16X25Test.kt, Gt06EncoderTest.kt, Gt06ClientTest.kt, OsmAndClientTest.kt}
  sim/src/integrationTest/kotlin/dz/hirz/sim/TraccarRoundTripIT.kt
  app/  (Compose Multiplatform, androidMain + commonMain, composeResources/values{,-fr,-en}/strings.xml)
  infra/traccar/{hirz.template.xml, README.md}
  infra/traccar/tools/{check-config.mjs, check-config.test.mjs}
  tools/forbidden-claims/{check.mjs, check.test.mjs, phrases.json}
  docs/  (01–05 design docs, superpowers/plans/, research/, legacy/)
  .github/workflows/ci.yml
  README.md, .gitignore, .editorconfig
```

---

### Task 1: Create the repo and migrate knowledge

**Files:**
- Create: `C:\Users\Administrator\Documents\hirz\` (git repo), `README.md`, `.gitignore`, `.editorconfig`
- Copy into `docs/`:
  - `01-research-analysis-and-vision.md` … `05-hirz-design.md` and this plan. They come from this session's folder, under `docs/`.
  - `docs/research/`: all of `…\6bdda3ce\research\*` plus `…\6bdda3ce\DEEP_RESEARCH_CHILD-ABDUCTION-VARIABLES.md`.
  - `docs/legacy/v2/`: from `kidguard-core`, `docs/superpowers/specs/*.md` and `plans/2026-10-05-roadmap.md`.
  - `docs/legacy/v2/relay/`: `kidguard-core/relay/*.ts` and `tests/relay/*.ts`, read from branch `m2/relay` (commit `42d48df`) with `git show`, not from the working tree.

**Interfaces:** Produces the repo root that every later task writes into.

- [ ] **Step 1:** Create the private GitHub repo.

  Run: `gh repo create toufikben/hirz --private --description "Hirz — unified protection and tracking (children, elders, vehicles, livestock)"`

  Expected: the URL is printed. Then `gh repo view toufikben/hirz --json visibility` returns `"PRIVATE"`.

- [ ] **Step 2:** `git init -b main` at the local path. Write `README.md`: an Arabic and English one-paragraph intent from spec §0, plus links to `docs/05-hirz-design.md` and this plan.
  - `.gitignore`: Gradle/Android/IDE/Node outputs, `local.properties`, `*.keystore`, `infra/traccar/data/`.
  - `.editorconfig`: UTF-8, LF, 4-space Kotlin, 2-space JSON/YAML/MJS.
- [ ] **Step 3:** Copy the files listed above. Add `docs/legacy/README.md` with one line per folder: what it is, its source commit, and "reference only — not built".
- [ ] **Step 4:** Verify.

  Run: `git -C "C:\Users\Administrator\Documents\Qoder\2026-10-05\6bdda3ce\kidguard-core" status --short`

  Expected: empty output (source untouched). Then `ls docs/research | wc -l` must be ≥ 16.

- [ ] **Step 5:** Commit and push.

  Run: `git add -A && git commit -m "docs: seed Hirz with design, research and v2 legacy references" && git remote add origin https://github.com/toufikben/hirz.git && git push -u origin main`

---

### Task 2: Gradle monorepo and the shared signal contract

**Files:**
- Create: `settings.gradle.kts` (includes `:shared`, `:server`, `:sim`, `:app`), root `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, Gradle wrapper
- Create: `shared/build.gradle.kts` (KMP: `jvm()`, `androidTarget()`), and the three files under `shared/.../signal/` plus `asset/AssetType.kt`
- Test: `shared/src/commonTest/kotlin/dz/hirz/shared/signal/SignalTest.kt`

**Interfaces — Produces** (spec §3, §1):
```kotlin
enum class FixSource { GNSS, WIFI, CELL, BLE_RELAY, SMS }
enum class AlarmKind { SOS, TAMPER, REMOVAL, POWER_CUT, JAMMING, FALL, DURESS }
enum class AssetType { CHILD, TEEN, ELDER, SPECIAL_NEEDS, CAR, MOTORCYCLE, BICYCLE, BUS, TRUCK, MACHINERY, LIVESTOCK, PET, BAG, GENERATOR, LONE_WORKER }

@Serializable
data class Position(val lat: Double, val lon: Double, val accuracyM: Double?, val accuracyEstimated: Boolean)

@Serializable
data class Signal(
    val sourceId: String, val assetId: String?,
    val fixTimeMs: Long?, val serverTimeMs: Long,
    val position: Position?, val fixSource: FixSource?,
    val speedKmh: Double?, val heading: Double?,
    val heartbeat: Boolean,
    val battery: Int?, val charging: Boolean?, val externalPower: Boolean?,
    val ignition: Boolean?, val motion: Boolean?, val tilt: Boolean?,
    val alarms: Set<AlarmKind>,
) {
    fun ageMs(nowMs: Long): Long?   // null when fixTimeMs is null
}
```

- [ ] **Step 1: Write the failing tests** in `SignalTest.kt`:
  - `ageIsMeasuredFromFixTime`: `fixTimeMs=1_000`, `ageMs(56_000)` returns `55_000`.
  - `ageIsNullWithoutFix`: a heartbeat-only signal (`position=null`, `fixTimeMs=null`, `heartbeat=true`) has `ageMs(...) == null`.
  - `rejectsLatitudeOutOfRange`: `Position(91.0, 0.0, null, false)` throws `IllegalArgumentException`.
  - `rejectsLongitudeOutOfRange`: same for `lon = 181.0`.
  - `roundTripsThroughJson`: `Json.decodeFromString<Signal>(Json.encodeToString(s)) == s` for a full signal with `alarms = setOf(AlarmKind.SOS)`.
- [ ] **Step 2: Verify the tests fail.** Push a branch and confirm CI fails on these tests. If Gradle runs locally, `./gradlew :shared:jvmTest` should fail with unresolved references.
- [ ] **Step 3: Implement** the types above. `Position` validates in `init`: `lat ∈ [-90,90]`, `lon ∈ [-180,180]`, and `accuracyM ≥ 0` when it is not null.
- [ ] **Step 4: Verify the tests pass:** `./gradlew :shared:allTests` is green in CI.
- [ ] **Step 5: Commit:** `feat(shared): add the normalized signal contract and asset types`

---

### Task 3: Ktor server shell

**Files:**
- Create: `server/build.gradle.kts` (depends on `:shared`), `server/src/main/kotlin/dz/hirz/server/Application.kt`, `HealthRoutes.kt`
- Test: `server/src/test/kotlin/dz/hirz/server/HealthRoutesTest.kt`

**Interfaces — Produces:**
- `fun Application.module()`. It installs ContentNegotiation (kotlinx.serialization JSON) and routes.
- `fun Route.healthRoutes(version: String)`
- `GET /health` returns `200 {"status":"ok","service":"hirz-core","version":"<version>"}`.
- `version` comes from the Gradle project version, injected as the system property `hirz.version` (default `"dev"`).
- `main` reads the port from env `PORT` (default `8080`).

- [ ] **Step 1: Write the failing tests** with `testApplication`:
  - `healthReturnsOk`: status `200`, JSON field `status == "ok"`, `service == "hirz-core"`.
  - `unknownRouteIs404`: `GET /nope` returns `404`.
- [ ] **Step 2: Verify they fail** (unresolved `module`).
- [ ] **Step 3: Implement** `module()`, `healthRoutes`, and `main` (Netty engine).
- [ ] **Step 4: Verify** `./gradlew :server:test` is green.
- [ ] **Step 5: Commit:** `feat(server): add the Ktor shell with a health endpoint`

---

### Task 4: Device simulator (GT06 + OsmAnd) and the Traccar round-trip test

**Files:**
- Create: `sim/build.gradle.kts`:
  - JVM application, main class `dz.hirz.sim.MainKt`, depends on `:shared`.
  - Adds an `integrationTest` source set and task. The task reads env `TRACCAR_URL`, `TRACCAR_GT06_HOST`, `TRACCAR_GT06_PORT`, and is **skipped with a printed reason** when `TRACCAR_URL` is unset.
- Create: `Crc16X25.kt`, `Gt06Encoder.kt`, `Gt06Client.kt`, `OsmAndClient.kt`, `Main.kt`
- Test: the four unit tests, plus `sim/src/integrationTest/kotlin/dz/hirz/sim/TraccarRoundTripIT.kt`

**Interfaces — Produces:**
```kotlin
object Crc16X25 { fun compute(bytes: ByteArray, from: Int = 0, toExclusive: Int = bytes.size): Int }

object Gt06Encoder {
    fun login(imei: String, serial: Int): ByteArray                       // protocol 0x01
    fun heartbeat(serial: Int, batteryLevel0to6: Int, gsmSignal0to4: Int): ByteArray  // protocol 0x13
    fun location(fix: Gt06Fix, serial: Int): ByteArray                    // protocol 0x12
    fun encodeCoordinate(degrees: Double): Long                           // abs(degrees) * 1_800_000, rounded
}
data class Gt06Fix(val lat: Double, val lon: Double, val speedKmh: Int, val courseDeg: Int,
                   val utc: java.time.Instant, val satellites: Int, val positioned: Boolean = true)

class Gt06Client(host: String, port: Int, ackTimeoutMs: Long = 5_000) : AutoCloseable {
    fun login(imei: String)             // sends login, blocks until the ACK (protocol 0x01 echo); throws Gt06AckTimeout
    fun send(fix: Gt06Fix)              // throws IllegalStateException if login() has not succeeded
    fun heartbeat()
}
class Gt06AckTimeout(message: String) : Exception(message)

class OsmAndClient(baseUrl: String) {   // e.g. http://localhost:5055
    fun send(id: String, fix: Gt06Fix, batteryPercent: Int?): Int   // returns the HTTP status
}
```

Rules for the encoder:
- The packet frame is `78 78 <len> <proto> <payload> <serial:2> <crc:2> 0D 0A`. CRC-16/X-25 runs over `len … serial`.
- Location payload: date-time as 6 bytes (YY MM DD hh mm ss, UTC), GPS info byte (`0xC0 | satellites.coerceIn(0,15)`), lat (4), lon (4), speed (1), course/status (2), then LBS `MCC=603` (Algeria), `MNC=1`, `LAC=0`, `CellID=0`.
- Course/status word:
  - bits 0–9 = course;
  - bit 10 = 1 for **north**;
  - bit 11 = 1 for **west**;
  - bit 12 = GPS positioned.

`Main.kt` CLI:
- `sim gt06 --host H --port P --imei I --lat .. --lon .. [--count N --interval-s S --dlat D]`
- `sim osmand --url U --id I --lat .. --lon ..`
- It prints each sent fix as one line.

- [ ] **Step 1: Write the failing unit tests:**
  - `Crc16X25Test.checkValue`: `compute("123456789".toByteArray()) == 0x906E`.
  - `Gt06EncoderTest.loginMatchesProtocolSpecExample`: `login("0123456789012345", 1)` equals hex `78 78 0D 01 01 23 45 67 89 01 23 45 00 01 8C DD 0D 0A`.
  - `encodesAlgiersCoordinates`: `encodeCoordinate(36.7538) == 66_156_840L` and `encodeCoordinate(3.0588) == 5_505_840L`.
  - `locationPacketHasValidFrameAndCrc`: length byte `0x1F` (31), protocol `0x12`, the trailing two bytes are `0D 0A`, and the CRC recomputed over `[2, size-4)` equals the embedded CRC.
  - `encodesSouthWestHemisphereBits`: a fix at `(-33.9, -18.4)` has bit 10 = 0 and bit 11 = 1. A fix at `(36.75, 3.05)` has bit 10 = 1 and bit 11 = 0.
  - `Gt06ClientTest.waitsForLoginAckBeforeLocation`:
    - Set up a local `ServerSocket` fake that records received packets and sends the login ACK after 300 ms.
    - Assert `send(fix)` before `login` throws `IllegalStateException`.
    - Assert that after `login` the fake received the login first and the location second.
  - `failsWhenAckNeverArrives`: a fake that never replies makes `login` throw `Gt06AckTimeout` within `ackTimeoutMs + 1s`.
  - `OsmAndClientTest.sendsQueryParameters`: an embedded HTTP fake receives `id`, `lat`, `lon`, `timestamp` (epoch seconds), `speed` (knots, as OsmAnd expects), and `batt`.
- [ ] **Step 2: Verify they fail** in CI (unresolved symbols).
- [ ] **Step 3: Implement** the encoder, the clients, and the CLI. `Gt06Client` uses a blocking `Socket` with `soTimeout = ackTimeoutMs`. The serial increments per packet.
- [ ] **Step 4: Verify** `./gradlew :sim:test` is green.
- [ ] **Step 5: Write `TraccarRoundTripIT`:**
  - `gt06FixAppearsInTraccar`:
    1. `POST {TRACCAR_URL}/api/users` creates the first (admin) user `ci@hirz.test` with a random password.
    2. Create a session.
    3. `POST /api/devices {name:"sim-gt06", uniqueId:"863000000000001"}`.
    4. `Gt06Client.login` with that IMEI, then `send(Gt06Fix(36.7538, 3.0588, 0, 0, now, 8))`.
    5. Poll `GET /api/positions?deviceId=<id>` every 1 s for up to 30 s.
    6. Assert one position with `|lat-36.7538| < 1e-4` and `|lon-3.0588| < 1e-4`.
  - `failsWhenPositionNeverArrives`: send nothing for a second device, and assert that the polling helper throws `AssertionError("position not recorded within 30s")`. This proves the helper cannot pass on timeout.
- [ ] **Step 6: Commit:** `feat(sim): add the GT06/OsmAnd simulator and the Traccar round-trip test`

---

### Task 5: Guarded Traccar configuration

**Files:**
- Create: `infra/traccar/hirz.template.xml`. Base it on v2 `lab/traccar/kidguard-lab.template.xml`, but drop the telegram, mail, and forward entries. Those return in P1/P2.
- Copy and adapt: v2 `lab/scripts/check-config.mjs` becomes `infra/traccar/tools/check-config.mjs`, and v2 `lab/tests/config.test.mjs` becomes `infra/traccar/tools/check-config.test.mjs`. Change the default template path to `infra/traccar/hirz.template.xml`.
- Create: `infra/traccar/README.md`: how to run Traccar 6.16.0 locally (JDK 25 measured working in v2, or Docker) with this template, and the port table (`8082` web, `5023` gt06, `5055` osmand).

**Interfaces — Produces:** `node infra/traccar/tools/check-config.mjs --template <path>` exits `0` and prints `config ok`, or exits `1` with the rule message.

The template must contain exactly these keys:
- `database.registerUnknown=false`
- `web.address=127.0.0.1`
- `web.port=8082`
- `protocols.enable=gt06,osmand`
- `geocoder.enable=false`
- `logger.console=true`
- H2 database settings with a non-empty password placeholder `${HIRZ_TRACCAR_DB_PASSWORD}`

- [ ] **Step 1: Bring over the v2 tests unchanged in intent.** They cover the `geocoder` rules, the double-quote parsing, and the duplicate-key rejection. Add:
  - `rejectsUnknownProtocols`: `protocols.enable=gt06,osmand,h02` fails with a message naming `h02`.
  - `rejectsPublicWebAddress`: `web.address=0.0.0.0` fails.
  - `rejectsRegisterUnknown`: `database.registerUnknown=true` fails.
- [ ] **Step 2: Verify the new tests fail.** Run: `node --test infra/traccar/tools/` and expect three failures.
- [ ] **Step 3: Implement** the three rules in `check-config.mjs`. Messages must name the rule, not only the key (v2 lesson).
- [ ] **Step 4: Verify.** Run: `node --test infra/traccar/tools/`, all pass. Then `node infra/traccar/tools/check-config.mjs --template infra/traccar/hirz.template.xml` prints `config ok`.
- [ ] **Step 5: Commit:** `feat(infra): add the guarded Traccar template and its checker`

---

### Task 6: App shell (Compose Multiplatform, Android)

**Files:**
- Create: `app/build.gradle.kts`:
  - KMP with `androidTarget()`, Compose Multiplatform, and the Android application plugin.
  - `namespace` and `applicationId` = `dz.hirz.app`; `minSdk 26`; `compileSdk`/`targetSdk 36`.
  - Depends on `:shared`.
- Create: `app/src/commonMain/kotlin/dz/hirz/app/App.kt`:
  - `@Composable fun App(modeOverride: AppMode? = null)` shows a placeholder screen with the app name and the mode picker labels.
  - `enum class AppMode { GUARDIAN, COMPANION, SMS_GATEWAY }`.
- Create: `app/src/androidMain/.../MainActivity.kt`, `AndroidManifest.xml`:
  - No dangerous permissions in P0.
  - `<meta-data android:name="isMonitoringTool" android:value="child_monitoring"/>` (spec §8.6).
- Create: `app/src/commonMain/composeResources/values/strings.xml` (Arabic default), `values-fr/strings.xml`, `values-en/strings.xml`. Keys: `app_name` ("حِرز" / "Hirz" / "Hirz"), `mode_guardian`, `mode_companion`, `mode_sms_gateway`, `tagline`.
- Test: `app/src/androidUnitTest/kotlin/dz/hirz/app/LocaleTest.kt`

**Interfaces — Produces:** `App()`, `AppMode`. P1 builds the screens on top of these.

- [ ] **Step 1: Write the failing tests:**
  - `defaultLocaleIsArabicRtl`: the default `values/strings.xml` `app_name` equals `"حِرز"`, and `app/src/androidMain/AndroidManifest.xml` has `android:supportsRtl="true"` on `<application>`. Read both as files in the test.
  - `everyKeyExistsInAllLocales`: parse the three `strings.xml` files and assert the key sets are equal.
  - `manifestDeclaresMonitoringTool`: the manifest contains `isMonitoringTool` with value `child_monitoring`.
- [ ] **Step 2: Verify they fail** in CI.
- [ ] **Step 3: Implement** the files above. The tagline must stay honest. Arabic: `"يساعدك على الاكتشاف المبكر والإبلاغ السريع"`; French and English carry the same meaning.
- [ ] **Step 4: Verify** in CI: `./gradlew :app:testDebugUnitTest :app:assembleDebug` is green, and the APK is uploaded as a CI artifact.
- [ ] **Step 5: Commit:** `feat(app): add the Compose Multiplatform shell with ar/fr/en and three modes`

---

### Task 7: Forbidden-claims guard

**Files:**
- Create: `tools/forbidden-claims/phrases.json`, `check.mjs`, `check.test.mjs`

**Interfaces — Produces:** `node tools/forbidden-claims/check.mjs <glob...>` exits `1` and lists `file:line: phrase` on any hit, and exits `0` otherwise. CI runs it over `app/src/**/strings.xml` and, later, store listing files.

Phrases (spec §8.7, v2 amendment §د). Match case-insensitively after Arabic diacritics are normalized:
- `يمنع الاختطاف`
- `كاشف اختطاف`
- `غير قابل للنزع`
- `مخفي`
- `موقع حي مضمون`
- `يبلغ الشرطة تلقائيا`
- `prevents kidnapping`
- `kidnapping detector`
- `guaranteed live location`
- `undetectable`
- `tamper-proof`
- `empêche l'enlèvement`
- `localisation garantie`
- `indétectable`

- [ ] **Step 1: Write the failing tests:**
  - `flagsArabicWithDiacritics`: the text `"يَمنعُ الاختطافَ"` is flagged.
  - `flagsFrench`: `"Empêche l'enlèvement"` is flagged.
  - `passesHonestTagline`: the Task 6 Arabic tagline passes.
  - `reportsFileAndLine`: the output format is `path:line: phrase`.
- [ ] **Step 2: Verify they fail:** `node --test tools/forbidden-claims/`.
- [ ] **Step 3: Implement.** Normalize by removing `\u064B-\u065F\u0670` and folding `أإآ→ا` and `ى→ي`.
- [ ] **Step 4: Verify** the tests pass. Then run it over the app strings: exit `0`.
- [ ] **Step 5: Commit:** `feat(tools): add the forbidden marketing-claims guard`

---

### Task 8: CI pipeline and the P0 exit gate

**Files:**
- Create: `.github/workflows/ci.yml`

Jobs (on `push` and `pull_request`):
1. **`gradle`**:
   - Temurin 21 and the Gradle cache.
   - Runs `./gradlew :shared:allTests :server:test :sim:test :app:testDebugUnitTest :app:assembleDebug`.
   - Uploads `app/build/outputs/apk/debug/*.apk` as artifact `hirz-debug-apk`.
2. **`node-guards`**:
   - Node 22.
   - Runs `node --test infra/traccar/tools/ tools/forbidden-claims/`.
   - Runs `node infra/traccar/tools/check-config.mjs --template infra/traccar/hirz.template.xml`.
   - Runs `node tools/forbidden-claims/check.mjs "app/src/**/strings.xml"`.
3. **`traccar-integration`** (needs `gradle`):
   - A step first renders the config from `hirz.template.xml` with `web.address=0.0.0.0` (CI-only override) and a random DB password, written to `$RUNNER_TEMP/traccar.xml`.
   - Then: `docker run -d --name traccar -p 8082:8082 -p 5023:5023 -p 5055:5055 -v $RUNNER_TEMP/traccar.xml:/opt/traccar/conf/traccar.xml traccar/traccar:6.16.0`.
   - This is a plain `docker run` step, not a `services:` container, because a service container starts before the config file can be rendered.
   - Wait for `GET /api/server` to return `200`, for up to 120 s. On failure, `docker logs traccar` is printed.
   - Then `TRACCAR_URL=http://localhost:8082 TRACCAR_GT06_HOST=localhost TRACCAR_GT06_PORT=5023 ./gradlew :sim:integrationTest`.
   - A `grep` step asserts the test report shows `tests="2"` and `failures="0"`, so that a skipped integration test **fails** the job.

- [ ] **Step 1:** Write `ci.yml` as above. Push to a branch `p0/ci`.
- [ ] **Step 2: Verify the failure path.** Temporarily point `TRACCAR_GT06_PORT` to `5099` in a scratch commit. Watch `traccar-integration` fail with `position not recorded within 30s` or a connection error, then revert. Record the run URL in the PR description.
- [ ] **Step 3: Verify the exit gate.** All three jobs are green on `p0/ci`. Run: `gh run list --branch p0/ci --limit 1` shows `completed success`. Download the APK artifact and confirm it exists: `gh run download <id> -n hirz-debug-apk`.
- [ ] **Step 4:** Open a PR `p0/ci → main`. Merging is the owner's decision; do not merge yourself.
- [ ] **Step 5: Commit:** `ci: add build, guards and the Traccar round-trip gate`

---

## P0 Exit Gate (all must hold, each with visible evidence)

- [ ] `toufikben/hirz` exists, is private, and holds the docs, research, and legacy references. `kidguard-core` is untouched.
- [ ] CI is green on all three jobs, and the run URL is recorded.
- [ ] The simulated GT06 fix at `(36.7538, 3.0588)` is read back from real Traccar 6.16.0 in CI.
- [ ] The failure path is proven: a broken port makes the integration job red.
- [ ] The debug APK artifact exists. Optional: it is installed on the owner's Realme via `adb install`, with a screenshot showing "حِرز" in RTL.
