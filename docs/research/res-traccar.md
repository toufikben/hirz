Research complete. All Traccar claims below were verified against the live docs and against a shallow clone of the current source (`traccar/traccar` @ master, v6.16 era) kept at `C:\Users\Administrator\Documents\Qoder\2026-10-05\6bdda3ce\_research\traccar` for follow-up. Report follows.

---

# Traccar Feasibility Dossier — Child-Safety GPS System (KidGuard)
*Researched 2026-10-05. Server verified against v6.16.0 (released 2026-09-27, GitHub releases API). Code citations refer to `master` as of that date.*

## 1. Install & Resources

**Current stable:** 6.16.0 (2026-09-27); prior 6.15.3 (2026-08-26). Release cadence is roughly monthly — plan on tracking upstream, not forking. ([Download](https://www.traccar.org/download/), GitHub releases API: `api.github.com/repos/traccar/traccar/releases`)

**Stack:** Java backend, **Java 21** required (`build.gradle: sourceCompatibility = JavaVersion.VERSION_21`). License **Apache 2.0** (README + LICENSE.txt). 677 protocol-related Java classes in `src/main/java/org/traccar/protocol/`; README claims "more than 200 GPS protocols".

**Docker vs jar:** Docker is the cleanest path — official image `traccar/traccar` and maintained compose templates in the repo (`docker/compose/traccar-mysql.yaml`, `traccar-timescaledb.yaml`) that wire Postgres/TimescaleDB via env vars (`DATABASE_URL`, `CONFIG_USE_ENVIRONMENT_VARIABLES=true`), plus an autoheal sidecar. The docs page ([Docker](https://www.traccar.org/docker/)) explicitly warns: (a) **default H2 embedded DB "is not recommended for production"** (use MySQL/Postgres/TimescaleDB); (b) publishing a wide protocol port range (e.g. `5000-5300`) makes Docker spawn one userland proxy per port → RAM blowup — fix with `"userland-proxy": false` in `daemon.json` **or** only enable the protocols you need (`protocols.enable` config key, confirmed in `ServerManager.java` which starts only protocols whose `<name>.port > 0`).

**Resource reality for ~2–10 devices:** 1 GB RAM is *workable but tight* with the Postgres sidecar. Evidence: forum thread ["Minimum memory, CPU 100%"](https://www.traccar.org/forums/topic/minimum-memory-cpu-100/) (2024): maintainer Anton Tananaev — *"If you optimize it well 1GB is probably enough"*; a user runs 1 CPU/1 GB Linode at "barely 5% CPU"; another needed +1 GB swap. Java 21 + Netty + Jersey is not a tiny-footprint stack; with Docker+Timescale, **2–4 GB is the comfortable target**. Disk: positions are tiny rows; a handful of trackers at 1–5 min intervals is <1 GB/year. CPU matters only for geocoding (offload to Nominatim self-hosted → expensive; use API with quota or skip reverse geocoding in MVP).
**Recommendation:** enable only the 1–2 protocols you use (`protocols.enable=gt06,watch`), publish only those ports, H2 for the first week only, Postgres for anything real.

**Cheapest realistic hosting (2026-10, EUR/USD, per [sliplane 2026 roundup](https://sliplane.io/blog/top-5-cheap-vps-providers), [Hetzner](https://www.hetzner.com/cloud/), [Contabo](https://contabo.com/blog/best-budget-vps-developers/)):**

| Provider | Plan | Specs | Price | 1 GB suffices? |
|---|---|---|---|---|
| RackNerd | tiny VPS | 1 CPU / 512 MB | ~$22.99/yr | No for Postgres; H2-only, marginal |
| **Hetzner** | CX/CPX entry | 2 vCPU / 4 GB | **€3.29/mo** | Yes, comfortable; EU locations (DE/FI) + US/SG |
| Netcup | entry | 2 vCPU / 2 GB | €3.35/mo | Yes with swap |
| Contabo | VPS S | 4 vCPU / 6 GB | €4.50/mo | Yes (overspec for MVP; weaker SLA reputation) |

For an Algerian dev: Hetzner/Contabo card/PayPal acceptance from DZ is the practical constraint — **unverified assumption, test account creation early**. Latency to Europe is irrelevant for UDP/TCP tracker ingest at this scale.

**TLS/HTTPS:** Traccar itself serves plain HTTP on `web.port` (default **8082**; confirmed in config docs). Standard deployment = nginx/Caddy reverse proxy + Let's Encrypt, WebSocket proxied at `/api/socket`. HTTPS is **mandatory in practice**: Traccar Client/Manager expect it on custom servers, your Capacitor app must hit HTTPS/WSS, and session cookies over plain HTTP would leak child-location auth. Relevant keys: `web.origin`, `web.sessionTimeout`, `web.sameSiteCookie` (all present in `Keys.java`).

## 2. Device Ingestion (protocols & the redirection make-or-break)

**Protocol identifiers verified on [traccar.org/devices/](https://www.traccar.org/devices/) and in source** (`src/main/java/org/traccar/protocol/*`):

| Family | Traccar protocol ID | Default port (docs/identify-protocol) | Decoder+Encoder |
|---|---|---|---|
| GT06 (Concox/Jimi, GT02L/GT06N/JC400, "TKSTAR-style" and countless clones) | `gt06` | **5023** | `Gt06Protocol`, `Gt06ProtocolEncoder` (commands supported) |
| TK103/TK102 old text | `tk103` | 5002 | encoder yes |
| Coban/gps103 clones | `gps103` | 5001 | encoder yes |
| Meiligao / Jimi iCan | `meiligao` | 5009 | encoder yes |
| Suntech | `suntech` | 5011 | encoder yes |
| Queclink | `gl100`/`gl200` | 5003/5004 | — |
| Kids watches (TKSTAR-style text) | `watch` | **5093** (`WatchProtocol*.java`, text CSV frames incl. battery/SOS fields) | encoder yes |
| H02 / many "GSM-only" trackers | `h02` | 5013 | — |
| Generic discovery | send device to port **5001**, then sample raw hex in logs (`database.saveOriginal` stores raw payload) | — | [Identify-protocol guide](https://www.traccar.org/identify-protocol/) |

**What the tracker must be configured to send:** your static IP/domain (a **numeric IP is safer than a domain — carriers and cheap firmware mangle DNS/SMS URLs**; SinoTrack v2 firmware even has a `GURL:ON` Google-URL shortcut param) + TCP port of the protocol + APN of the local SIM (Algérie Mobilis/Djezzy/Ooredoo APN) + upload interval; device IMEI becomes Traccar's `uniqueId` and the device auto-registers on first contact unless you disable it.

**Hard evidence on redirecting cheap trackers (this is where the risk lives):**
- **Old-school TK/GPS103 devices: fully redirectable by SMS.** Forum "how to configure trackers to ur own server" (2019–2022): gps103 command `adminip123456 202.105.135.50 9000` → replies "adminip OK". ([thread](https://www.traccar.org/forums/topic/how-to-configure-trackers-to-ur-own-server/))
- **SinoTrack ST-901 (GT06 family): redirectable.** Forum thread (2025-04): SMS `8040000 <IP> <port>` works; caveats: some Algerian-type carriers strip domains from SMS (use raw IP), old firmware revisions behave differently. ([thread](https://www.traccar.org/forums/topic/st-901-sinotrack-to-traccar/))
- **SeTracker/Wonlex kids watches: NOT reliably redirectable.** Thread (2022-08): attempted `pw,123456,ip,1.2.3.4,5093#` → *"Wonlex KT12 have fixed IP which can not be changed through SMS"*, payload proprietary-encrypted, and the Q90 watch thread: units ship with a **locked transmission mode; the only way to turn off the encryption "is flashing the ROM"** — some users returned devices. ([Q50/Q90 thread](https://www.traccar.org/forums/topic/trouble-identifying-protocol-for-kids-smart-watch-q90/), [Q50 watch protocol](https://www.traccar.org/forums/topic/q50-watch-protocol/), [how-is-my-device-supposed-to-find-my-traccar-server](https://www.traccar.org/forums/topic/how-is-my-device-supposed-to-find-my-traccar-server/))
- Ecosystem note: SeTracker traffic resembles GT06 (bracket-ID + comma fields — see [yaqin-watch-protocol Go lib](https://github.com/faraganiev/yaqin-watch-protocol)); open-source redirect/emulation projects exist (e.g. [mesh12/SETracker](https://github.com/mesh12/SETracker), unmaintained) but **CocoTracker has no documented custom-server path found in this research — treat as unsupported until proven**.
- **"JT518": I found no public documentation of this model at all** (searches returned nothing specific). **Unverified assumption:** it is a GT06-derived device (JT- prefix suggests Jimi-style) sold under SeTracker-like branding in DZ. Model-number ≠ protocol — buy once, identify once, then standardize.

**Verdict:** GT06/Meiligao/TKSTAR-class pucks/pendants (SinoTrack, Concox, Jimi, TKSTAR GT06N) → redirection is a solved, documented problem. SeTracker/CocoTracker-branded kids watches → **coin flip; must be validated physically (see §First-week plan)**. Buy a watch only after the SMS `ip` command is confirmed on that exact firmware, or buy a redirect-capable GT06N puck (~2000–3500 DZD range) instead.

## 3. Data Model & API

Verified against the repo's `openapi.yaml` (authoritative, v6.16) and [Traccar API docs](https://www.traccar.org/traccar-api/).

**Endpoints you need (exact paths):** `/api/devices`, `/api/positions` (+ `/positions/{id}/kml|gpx|csv`), `/api/geofences`, `/api/events/{id}` (event CRUD; **event history is read via `/api/reports/events`**, not a bare `/events` list), `/api/notifications` (+ `/notifications/types`, `/notificators`, `/test/{notificator}`, `/send/{notificator}`), `/api/users`, `/api/groups`, `/api/permissions` (+`/permissions/bulk`), `/api/share/device`, `/api/share/group`, `/api/commands` (+`/commands/send`, `/commands/types` — remote config SMS through the server), `/api/reports/{route,events,geofences,summary,trips,combined}`, `/api/session`, `/api/session/token`, `/api/session/token/revoke`, `/api/users/totp`, `/api/server`, `/api/health`.

**Authentication (3 options, all in source):**
1. Session cookie (`POST /api/session` with `email`/`password`; salt+SHA1 hashing scheme in `LoginBaseService`).
2. HTTP Basic.
3. **Bearer/JWT access token**: `POST /api/session/token` → token usable as `Authorization: Bearer …` on all REST endpoints and as `?token=` on the WebSocket (`AsyncSocketServlet.java:62-66` reads `token` param, falls back to session-cookie + **Origin validation** — `SessionHelper.isSessionOriginValid`, line 70). Token revocation endpoint exists. This is exactly the "access key" story a 3rd-party Capacitor client needs: create two parent users server-side, mint long-lived tokens, never store passwords in the app.
- **Rate limiting: none found** — no 429 logic, no login throttle in `src/main/java/org/traccar/api/` (grepped). You must add `nginx limit_req` on `/api/session` yourself.

**Multi-parent sharing without code changes:** yes — Traccar's permission model is per-object: `POST /api/permissions {userId, deviceId}` shares a device with another user; geofences can likewise be shared to multiple users; **all users with device access receive its events/notifications** (`NotificationManager.java` iterates the device's user set from the cache). Two parents = two regular users + one shared device (or a group). Your React/Capacitor app can consume the REST+WS API purely as an external client; nothing in the child-safety flow requires touching server code.

## 4. Alerting Built-in (verified in source, v6.16)

**Notificators (`NotificatorManager.java:45-54`):** the registered set is `command, web, mail, sms, firebase, traccar, telegram, whatsapp, pushover`. Default `notificator.types = "web,mail,command"` (`Keys.java:1430`). Note: **there is no `webhook` notificator** in current source (older 5.x had one; replaced by the event forwarder, below). `traccar` = official push via Traccar's own FCM project (`notificator.traccar.key`); `firebase` = your own Firebase service account (`notificator.firebase.serviceAccount`, `…firebase.mode`) → **direct Firebase push into your Capacitor app is natively supported**.

**Exact config keys (verified):**
- Telegram: `notificator.telegram.key=<bot token>`, `notificator.telegram.chatId=<default chat>`, optional `notificator.telegram.sendLocation=true` (sends map link/coords; `NotificatorTelegram.java` posts `sendMessage`/`sendLocation`, supports `notificator.telegram.proxy.url`, `…base.url`).
- SMTP: `mail.smtp.host/port/username/password`, `mail.smtp.ssl.enable`, `mail.smtp.ssl.trust`, `mail.smtp.timeout` (+`mail.subject`, `mail.from` per docs page).
- SMS: generic HTTP gateway: `sms.http.url` with `{phone}`/`{message}` placeholders, `sms.http.authorization`/`sms.http.user|password`/`sms.http.authorizationHeader`, `sms.http.template` (JSON body template) — plug any SMS provider (Twilio-compatible REST or local).
- Per-user routing: SMS/WhatsApp pull the number from the user account; Telegram chatId can be per-user attribute; Velocity templates (`templates.root`) localize messages. Users subscribe via `/api/notifications` rows (type + event types + calendar/geofence/device attributes) — fully drivable from your app.

**Event catalog (`model/Event.java` constants, verbatim):** `commandResult, deviceOnline, deviceUnknown, deviceOffline, deviceInactive, queuedCommandSent, deviceMoving, deviceStopped, deviceOverspeed, deviceFuelDrop, deviceFuelIncrease, geofenceEnter, geofenceExit, geofenceCrossed, proximityEnter, proximityExit, unaccompaniedMotion, alarm, ignitionOn, ignitionOff, maintenance, driverChanged, media`.
- **SOS: yes, end-to-end.** `Gt06ProtocolDecoder.java:432,451,1183` map the GT06 alarm bits to `Position.ALARM_SOS`; `AlarmEventHandler.java:54` turns any position alarm into an `Event(TYPE_ALARM)`; device-reported alarm types (`AlarmType` in docs: sos, general, vibration, overspeed, lowPower/lowBattery, tamper…) ride on the alarm event's attributes. The watch protocol decoder parses SOS + low-battery fields from its CSV frames too.
- Battery-low → decoder sets `Position.KEY_BATTERY` + alarm → `alarm` event with `alarm=battery`; offline/not-reporting → `deviceOffline` & `deviceInactive` events (timeouts `server.inactiveTimeout`, `server.offlineTimeout`).
- Abduction-relevant extras already built: `geofenceExit` (school geofence), `deviceOverspeed` (vehicle-abduction signal: sudden high speed), `unaccompaniedMotion` (motion without authorized Bluetooth presence — niche), `deviceStopped` far from home.

**Custom relay (the fan-out path):** `event.forward.url` (+ `event.forward.type=json`, `event.forward.header` for an auth header; `Keys.java:1155-1193`) — server POSTs `EventData{event, position, device, geofence, maintenance}` JSON of **every event to your Node relay** (`forward/EventForwarderJson.java`). Similarly `forward.url`/`forward.type` streams positions (url/json/kafka/mqtt/amqp/redis implementations all present in `src/main/java/org/traccar/forward/`). Retry semantics exist for positions (`forward.retry.*`).

## 5. Extending

- **Integrate (zero fork):** everything KidGuard needs — custom alert fan-out (event.forward webhook → Node relay → Firebase/Telegram/SMS cascade, escalation timers), custom client UI (REST+WS+tokens), remote config (`/api/commands`) — is achievable without recompiling. This is the strongly recommended posture given Apache-2.0 upstream moves monthly.
- **Fork/patch cost:** no official plugin system for business logic; new decoders = add Java classes under `org.traccar.protocol` (auto-discovered by `ServerManager` classpath scan, so it's even possible in a downstream build), custom notification logic = editing `notificators/` + rebuilding the jar (Java 21 + Gradle; the front-end traccar-web is a separate repo). Fork maintenance = re-merging every month; only worth it if you must change the permission model or embed the server with your app.
- Your existing geofence/risk logic in the Capacitor app stays authoritative client-side; the Traccar relay gives you redundancy (server events arrive even when the app is killed — that's exactly what a webhook→push relay fixes).

## 6. Official mobile apps

- **Traccar Manager** ([repo](https://github.com/traccar/traccar-manager) "Mobile app for viewing and managing devices tracked by Traccar", [page](https://www.traccar.org/manager/), **Apache 2.0**, actively pushed 2026-09): map of devices, geofences, real-time notifications, works against any self-hosted server (globe icon at login → your URL). Each parent needs **their own Traccar user account** (no multi-device single-account sharing UI), which you'd provision + share the device to — fine for 2 parents. **A parent could use Manager as day-1 UI while you build the Capacitor app.** Note Android/iOS repos were archived in favor of a consolidated repo — still maintained.
- **Traccar Client** ([repo](https://github.com/traccar/traccar-client), Apache 2.0, pushed 2026-10-04): turns a phone into a GPS tracker (reports locations to your server; interval + battery saver + remote commands). Useful as a **backup tracker on a second phone** and for field-testing the ingest path without hardware; there's also a **Traccar Client SDK** ("background location tracking SDK", active) should you ever offer phone-only tracking — not a substitute for a worn tracker for small children.

## 7. Privacy / Security (children's location data)

- **CVE-2025-68930** (published ~2025/2026, CVSS 6.5 Medium): `/api/socket` accepted WebSocket upgrades **without Origin validation** ≤ v6.11.1 → drive-by cookie theft of live location streams ([SentinelOne entry](https://www.sentinelone.com/vulnerability-database/cve-2025-68930/)). **Verified fixed in current source**: `AsyncSocketServlet.java` now calls `SessionHelper.isSessionOriginValid(...)`. Run ≥6.12, ideally 6.16.
- Older hardening history worth knowing: the 2024-10 ["Traccar Vulnerability"](https://www.traccar.org/forums/topic/traccar-vulnerability/) thread and a 2025 cryptominer-on-VPS incident (from the 2024 thread: internet-exposed installs get scanned and abused) — the exposure is weak credentials + open registration, not remote exploits.
- **Default-credential traps (source-verified):** H2 DB ships with user `sa`, **empty password** (`Keys.java:562-569` docstrings); compose templates use `traccar/traccar` Postgres creds — change them. Self-registration for new users can be enabled/disabled (disable on production; OpenID `allowRegistration` flags exist).
- Multi-tenancy: single-instance role/permission model (admins vs regular users); users cannot see others' data unless shared — acceptable for 2-parent product, but object-level permissions are coarse (no per-field redaction).
- **Data retention: no automatic position purge exists in v6.16 source** (grep for retention/keepDays found only `web.requestLog.retainDays` and `users.defaultExpirationDays`). For a child-safety product you must implement purge yourself: cron `DELETE FROM tc_positions WHERE fix_time < now()-interval '90 days'` (schema is plain SQL tables `tc_*`), and expose a delete-account path.
- Hardening checklist: HTTPS-only (nginx), `limit_req` on `/api/session`, non-default admin, disable H2 → Postgres, firewall protocol ports to tracker IPs is impractical (mobile IPs) → use device `password` attr (`device.password` key exists) + uniqueIds whitelist (`filter.allowOffline` etc.), bind `web.port` to localhost, back up Postgres, `web.totpEnable`/`totpForce` available for parent accounts (source keys `WEB_TOTP_ENABLE/FORCE`).

## 8. Alternatives — only where Traccar has a real limitation

| Criterion | **Traccar self-hosted** | **Node/TS own ingest** (`gt06` parsers, netty-style) | **Flespi** | **OwnTracks** | **Tracker's own cloud** (SeTracker2/SinoTrack) |
|---|---|---|---|---|---|
| Protocol coverage | 200+ incl. gt06/watch/meiligao/suntech — *verified* | You write per-firmware variants (encrypted Wonlex = blocker) | Good, commercial support | Phone-only (but Traccar has `owntracks` decoder) | Locked ecosystem |
| Geofence/event/notification engine | Built in, JSON-forwardable | Rebuild everything | Paid feature set | None | Opaque |
| Cost at 1–10 devices | ~€3.3–4.5/mo VPS | same + dev time | Free dev ≤10 devices, **€130/mo** production ([pricing](https://flespi.com/pricing)) | ~free | "free" = your data hostage |
| Child-privacy control | Full (self-host, purge scripts) | Full | Data in Latvia/EU, GDPR ok but vendor | N/A | None |
| Build speed | Hours | Months | Days (if budget) | N/A | N/A |
| Fit with your Capacitor app | REST+WS+Bearer, Apache-2, no fork | n/a | MQTT/webhooks | n/a | reverse-engineered APIs, ToS risk |

**Decision: keep Traccar.** No blocking limitation found; the only genuine limitation is the tracker-side firmware lottery (§2) — which equally breaks every alternative except the vendor's own cloud. Hybrid option worth keeping in mind: your Capacitor app *can* also receive the child-phone fallback via the `owntracks`/`osmand` decoders (both verified present in the protocol dir) if you ever need a second reporting source.

---

## Recommended end-to-end data path

```
Child tracker (GT06-class, SIM/APN + SMS-configured server IP:5023, 60–120 s interval, SOS wired)
   │ TCP gt06 (or watch:5093 if validated redirectable)
   ▼
VPS (Hetzner 2vCPU/4GB, Docker): traccar 6.16 + TimescaleDB, only gt06/watch ports enabled
   ├─ REST /api (Bearer tokens for the 2 parent users; shared device permissions)
   ├─ WS /api/socket?token=… → live map + events inside Capacitor parent app (foreground)
   ├─ native notificators: telegram (parents' chat), mail (backup), firebase (push into app via notificator.firebase.serviceAccount)
   └─ event.forward.url = https://relay.yourdomain/ingest (JSON EventData + secret header)
        ▼
      tiny Node relay (can run on the same VPS): escalation state machine —
        ack window, re-send, second parent, SMS cascade, offline-timeout sweeper, audit log
```
Keep the app's existing geofence/risk logic as the *fast local layer*; Traccar events are the *always-on server layer*; the relay is the *escalation layer*.

## 5 highest-risk unknowns (validate by experiment before committing)

1. **Tracker redirectability of the exact SKU sold in Algeria ("JT518" and SeTracker/CocoTracker watches).** No public evidence found for JT518; documented cases of locked/encrypted Wonlex firmware. → Buy candidate models locally, test SMS `ip/server` commands against a listening Traccar before any app work depends on them.
2. **SOS button actually reaching `alarm:sos` through the unit's firmware** (some clones send SOS as a phone-call, not a data packet). → Press test on day 3.
3. **1 GB-class instance viability under the real stack (Docker + Postgres + WS)** and the 5000-5300 port-range memory trap. → Deploy with `protocols.enable` restricted + userland-proxy off; watch `docker stats` for a week.
4. **Reliability of Algérie Telecom data connection + carrier SMS filtering of IP-bearing commands** (documented domain-stripping issues). → Test with a cheap data SIM; hardcode IP; verify APN.
5. **No built-in login throttle/rate limiting + open registration defaults** → your server exposing children's live positions. → Harden before any real child is onboarded; brute-force test your own box.

## First-week validation plan (1 tracker + 1 Realme phone w/ adb + demo VPS)

- **D1 (€0–3):** Hetzner/Contabo trial box; `docker compose up` with the repo's `traccar-timescaledb.yaml`; restrict `protocols.enable=gt06,watch`; nginx+certbot; create admin user, **disable registration**; register device with `uniqueId=IMEI`, note port 5023/5093.
- **D2:** Buy the GT06-class tracker; SMS-config APN + server IP + port (capture the vendor manual; screenshot the exact working command — this is your hardest artifact). Confirm positions land in Traccar (`/api/positions` with curl+Basic). Use the Realme (adb + Termux or Traccar Client app pointed at your server) as a second `osmand`/client data source to simulate the 2nd reporting path.
- **D3:** SOS button, low-battery (let it drain), geofence enter/exit around your home; check each appears in `/api/reports/events` and as WS frames (`wscat -H Authorization` or your app); verify `event.forward.url` POSTs to a 20-line Node `http.createServer` relay.
- **D4–5:** Telegram bot + `mail.smtp` + Firebase push (Manager app on the Realme as reference UX); share the device to a second user; prove notifications fan out to **both** users simultaneously.
- **D6:** Kill-switch tests: pull SIM, block port at firewall, watch `deviceOffline` event timing; reboot VPS, verify positions buffered by tracker arrive on reconnect (GT06 uploads blind-zone history — verify your model does).
- **D7:** If the watch/SKU failed §2 → test alternate redirectable model; decide final BOM. Write down: measured RAM (`docker stats`), event→push latency, and the exact SMS command sheet per model.

**Unverified assumptions flagged:** Hetzner/Contabo signup from Algeria; JT518's underlying protocol; CocoTracker custom-server path; per-model SOS-over-data behavior; Tracker buffering behavior on reconnect; your device's battery reporting granularity in the `watch` decoder.

Sources (primary): [Download 6.16.0](https://www.traccar.org/download/) · [Docker](https://www.traccar.org/docker/) · [Devices/protocols](https://www.traccar.org/devices/) · [Identify protocol](https://www.traccar.org/identify-protocol/) · [API](https://www.traccar.org/traccar-api/) · [Notifications](https://www.traccar.org/notifications/) · [Events](https://www.traccar.org/events/) · [Config file reference](https://www.traccar.org/configuration-file/) · [Source traccar/traccar @6.16](https://github.com/traccar/traccar) (openapi.yaml, Keys.java, Event.java, Gt06ProtocolDecoder.java, NotificatorManager.java, AsyncSocketServlet.java, forward/, docker/compose/) · [Manager](https://www.traccar.org/manager/) · [Client](https://www.traccar.org/client/) · [CVE-2025-68930](https://www.sentinelone.com/vulnerability-database/cve-2025-68930/) · [NVD CVE-2025-61666](https://nvd.nist.gov/vuln/detail/CVE-2025-61666) (JS-blocked during fetch — **unverified**) · forum threads on [memory](https://www.traccar.org/forums/topic/minimum-memory-cpu-100/), [server redirection](https://www.traccar.org/forums/topic/how-to-configure-trackers-to-ur-own-server/), [ST-901](https://www.traccar.org/forums/topic/st-901-sinotrack-to-traccar/), [SeTracker locking](https://www.traccar.org/forums/topic/trouble-identifying-protocol-for-kids-smart-watch-q90/), [vulnerability reports](https://www.traccar.org/forums/topic/traccar-vulnerability/) · [hosting 2026 comparison](https://sliplane.io/blog/top-5-cheap-vps-providers) · [Flespi pricing](https://flespi.com/pricing) · [yaqin-watch-protocol](https://github.com/faraganiev/yaqin-watch-protocol).