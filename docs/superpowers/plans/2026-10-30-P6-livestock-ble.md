# Hirz P6 — Livestock, Store-and-Forward and the BLE Relay Implementation Plan

> **ملخص بالعربية:** المرحلة P6 تحمي **المواشي** في المراعي التي لا تغطية فيها، وتضيف **قناة BLE** التي أُجّلت إليها أعمال P4 وP5:
> - **القطيع:** مجموعة رؤوس، لها «قائد» مجهز بمتتبع والبقية **أطواق BLE** أرخص.
> - **القواعد:**
>   - `HERD_NIGHT_MOVE`: تحرك القائد أو نسبة من القطيع ليلًا خارج الحظيرة.
>   - `HERD_DISPERSION`: اتساع مفاجئ لانتشار القطيع (ذئاب، كلاب، ذعر).
>   - `ANIMAL_STILL`: رأس ساكن ساعات طويلة (مريض أو نافق).
>   - `COLLAR_CUT`: نزع الطوق أو قطعه.
>   - `VEHICLE_SPEED_UNEXPLAINED`: رأس يسير بسرعة مركبة، أي حُمل في شاحنة.
> - **التخزين والإرسال اللاحق:** الطوق أو الهاتف يخزن المسار بلا تغطية ويرسله عند عودتها. القواعد تحكم عليه بوقته الحقيقي، والإنذار يقول «وصل متأخرًا بـ N دقيقة».
> - **مرحّل BLE:** هاتف الراعي (تطبيق المرافق) يلتقط الأطواق ويرفعها.
> - **التقويم الجزائري:** حساسية أعلى قبل **عيد الأضحى** بأسبوعين، وتفويض **يوم السوق** الأسبوعي.
> - **ما أُجّل إلى هنا:**
>   - `LEASH_BREAK`: انفصال الطفل عن هاتف الولي في السوق أو الشاطئ.
>   - **تسليح السيارة تلقائيًا** حين يبتعد هاتف المالك.
>   - `KEYLESS_NO_OWNER`: تشغيل المحرك وهاتف المالك بعيد.
> - **LoRaWAN:** بحث وتجربة فقط، بلا التزام بالشراء.
>
> **بوابة الخروج:** سيناريوهات خضراء في CI، ثم **تجربة مع مربٍّ واحد** (المالك يرتبها).

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:**
- **Herds:** a herd out of its barn at night, scattered by a predator, or loaded on a truck raises an incident.
- **Animals:** a still animal raises one too, as does a collar cut off.
- **No coverage:** this holds in pastures without network, through stored tracks and a shepherd's phone, and a late report says how late it is.
- **People and vehicles:** the BLE presence channel gives children `LEASH_BREAK`, and vehicles auto-arm and `KEYLESS_NO_OWNER`.

**Architecture:** Livestock is a new profile (P5 `Profiles`) plus group rules over `asset_groups` and `asset_links(kind = 'herd_leader')`, both in the schema since V1.

**Store-and-forward** reuses ingest's fix-time ordering. A fix older than `boundaryMaxAgeMs` is today ignored by the place rules. For late-tolerant profiles it is evaluated at its fix time instead, and any finding carries `lateByS`.

**BLE tags** are sources of kind `ble`. A sighting uploaded by any Hirz phone becomes a fix for the tag's asset at the relay's position. Its accuracy is the relay's own plus a range estimated from RSSI. No coordinates of the relay itself are ever exposed to the tag's circle.

**Presence** (leash, owner near the car) uses the same sightings, read as "this phone sees that tag".

**Tech Stack:** P5 stack. New: Android BLE scanning in the companion's foreground service (`BLUETOOTH_SCAN`, location already granted); open beacon formats only (iBeacon, Eddystone-UID/TLM). AirTag-style closed networks stay out (decision §1.11).

**Spec:**
- `docs/05-hirz-design.md`: §1 (links "herd leader", "accompanied by", groups), §2 (BLE tags via Hirz phones), §4.3 (`HERD_NIGHT_MOVE`, `VEHICLE_SPEED_UNEXPLAINED`, `NIGHT_EXIT` for barns), §4.4 (livestock and car profiles), §11 (P6 row), §12 (Eid al-Adha, market days, Algerian calendar), §12.2 (`HERD_DISPERSION`, `COLLAR_CUT`, `ANIMAL_STILL`, `LEASH_BREAK`, `KEYLESS_NO_OWNER`), §12.4 (shepherd template).
- `docs/04-decisions.md` §14 (no-coverage options a/b/c).
- P5 ledger.

## Global Constraints

- Every P1–P5 Global Constraint applies (DTOs in `:shared`, no coordinates in pushes or SMS, RED before GREEN in CI, pure rules with replay scenarios, profiles decide tiers, the condition never leaks).
- **Livestock profile defaults** (starting values, to be measured):
  - `HERD_NIGHT_MOVE`: P1.
  - `NIGHT_EXIT` from a `BARN` place: P1.
  - `PLACE_EXIT` from a `PASTURE`: P2.
  - `VEHICLE_SPEED_UNEXPLAINED` (≥ 25 km/h for 60 s outside an authorization or market day): P1, raised to P0 together with a night exit.
  - `HERD_DISPERSION`: P2 by day, P1 at night.
  - `COLLAR_CUT`: P1.
  - `ANIMAL_STILL` (no displacement over 20 m for 6 h, by day only, inside a pasture or barn): P2.
- **Late reports:**
  - A late-tolerant profile (livestock) evaluates every fix at its own time, up to 72 h back.
  - A finding from a fix older than 2 min carries `lateByS`. The app says "reported N min late".
  - A late finding never fires a P0 on its own (the moment has passed); it opens at most P1 with the track.
  - Silence for livestock waits 6 h (pastures without coverage), not 5 min.
- **Night** for livestock is the P5 profile night window. Sunset and sunrise from position and date (design §12) is P7.
- **Eid al-Adha:** a table of dates in `shared/.../calendar/AlgerianCalendar.kt` (2026–2030, astronomical estimates, marked "confirm against the Ministry of Religious Affairs' announcement", owner-editable per circle).
  - For the 14 days before Eid, livestock tiers rise one step (P2 → P1) and `HERD_NIGHT_MOVE` needs a smaller share of the herd.
  - Nothing else changes.
- **Market day:** a weekly window per herd (e.g. Tuesday 05:00–14:00) that authorizes movement and speed, like a vehicle authorization. Night rules still run.
- **BLE privacy:**
  - A sighting is accepted only for a tag registered to an asset, and only if the uploading phone is a paired Hirz source.
  - The relay's identity and position are never shown to the tag's circle; the fix carries the tag's estimated position only.
  - Unknown tags are dropped on the phone, never uploaded.
  - Rate limit: one sighting per tag per relay per 30 s.
- **BLE tag registration needs proof of possession** (the P3 lesson): the owner scans the tag with their own phone within 2 m (RSSI ≥ a calibrated threshold) at registration. A tag id typed by hand is refused.
- **Accuracy:**
  - A sighting fix's accuracy is the relay's accuracy plus an RSSI range (20 m strong, 60 m medium, 120 m weak).
  - Place rules keep their accuracy gates, so a weak sighting never confirms a boundary.
- **Leash and presence:**
  - `LEASH_BREAK` runs only while the guardian has started an "accompanied" outing from the app (time-boxed, ≤ 6 h). It fires when the child's tag or phone has not been seen by the guardian's phone for 60 s and the child's last fix is more than 50 m from the guardian's: P1, P0 for autism.
  - Vehicle auto-arm on owner absence: the car's tag (or tracker) is not seen by any authorized driver's phone for 2 min after ignition off. This is added to the P4 resolver as another arming reason, with the same latch.
  - `KEYLESS_NO_OWNER`: ignition on while no authorized phone has seen the car's tag in the last 2 min, only for cars with a registered tag: P1.

## Out of P6 (deliberate)

- **LoRaWAN deployment:** Task 9 is research and a bench trial with one gateway if the owner buys it. Production support only after the trial shows range and battery numbers.
- **Sunset/sunrise night, routines and learning:** P7.
- **Bus route mode, `LEFT_IN_VEHICLE`:** P9 (needs "carried in" link work).
- **Closed BLE networks (AirTag, SmartTag):** never; manual reference only.

## Review Focus

1. **A herd moved at night by its shepherd on a market day** must not alarm. Pinned by `marketDayAuthorizesMovement` (Task 3).
2. **A stored track uploaded 5 h later** that shows a 02:00 night exit opens a P1 that says how late it is, never a P0. Pinned by `lateNightExitSaysHowLateAndCapsAtP1` (Task 2).
3. **A weak BLE sighting near the barn edge** must not confirm a night exit. Pinned by `weakSightingNeverConfirmsABoundary` (Task 5).
4. **A sighting of a tag registered in another circle**, uploaded by any phone, reaches only that circle and never reveals the relay. Pinned by `sightingRevealsNothingAboutTheRelay` (Task 5).
5. **A tag id typed by hand** cannot be registered. Pinned by `tagNeedsProofOfPossession` (Task 5).
6. **`LEASH_BREAK` outside an accompanied outing** never fires. Pinned by `leashOnlyDuringAnOuting` (Task 7).

---

### Task 1: Livestock profile, herds and leaders

**Files:**
- shared `RuleId` (+ `HERD_NIGHT_MOVE`, `HERD_DISPERSION`, `COLLAR_CUT`, `ANIMAL_STILL`, `VEHICLE_SPEED_UNEXPLAINED`, `LEASH_BREAK`, `KEYLESS_NO_OWNER`) and `api/Herds.kt`.
- `Profiles` (livestock defaults, `lateTolerant`, `silenceAfterMs`).
- `server/.../herds/HerdService.kt` and `HerdRoutes.kt`:
  - `POST /v1/circles/{id}/herds {name}`
  - `POST /v1/herds/{id}/members {assetId}`
  - `PUT /v1/herds/{id}/leader {assetId}`
  - `GET /v1/herds/{id}` → `HerdDto` with members, leader, last spread and centroid.
- `V26__herds.sql` (`asset_groups.kind 'herd'`, `asset_groups.market_window jsonb`, `asset_groups.eid_date date`).

**Tests:** `herds/HerdTest.kt`, `rules/ProfilesTest` (livestock).

- [ ] **Step 1: Failing tests:** `livestockProfileDefaults`, `herdHasOneLeader`, `herdMembersSameCircle`, `viewerCannotEditHerd`.
- [ ] **Step 2:** RED. **Step 3:** Implement. **Step 4:** GREEN.
- [ ] **Step 5: Commit:** `feat(core,shared): livestock profile, herds and leaders`

---

### Task 2: Store-and-forward — late fixes judged at their own time

**Files:**
- `RulesEngine` (a late-tolerant profile evaluates fixes up to 72 h old; `Finding.lateByS`).
- `Findings`/`IncidentService` (cap a late P0 at P1; reason arg `lateByS`).
- `SilenceScanner` (profile `silenceAfterMs`).
- `ReasonText` ("reported N min late").

**Tests:** `rules/LateReportsTest.kt`.

- [ ] **Step 1: Failing tests:** `lateNightExitSaysHowLateAndCapsAtP1`, `lateFixesAreJudgedInOrder` (a buffered batch out of order), `peopleStillIgnoreStaleFixes` (child profile unchanged), `livestockSilenceWaits6h`.
- [ ] **Step 2:** RED. **Step 3:** Implement. **Step 4:** GREEN.
- [ ] **Step 5: Commit:** `feat(core): store-and-forward reports judged at their own time`

---

### Task 3: Livestock rules

**Files:**
- `server/.../rules/HerdRules.kt` (pure: `nightMove`, `dispersion`, `still`, `truckSpeed`).
- `jobs/HerdScanner.kt` (dispersion and stillness every 5 min under the herd's advisory lock).
- `RulesEngine` (barn night exit, collar cut from `AlarmKind.REMOVAL`, truck speed).
- `V27__herd_rule_state.sql`.

**Tests:** `rules/HerdRulesTest.kt`.

**Behavior:**
- **`HERD_NIGHT_MOVE`:** at night, the leader, or at least 30% of the herd's members with a fix in the last 30 min, confirmed outside the `BARN`, moving (≥ 2 good fixes 50 m apart).
- **`HERD_DISPERSION`:** the herd's radius of gyration over fixes ≤ 10 min old grows ×3 and ≥ 300 m within 15 min.
- **`ANIMAL_STILL`:** 6 h, displacement < 20 m, at least 6 good fixes in that time (so "no fixes" is silence, not stillness), daytime only.
- **`VEHICLE_SPEED_UNEXPLAINED`:** 60 s at ≥ 25 km/h on good fixes, outside a market day or authorization. Raised to P0 when the same asset has an open night exit.

- [ ] **Step 1: Failing tests:** `herdLeavesBarnAt2am`, `marketDayAuthorizesMovement`, `wolvesScatterTheHerd`, `grazingSpreadIsNotDispersion`, `sickEweStillFor6h`, `noFixesIsSilenceNotStillness`, `loadedOnATruck`, `collarCutAlarm`.
- [ ] **Step 2:** RED. **Step 3:** Implement. **Step 4:** GREEN.
- [ ] **Step 5: Commit:** `feat(core): herd night move, dispersion, stillness, truck speed and collar cut`

---

### Task 4: Algerian calendar — Eid al-Adha and market days

**Files:**
- `shared/.../calendar/AlgerianCalendar.kt` (Eid al-Adha estimates 2026–2030, the per-circle override from `asset_groups.eid_date`).
- `Profiles.of(..., at)` (the 14-day raise).
- Herd routes (`PUT /v1/herds/{id}/market {window}`, `PUT /v1/herds/{id}/eid {date}`).

**Tests:** `calendar/AlgerianCalendarTest.kt`, `rules/EidSensitivityTest.kt`.

- [ ] **Step 1: Failing tests:** `eidRaisesLivestockTiersTwoWeeksBefore`, `eidLeavesPeopleAlone`, `ownerCorrectsEidDate`, `marketWindowIsWeekly`.
- [ ] **Step 2:** RED. **Step 3:** Implement. **Step 4:** GREEN.
- [ ] **Step 5: Commit:** `feat(core,shared): Eid al-Adha sensitivity and market days`

---

### Task 5: BLE tags and the relay endpoint

**Files:**
- `server/.../ble/BleService.kt` and `BleRoutes.kt`:
  - `POST /v1/assets/{id}/tags {tagId, format, proofRssi, proofAtMs}`: owner or guardian, proof of possession.
  - `DELETE /v1/assets/{id}/tags/{tagId}`.
  - `POST /v1/relay/sightings [{tagId, rssi, seenAtMs}]` with the relay's own fix, by source token.
- `V28__ble.sql` (`sources.kind 'ble'`, `ble_tags(tag_id unique, asset_id, source_id, format)`, `ble_sightings(tag_id, relay_source_id, seen_at, rssi)` kept 7 days for presence).
- `shared/.../ble/BleFrames.kt` (iBeacon and Eddystone-UID/TLM parsing, pure).

**Tests:** `ble/BleTest.kt`, `BleFramesTest.kt`.

- [ ] **Step 1: Failing tests:**
  - `sightingBecomesAFixForTheTagsAsset`
  - `sightingRevealsNothingAboutTheRelay` (the tag's circle sees no relay id, name or exact position)
  - `unknownTagIsDropped`
  - `weakSightingNeverConfirmsABoundary`
  - `tagNeedsProofOfPossession`
  - `sightingsAreRateLimited`
  - `eddystoneAndIBeaconParse`
- [ ] **Step 2:** RED. **Step 3:** Implement. **Step 4:** GREEN.
- [ ] **Step 5: Commit:** `feat(core,shared): BLE tags and the phone relay`

---

### Task 6: Companion — shepherd relay mode

**Files:**
- `companion/RelayLogic.kt` (+ `RelayLogicTest.kt`): known-tag filter, per-tag 30 s throttle, offline queue with the existing `SignalQueue` limits, battery-saver duty cycle (10 s scan every 60 s).
- Android `BleScanner.kt` in `LocationService`.
- Manifest (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` not needed).
- Shepherd template in `CompanionRoot`: herd count seen in the last 10 min, last upload time.

- [ ] **Step 1: Failing tests:** `onlyRegisteredTagsLeaveThePhone`, `throttlePerTag`, `offlineSightingsQueueAndFlushInOrder`, `dutyCycleSavesBattery`.
- [ ] **Step 2:** RED. **Step 3:** Implement. **Step 4:** GREEN; manifest check in CI (`BLUETOOTH_SCAN` only in the companion flavors that need it; no SMS permission added).
- [ ] **Step 5: Commit:** `feat(app): shepherd relay mode scans and uploads BLE collars`

---

### Task 7: Presence — `LEASH_BREAK`

**Files:**
- `server/.../presence/Outings.kt`:
  - `POST /v1/assets/{id}/outings {hours ≤ 6}`, by the guardian whose phone scans.
  - `DELETE` to end an outing.
- `jobs/LeashScanner.kt` (every 15 s).
- `V29__outings.sql`.
- App: "We're going out" button on a child's card; the guardian's phone scans the child's tag (or reads the child's phone position) during the outing.

**Tests:** `presence/LeashTest.kt`.

- [ ] **Step 1: Failing tests:** `leashBreakAfter60sAnd50m`, `leashOnlyDuringAnOuting`, `autismLeashIsP0`, `outingEndsByItself`.
- [ ] **Step 2:** RED. **Step 3:** Implement. **Step 4:** GREEN.
- [ ] **Step 5: Commit:** `feat(core,app): accompanied outings and LEASH_BREAK`

---

### Task 8: Vehicles — owner-absence arming and `KEYLESS_NO_OWNER`

**Files:**
- `ModeResolver` (input `ownerSeenAt`; a new reason `owner_away` that latches like `auto_arm`).
- `VehicleRules` (`KEYLESS_NO_OWNER`).
- `VehicleStateTracker` (presence from `ble_sightings` by authorized drivers' phones).
- App vehicle card (register the car's tag).

**Tests:** `vehicles/PresenceArmTest.kt`, plus 2 new `VehicleScenariosTest` JSONs (`owner_walks_away_arms`, `relay_attack_start`).

- [ ] **Step 1: Failing tests:** `armsWhenOwnerPhoneLeaves2min`, `driverPhoneNearbyDoesNotArm`, `ignitionWithNoKnownPhoneIsKeyless`, `noTagNoKeylessRule`.
- [ ] **Step 2:** RED. **Step 3:** Implement. **Step 4:** GREEN.
- [ ] **Step 5: Commit:** `feat(core,app): arm when the owner walks away and flag keyless starts`

---

### Task 9: LoRaWAN — research and bench trial

**Files:** `docs/research/res-lorawan.md`.

It answers:
- Traccar's LoRaWAN support (which integrations, which payload decoders).
- ChirpStack versus The Things Stack for a farm gateway.
- Algerian band (EU868) and legal limits.
- Collar models with GNSS + LoRa and their battery life at a 15 min interval.
- Gateway cost and power (solar).
- Range in steppe and mountains from published trials.
- A recommendation with numbers.

If the owner buys a gateway and two collars, `docs/runbooks/P6-lorawan-bench.md` records a bench trial.

- [ ] **Step 1:** Research (web, cited).
- [ ] **Step 2:** Decision written into `docs/04-decisions.md` §14 as an update, not a rewrite.
- [ ] **Step 3: Commit:** `docs(research): LoRaWAN for pastures without coverage`

---

### Task 10: App, scenarios, matrix and the breeder trial (exit gate)

**Files:**
- App:
  - `guardian/HerdCard.kt` (+ `HerdLogic.kt`, `HerdLogicTest.kt`): members, leader, the spread on the map, market day and Eid date.
  - `ReasonText` (+ `herd_night_move`, `herd_dispersed`, `animal_still`, `collar_cut`, `truck_speed`, `leash_break`, `keyless_no_owner`, and "reported N min late").
  - Livestock in the asset types; place kinds `PASTURE` and `BARN` in the editor.
- Server: `server/src/test/resources/livestock/*.json` and `livestock/LivestockScenariosTest.kt`.
- The P5 confusion matrix extended with the livestock profile.
- Docs: `docs/evidence/P6-gate.md`, `docs/runbooks/P6-breeder-trial.md`.

- [ ] **Step 1:** Scenarios (positive and negative per rule, AC-19), including the late-upload ones.
- [ ] **Step 2:** Matrix: no off-profile alarms.
- [ ] **Step 3: Breeder trial (owner-dependent):** one breeder, two weeks.
  - Setup: a leader with a GSM tracker, the rest on BLE collars, the shepherd's phone as the relay.
  - Record every alert (real or false), coverage holes and late uploads, and battery life.
- [ ] **Step 4: Commit:** `test(core): P6 livestock scenarios and matrix`

---

## P6 Exit Gate
- [ ] CI green, including every P6 scenario and the extended matrix; P1–P5 stay green.
- [ ] AC-19 for every new rule.
- [ ] AC-20: livestock is a profile and group rules only. There is no change in `escalation/` or `notify/` beyond the late-report cap.
- [ ] The breeder trial is recorded in `P6-gate.md`, or marked owner-dependent and not run.
- [ ] The LoRaWAN research is written with a recommendation.
