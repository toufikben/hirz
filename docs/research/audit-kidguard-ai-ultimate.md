Audit complete. All findings below carry file:line evidence. No files were modified.

---

# KidGuard AI Ultimate — Architecture Audit

Scope confirmed: React 19 + TS + Vite + Capacitor 7 + Supabase, ~5026 LOC in `src/`, 6 Java files in `android/`, 2 SQL migrations. Last commit `2026-09-15` (`git log -1`). No `docs/`. No test files anywhere (`find` for `*.test/*.spec/__tests__` returned nothing).

## 1. Intended architecture (parent/child device, data flow, Supabase)
- Roles are per-device, chosen at onboarding: `DeviceRole = 'parent' | 'child' | 'vehicle'` (`src/types.ts:5`), set via `setRole` (`src/App.tsx:84-88`) and persisted in Zustand (`src/store/useAppStore.ts:133`). It is a **two-phone** model (parent phone ↔ child phone), NOT parent-phone + wearable.
- Data flow: child runs `ChildView` (`src/ui/child/ChildView.tsx`) sending location/heartbeat; parent runs `ParentDashboard` (`src/ui/parent/ParentDashboard.tsx`) watching. App chooses branch on role at `App.tsx:119`.
- Supabase tables (`supabase/migrations/001_initial.sql`): `devices` (role, `public_key`, `fcm_token`; line 7), `pairings` (6-digit code; line 19), `last_known_location` (encrypted_payload+nonce; line 30), `heartbeats` (battery/network; line 38), `incidents` (encrypted; line 49), `signaling` (WebRTC SDP/ICE; line 59). RLS is strict owner-scoped (`001:81-117`) — every policy keys off `auth.uid() = devices.user_id`. Realtime publication adds `signaling`, `last_known_location`, `incidents` (`001:123-125`). Plus a `keepalive` table + daily cron edge function to stop free-tier pausing (`002_keepalive.sql`, `supabase/functions/keepalive/index.ts`). **No `guardians`/roles/invites table** (see §6).

## 2. E2EE / P2P / realtime / heartbeat foundation — mostly NOT wired
- Crypto is real and sound: libsodium X25519 → `crypto_box_beforenm` shared key (`src/crypto/e2ee.ts:34`), secretbox / XChaCha20-Poly1305 AEAD (`e2ee.ts:41-91`), HMAC via `crypto_auth` (`e2ee.ts:96`), pairing code (`e2ee.ts:121`). Identity keypair persisted via **Capacitor Preferences, not Android Keystore** (`src/crypto/keyExchange.ts:47`) — private key stored on-device but unprotected at rest.
- Transport: WebRTC `simple-peer` DataChannel, signaling over Supabase Realtime `signaling` table (`src/p2p/WebRTCManager.ts:55`, `src/p2p/signaling.ts:25`). STUN only, **no TURN** (`WebRTCManager.ts:60-63` comment) — will fail behind NAT.
- **Wiring gap (critical):** `encryptObject`/`getSharedKey` are used ONLY by `EncryptedQueue` (`src/services/offline/EncryptedQueue.ts:53-54`), which is itself used only by `SmtpService`/`SmsService` (grep). The live paths are plaintext: child broadcasts `send({ type:'location', payload:currentLocation })` unencrypted (`ChildView.tsx:32`); `pushEncryptedLocation`, `subscribeToLocation`, `subscribeToIncidents`, `insertIncident` are **defined but never called** (`src/services/supabase/realtime.ts:44,66,104,133`); `setPeerLocation` is never invoked (`useAppStore.ts:167` only definition) so parent's `LiveMapTab` peer marker stays empty (`src/ui/parent/LiveMap.tsx:9`).
- Heartbeat: `HeartbeatProtocol` inserts **plaintext** `battery,network` to `heartbeats` and parent **polls every 20s** (`src/core/HeartbeatProtocol.ts:96-100,115-137`). The HMAC signature is computed but **never sent or verified** (`:91-93` vs `:96`). It is NOT realtime/P2P — contradicts the README "P2P heartbeat" claim.

## 3. "Local AI" / risk engine — heuristics only, and unmounted
No ML model, no inference, no on-device NN. All are statistical/heuristic classes: `AnomalyDetector` = grid-cell frequency map (`src/ai/AnomalyDetector.ts:18`, comment "no ML model needed" `:4`); `MotionSignature` = per-hour mean/variance z-score + hard thresholds (`src/ai/MotionSignature.ts:11-13,66-75`); `ZoneLearner` = 1-NN clustering (`ZoneLearner.ts:29`); `PredictiveSafetyScore` = fixed weighted sum (`PredictiveSafetyScore.ts:21-47`); `RiskScorer` = additive `RISK_WEIGHTS` lookup (`src/ai/RiskScorer.ts:39-56`, weights in `src/utils/constants.ts:93`). Signals consumed include `heartRateAnomaly`, `motionAnomaly`, `mockScore` (`RiskScorer.ts:11-18`) — but **the whole engine is dead**: `useRiskEngine` is imported by nothing, and none of `RiskScorer/AnomalyDetector/MotionSignature/ZoneLearner/PredictiveSafetyScore` are instantiated anywhere (grep). Motion IS streamed (`src/hooks/useSensorStream.ts:42-53`) via `@capacitor/motion` but no consumer is wired (no listener of `kg:motion` except `useLocation`'s fusion).

## 4. Duress / abnormal-physiology detection
- Abnormal HR logic exists but is heuristic and unmounted: `HeartRateService.detectAnomaly` flags `bpm>130 && !isMoving` or `|bpm-avg|>45` (`src/services/heartrate/HeartRateService.ts:52-62`); `useHeartRate` that would surface it is imported by nothing (grep). So **no HR alert reaches UI**.
- Panic button: exists in UI but only fires a **local notification**, no network send: `ChildView.tsx:92-97` `escalate(...severity:'critical')` → `notify()` local-only (§5/NotificationManager).
- Panic *word* detection: `SilentPanic` uses `@capacitor-community/speech-recognition` (`src/core/SilentPanic.ts:38`, word list `constants.ts:27`) but is **never instantiated** (grep) — dead.
- Stillness/fall/snatch: only inside dead `MotionSignature` (`MotionSignature.ts:44-64`).
- **Tamper/removal of a wearable: absent** (no bracelet concept anywhere).
- Dead-man check-in: `DeadManSwitch` is wired (`src/ui/child/CheckInPrompt.tsx:8`). Anti-theft motion: `AntiTheftMode` wired to `AntiTheftPanel.tsx:4`.

## 5. Native plugins & Android capabilities
Four Capacitor plugins registered in `MainActivity.java:9-12`:
- `BleHeartRatePlugin.java` — BLE GATT Heart Rate Service `0x180D`/char `0x2A37`, scan+connect+notify (`:46-47,99-133`); first-scan-wins, 15s timeout, no reconnection/pairing UI.
- `AutomaticSmsPlugin.java` — `SmsManager` direct send (`:53-59`).
- `SmtpEmailPlugin.java` — javax.mail over a background thread (`:41-69`). (Note: `android/` has no mail dependency visible in build.gradle — may not compile; unverified.)
- `HeartbeatForegroundPlugin.java` + `HeartbeatService.java` — foreground service type `location|dataSync`, ongoing notification (`HeartbeatService.java:35-40`).
Manifest exposes: background location, foreground service, POST_NOTIFICATIONS, SEND_SMS, READ_PHONE_STATE, BLE scan/connect, BODY_SENSORS, RECORD_AUDIO, VIBRATE, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS (`AndroidManifest.xml:9-42`). **No Keystore** (keys in Preferences), **no battery-optimization runtime handling**, no respiration/spO2 characteristic. Community plugins in `package.json`: background-geolocation, bluetooth-le, speech-recognition, motion (bluetooth-le/motion/speech are barely/never used by code).

## 6. Multi-parent support — essentially absent
Schema only models a single `parent_device_id` + `single child_device_id` per pairing (`001:22-23`); RLS treats devices as owned by one `user_id` (`001:83`). `FamilyMesh` ("one parent, multiple children") exists but is **never used** (`src/core/FamilyMesh.ts:20`, grep empty). No roles, no guardian invitations, no notification fan-out. Escalation only produces a local notification; SMS/SMTP/push channels are declared (`types.ts:9` `AlertChannel`) and implemented (`SmsService.ts`, `SmtpService.ts`) but **not connected** — `AlertEscalation.escalate` calls only `notify()` (`src/core/AlertEscalation.ts:41`).

## 7. State of completion / why stalled
- ROADMAP (`ROADMAP.md`) claims PART 1-5 complete, TypeScript `tsc --noEmit` passing, signed APK/AAB built. That is plausible for *compilation* (all files are type-valid; `RISK_WEIGHTS` is defined at `constants.ts:93`), but the roadmap overstates function: the E2EE, risk engine, HR, mesh, realtime-location, incident-persistence, SMS/SMTP layers are all **compiled-but-unwired dead code**. A blocker is documented at `ROADMAP.md:22-24`: Supabase PostgREST returns **401** with the project's API keys, so REST/Realtime never validated on device — likely the direct cause of the stall (nothing end-to-end could be tested). Only real functional flows today: pairing code round-trip (`PairingService.ts`), heartbeat poll, local notifications, child SOS-to-local, dead-man, anti-theft panel.

## 8. Verdict — salvage vs rebuild
**Salvage (as foundation for parent-side + wearable):**
- Supabase schema + strict per-owner RLS + realtime publication (`001_initial.sql`) and the `keepalive` cron pattern.
- libsodium E2EE primitives + X25519 identity (`crypto/e2ee.ts`, `crypto/keyExchange.ts`) — move key storage to Android Keystore.
- WebRTC + Supabase signaling scaffolding (`p2p/*`) — add TURN.
- Heartbeat + foreground service (`core/HeartbeatProtocol.ts`, `HeartbeatService.java`) — but switch poll→realtime and actually transmit the HMAC.
- BLE HR plugin (`BleHeartRatePlugin.java`) — closest existing artifact to the bracelet; extend for respiration/multiple characteristics + reconnection.
- i18n (25 langs, RTL) `constants.ts:37-70` + Zustand settings store.

**Rebuild:**
- Data path must be encrypted end-to-end (ChildView plaintext `:32`, unused `pushEncryptedLocation`/`setPeerLocation`).
- Risk/duress engine: replace heuristics with real wearable HR/respiration/step inference and actually mount `useHeartRate`+`useRiskEngine`; add tamper/removal detection (**absent today**).
- Multi-guardian model: add `guardians`/invitation + fan-out tables (schema only supports 1 parent↔1 child); wire SMS/SMTP/push escalation (currently local-only).
- Bracelet device role + provisioning; the current `child` role assumes a phone.

Bottom line: this repo contains the **parent-side scaffolding** (pairing, realtime, heartbeat, BLE HR, E2EE primitives) that the other repo lacked, but the "wearable + kidnapping/duress" half is present only as unmounted heuristic code and a single-phone data model — strong parts to reuse, the sensing/duress/multi-parent layers to build fresh.