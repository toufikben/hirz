# Hirz P7 — Routines and Learning Implementation Plan

> **ملخص بالعربية:** المرحلة P7 تجعل حِرز **يعرف يوم الأصل المعتاد**، فيلاحظ ما لم يحدث، لا ما حدث فقط:
> - **الروتين:** «من الأحد إلى الخميس، يصل إلى المدرسة قبل 08:00، والانصراف 16:30». قوالب جاهزة: المدرسة، المسجد، الدروس، المرعى، الحظيرة ليلًا.
> - **القواعد الجديدة:**
>   - `MISSED_ARRIVAL`: لم يصل قبل الموعد.
>   - `STATIONARY_AFTER_DISMISSAL`: بعد الانصراف لا إزاحة 15 دقيقة في مكان غير معروف.
>   - `UNUSUAL_STOP`: توقف طويل في مكان غير معروف أثناء تنقل معتاد.
>   - `PACING_LOOP`: مسن يدور في نفس الشارع.
> - **التقويم الجزائري:** العطل المدرسية وعطلة الجمعة والسبت، و**رمضان** (يتكيف الروتين وتخرج العائلة ليلًا بعد التراويح)، و**الليل من الغروب والشروق الفعليين** للمكان.
> - **التعلم:** يتعلم أماكن الأصل المعتادة. «مكان لم يزره من قبل» دليل P2 داعم، **ولا يرفع المستوى وحده أبدًا**.
> - **تاريخ الإنذارات الكاذبة:** إذا تكرر الإنذار الكاذب لقاعدة على أصل، يصير P1 «تحقق أولًا». **لا يمسّ P0 أبدًا.**
> - **الملخص اليومي:** أدلة P2 الصغيرة تُجمع في ملخص واحد مساءً بدل إشعارات متفرقة.
>
> **بوابة الخروج:** لا ارتفاع في الإنذار الكاذب. كل مكتبات السيناريوهات (P4–P7) خضراء. ومحاكاة شهر عادي لعشرة أصول لا تُطلق أي P1، وهذه ميزانية الإنذار الكاذب.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **Never build locally: push and read CI** (the owner's machine is weak).

**Goal:** Hirz notices what did not happen: the school arrival that never came, the child who stopped somewhere unknown after school, the elder walking in circles. It never adds a false alarm in the process. Each family's false alarms become fewer over time, not more.

**Architecture:**
- **Routines** are data (`routines`, V30): an asset, a place, the days, an arrive-by and/or a dismissal time.
  - A pure `RoutineRules` object judges them against the asset's place state and recent fixes.
  - `RoutineScanner` runs every minute, because a missed arrival is an absence and no report triggers it.
  - The day rules (school days, holidays, Ramadan) come from the server-side `AlgerianCalendar`.
- **Sun-based night:** a pure `SolarTimes` (NOAA algorithm) is offered as a night-window kind in profiles.
- **Learning:** stored as `learned_places`, built by a nightly job from dwell clusters.
  - It only adds supporting reasons and digest items. It never opens an incident (design §4.5).
- **False-alarm history:** read from `incident_reviews` (since P2) in `Profiles.withHistory`.

**Tech Stack:** P6 stack. No new infrastructure.

**Spec:** `docs/05-hirz-design.md`:
- §1 (Routine)
- §4.3 (`MISSED_ARRIVAL`, `STATIONARY_AFTER_DISMISSAL`)
- §4.5 (smart layer)
- §6.3 (routine templates)
- §11 (P7 row, gate)
- §12 (Algerian calendar, Ramadan, sun-based night)
- §12.2 (`UNUSUAL_STOP`, `PACING_LOOP`, `MULTI_ANOMALY_DIGEST`, tier modifiers and false-alarm history)
- §10 (false-alarm budget: P1 < 0.5 per 1000 asset-days, P0 ≈ 0)

The P6 ledger records the deferrals (sunrise night, M3).

## Global Constraints

- Every P1–P6 Global Constraint applies:
  - DTOs live in `:shared`.
  - No coordinates in pushes or SMS.
  - The condition never leaks.
  - RED before GREEN in CI.
  - Rules are pure, with replay scenarios.
  - Profiles decide tiers.
  - Only CI builds.
- **Learning never raises.** A learned "new place" is a supporting reason on an existing incident or a digest line. It is never a finding on its own, and never a tier change.
- **False-alarm history lowers only P1.**
  - Trigger: 3 or more false-alarm reviews by an owner or guardian for the same rule on the same asset within 30 days.
  - Effect: that rule's P1 becomes **"verify first"**. It is a P1 whose first push is silent (in-app and a quiet notification). If unconfirmed within its ack window, it escalates as a normal P1.
  - P0 is never touched.
  - The owner sees the change and can undo it.
- **Routine thresholds** (starting values, to be measured):
  - `MISSED_ARRIVAL`: not inside the place `arrivalGraceMin` (10) after arrive-by. Only when the asset was outside it at the start of the arrival window, it has a fresh source (heartbeat within 15 min), and it is not covered by an authorization, handover or day off.
  - `STATIONARY_AFTER_DISMISSAL`: from dismissal + 5 min, 15 min of good fixes within 40 m outside every known place.
  - `UNUSUAL_STOP` (P2): during a routine trip (left place A, B expected), more than 10 min within 40 m outside known and learned places.
  - `PACING_LOOP` (P2, elders): in 30 min, at least 1 km of good fixes within a 250 m radius, outside home, revisiting at least 3 times the same 50 m cell.
- **Days:**
  - School routines skip Friday and Saturday by default.
  - They also skip the Algerian school holidays, from a table of 2026–2028 dates marked "confirm against the Ministry of National Education calendar", which the owner can edit.
  - National holidays are skipped too.
- **Ramadan** (estimated dates 2027–2030, owner-editable):
  - Routine times shift by the owner's offset; school usually starts later.
  - People's `NIGHT_EXIT` gets a default exception from iftar to the end of tarawih (about 21:30–23:30), unless the owner turns it off.
- **Sun-based night:** a profile's night window can be "sunset + X → sunrise − Y" for its home or barn location. This is the default for livestock (X = 0, Y = 0); people keep fixed hours unless the owner chooses it.
- **Digest:** P2 findings outside incidents are collected, together with learned-place supporting reasons, into one in-app summary per circle per day at 20:00 local. Its push is quiet and carries no coordinates.

## Out of P7 (deliberate)

- **Route corridors for buses and children** (`ROUTE_DEVIATION`): P9 with buses.
- **"Carried in" path matching** (a child's speed explained by an authorized car on the same path): P9.
- **Weather and temperature modifiers:** they need a weather source; deferred until a provider is chosen.
- **Darija voice:** still waiting for recordings.

## Review Focus

1. **A school holiday** must not produce a `MISSED_ARRIVAL` for every child in the country. Pinned by `holidayNoMissedArrival` (Task 2).
2. **A child whose phone died at home overnight** must not raise `MISSED_ARRIVAL` P1 at 08:10. That case belongs to the silence rule, which says so. Pinned by `deadPhoneIsSilenceNotMissedArrival` (Task 2).
3. **Learning cannot raise a tier, ever,** even with ten "new place" reasons. Pinned by `learningNeverRaises` (Task 6).
4. **False-alarm history never touches P0,** and needs 3 different incidents reviewed as false within 30 days. Marking the same incident again does not count twice. Pinned by `historyLowersP1NotP0` (Task 7).
5. **The false-alarm budget:** a simulated month of normal school days for 10 assets raises zero P1 and zero P0. Pinned by `normalMonthWithinBudget` (Task 10).

---

### Task 1: Routines — model, templates, API

**Files:**
- shared `api/Routines.kt` (`RoutineDto`, `RoutineRequest`, `RoutineTemplate`)
- `server/.../routines/RoutineService.kt` and `RoutineRoutes.kt`:
  - `GET/POST /v1/assets/{id}/routines`
  - `PUT/DELETE /v1/routines/{rid}`
  - templates: school 07:30–16:30 Sun–Thu, mosque, lessons, pasture day, barn at night
- `V30__routines.sql`:
  - `routines(id, asset_id, place_id, days int[], arrive_by_min, dismissal_min, ramadan_offset_min, active, created_by)`
  - `calendar_overrides(circle_id, kind, date)`

**Tests:** `routines/RoutineTest.kt`.

- [ ] **Step 1: Failing tests:** `templateCreatesSchoolRoutine`, `routinePlaceMustBeSameCircle`, `viewerCannotEditRoutines`, `routineChangesAreAudited`.
- [ ] **Step 2:** RED. **Step 3:** Implement. **Step 4:** GREEN.
- [ ] **Step 5: Commit:** `feat(core,shared): routines with templates`

---

### Task 2: `MISSED_ARRIVAL` and the Algerian school calendar

**Files:**
- `server/.../calendar/AlgerianCalendar.kt` (+ school holidays 2026–2028, national holidays, `isSchoolDay(date, overrides)`)
- `rules/RoutineRules.kt` (pure: `missedArrival`)
- `jobs/RoutineScanner.kt` (every minute, once per routine per day)

**Tests:** `routines/MissedArrivalTest.kt`, `calendar/SchoolCalendarTest.kt`.

- [ ] **Step 1: Failing tests:**
  - `notAtSchoolBy0810RaisesP1`
  - `arrivedAt0755IsFine`
  - `fridayNoRoutine`
  - `holidayNoMissedArrival`
  - `deadPhoneIsSilenceNotMissedArrival`
  - `handoverDayOff` (an active handover muting place exits also mutes the routine)
  - `onceADay`
- [ ] **Step 2:** RED. **Step 3:** Implement. **Step 4:** GREEN.
- [ ] **Step 5: Commit:** `feat(core): missed arrival on the Algerian school calendar`

---

### Task 3: `STATIONARY_AFTER_DISMISSAL` and `UNUSUAL_STOP`

**Files:** `RoutineRules.kt` (`stationaryAfterDismissal`, `unusualStop`), `RoutineScanner`.

**Tests:** `routines/AfterSchoolTest.kt`.

- [ ] **Step 1: Failing tests:**
  - `stoppedAtAnUnknownCornerAfterSchool`
  - `waitingAtTheSchoolGateIsFine` (inside the place)
  - `atGrandmasIsFine` (a known place)
  - `slowWalkHomeIsNotStationary`
  - `unusualStopOnTheWayIsP2`
  - `coarseFixesSayNothing`
- [ ] **Step 2:** RED. **Step 3:** Implement. **Step 4:** GREEN.
- [ ] **Step 5: Commit:** `feat(core): stationary after dismissal and unusual stops`

---

### Task 4: `PACING_LOOP` (elders)

**Files:** `rules/PersonRules.kt` (`pacing`), `HerdScanner`-style slow scan in `RoutineScanner`.

**Tests:** `rules/PacingTest.kt`.

- [ ] **Step 1: Failing tests:** `circlingTheSameBlockRaisesP2`, `aLongStraightWalkIsNotPacing`, `walkingInTheGardenAtHomeIsNot`.
- [ ] **Step 2:** RED. **Step 3:** Implement. **Step 4:** GREEN.
- [ ] **Step 5: Commit:** `feat(core): pacing loops for elders`

---

### Task 5: Sun-based night and Ramadan

**Files:**
- shared `rules/SolarTimes.kt` (pure NOAA sunrise and sunset)
- `Profiles` (night kind `sun`, offsets; livestock default)
- `AlgerianCalendar` (Ramadan estimates, owner override)
- `Profiles.seasonal` (Ramadan night exception for people)
- `ProfileUpdate` (+ `nightBySun`, `sunOffsets`)

**Tests:** `SolarTimesTest` (Algiers, Tamanrasset, solstices, within 2 min of published tables), `rules/RamadanTest.kt`.

- [ ] **Step 1: Failing tests:**
  - `sunriseAlgiersJune`
  - `livestockNightFollowsTheSun`
  - `tarawihIsNotANightExitInRamadan`
  - `ramadanShiftsTheSchoolRoutine`
  - `ownerCanTurnTheRamadanExceptionOff`
- [ ] **Step 2:** RED. **Step 3:** Implement. **Step 4:** GREEN.
- [ ] **Step 5: Commit:** `feat(core,shared): night by the sun and Ramadan`

---

### Task 6: Learned places

**Files:**
- `jobs/LearningJob.kt`, nightly: dwell clusters of 20 min or more on 3 or more distinct days within 60 days, 50 m radius.
- `V31__learned_places.sql`.
- `RulesEngine` and `IncidentService.reasonAdded`: a "new place never visited" reason is appended to an open incident whose last fix lies outside every known and learned place.
- Digest feed.

**Tests:** `learning/LearningTest.kt`.

- [ ] **Step 1: Failing tests:** `learnsGrandmasHouse`, `oneVisitIsNotLearned`, `newPlaceIsASupportingReason`, `learningNeverRaises`, `learnedPlacesExpireAfter60Days`.
- [ ] **Step 2:** RED. **Step 3:** Implement. **Step 4:** GREEN.
- [ ] **Step 5: Commit:** `feat(core): learned places as supporting evidence only`

---

### Task 7: False-alarm history — "verify first"

**Files:**
- `Profiles.withHistory(profile, assetId, rule)`, which reads `incident_reviews`.
- `IncidentService.open`: a P1 with history opens `verify_first = true`. Its first push is quiet; it escalates normally after its ack window.
- `V32__verify_first.sql` (`incidents.verify_first`).
- Profile screen (shows it, with an undo).

**Tests:** `incidents/VerifyFirstTest.kt`.

- [ ] **Step 1: Failing tests:**
  - `threeFalseAlarmsMakeP1VerifyFirst`
  - `historyLowersP1NotP0`
  - `verifyFirstStillEscalatesWhenIgnored`
  - `ownerCanUndo`
  - `oldReviewsExpire` (30 days)
- [ ] **Step 2:** RED. **Step 3:** Implement. **Step 4:** GREEN.
- [ ] **Step 5: Commit:** `feat(core): verify-first after repeated false alarms`

---

### Task 8: `MULTI_ANOMALY_DIGEST`

**Files:**
- `jobs/DigestJob.kt` (20:00 local, per circle).
- `V33__digests.sql`.
- `GET /v1/circles/{id}/digests`.
- P2 findings that never opened an incident are routed to the digest: today they are stored silently.
- A quiet push of kind `digest`, carrying no coordinates and no rule names that reveal a condition.

**Tests:** `digest/DigestTest.kt`.

- [ ] **Step 1: Failing tests:** `p2sGatherIntoOneEveningDigest`, `noDigestWhenNothingHappened`, `digestPushIsQuietAndCarriesNoPlace`, `digestRespectsCondition` (no condition-revealing rule).
- [ ] **Step 2:** RED. **Step 3:** Implement. **Step 4:** GREEN.
- [ ] **Step 5: Commit:** `feat(core): evening digest of small signs`

---

### Task 9: App — routines editor, digest, verify-first, night by sun

**Files:**
- `guardian/RoutineCard.kt` and `RoutineLogic.kt` (+ `RoutineLogicTest.kt`): templates, days chips, times, Ramadan offset.
- `guardian/DigestScreen.kt`.
- The verify-first label on incident cards.
- The night-by-sun toggle in `ProfileCard`.
- `ReasonText`: `missed_arrival`, `stationary_after_dismissal`, `unusual_stop`, `pacing_loop`, `new_place`, `verify_first`, in ar/fr/en.

- [ ] **Step 1: Failing tests:** `templatesFillTheForm`, `schoolDaysDefaultSunToThu`, `everyNewReasonReadsInThreeLanguages`, `verifyFirstLabelIsPlain`.
- [ ] **Step 2:** RED. **Step 3:** Implement. **Step 4:** GREEN; both flavors build; the forbidden-claims guard passes.
- [ ] **Step 5: Commit:** `feat(app): routines, evening digest, verify-first and night by the sun`

---

### Task 10: Scenarios and the false-alarm budget (exit gate)

**Files:**
- `server/src/test/resources/routines/*.json`: a positive and a negative per new rule (AC-19).
- `routines/RoutineScenariosTest.kt`.
- `budget/NormalMonthTest.kt`: a generated 30 days for 10 assets, covering school days, weekends, a holiday, Ramadan evenings, a grandma visit, GPS drift and a dead phone overnight. It asserts zero P1 and zero P0 incidents, and P2 only as digest lines.
- `docs/evidence/P7-gate.md`.

- [ ] **Step 1:** Scenarios. RED where the rules are missing; they are added in Tasks 2–8, so this task runs last.
- [ ] **Step 2:** The normal month passes the budget; every P4–P6 scenario library stays green.
- [ ] **Step 3: Commit:** `test(core): P7 scenarios and the false-alarm budget`

---

## P7 Exit Gate
- [ ] CI green, including every scenario library (P4–P7) and the normal-month budget test.
- [ ] AC-19 for every new rule.
- [ ] Learning never raises (`learningNeverRaises`), and history never touches P0 (`historyLowersP1NotP0`).
- [ ] Whole-branch review fixed, then the PR.
