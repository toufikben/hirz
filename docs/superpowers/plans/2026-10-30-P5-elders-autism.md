# Hirz P5 — Elders and Autism Implementation Plan

> **ملخص بالعربية:** المرحلة P5 تحمي **المسن (خاصة الزهايمر)** و**الطفل أو البالغ من ذوي التوحد**، وتكمل قواعد **المراهق**:
> - **ملفات السلوك:** كل نوع أصل (وحالة مثل الزهايمر أو التوحد) يحدد القواعد والعتبات والمستويات، قابلة للتعديل لكل أصل، بلا تغيير في الحوادث ولا الإشعارات (AC-20).
> - **القواعد الجديدة:**
>   - `NIGHT_EXIT`: خروج المسن من البيت ليلًا.
>   - `HAZARD_PROXIMITY`: دخول منطقة خطر موسومة (ماء، طريق سريع، سكة)، P0 للتوحد.
>   - `RUN_AWAY_SPEED`: ركض مفاجئ مبتعدًا عن البيت، P0 للتوحد.
>   - `PAST_ADDRESS_HEADING`: توجه المسن نحو عنوان قديم.
>   - `CURFEW` و`COASTAL_NIGHT` للمراهق (الثانية تُضبط يدويًا ولا تُسوَّق).
> - **بلوغ الطفل:** تنبيه مراجعة عند 15 سنة، وعند 18 سنة يتوقف التتبع ما لم يوافق هو.
> - **الوضع المبسط للمسن** في تطبيق المرافق: زر استغاثة واحد كبير جدًا، ورسالة صوتية.
>
> **بوابة الخروج:** سيناريوهات إعادة تشغيل خضراء في CI: لكل قاعدة جديدة سيناريو إيجابي وسلبي على الأقل (AC-19)، ومصفوفة خلط لكل ملف سلوك. ثم تجربة ميدانية مع أسرة واحدة إن أمكن.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An elder who leaves home at night or heads toward an old address, and an autistic person who bolts or reaches water, a road or a railway, raise an incident fast enough to matter. The same events for a profile where they are normal raise nothing. Adding these profiles changes no incident, escalation or notification code.

**Architecture:** A pure `Profiles` object turns an asset's type, condition and per-asset overrides into a `Profile`: which rules run, their tiers, and their thresholds. `RulesEngine` asks the profile instead of hard-coding `Tier.P1` and `RuleConfig` values. The new rules are pure functions with persisted per-asset state (`person_rule_state`) feeding the existing `FindingRecorder`, as the vehicle rules do. The age transition is a daily job. The simplified companion mode is a layout of the existing companion with its existing SOS and check-in paths.

**Tech Stack:** P4 stack. No new infrastructure.

**Spec:** `docs/05-hirz-design.md` §4.3 (`NIGHT_EXIT`, `HAZARD_PROXIMITY`), §4.4 (elder and autism profiles), §10 (AC-19, AC-20), §11 (P5 row), §12.2 (`CURFEW`, `COASTAL_NIGHT`, `PAST_ADDRESS_HEADING`, `RUN_AWAY_SPEED`, tier modifiers), §12.4 (elder companion template, voice), §12.5.6 (age transition). P4 ledger: `.superpowers/sdd/2026-10-30-P4-vehicles/progress.md`.

## Global Constraints

- Every P1–P4 Global Constraint applies (DTOs in `:shared`, no coordinates in pushes or SMS, duress silence, RED before GREEN in CI, pure rules with replay scenarios, vehicles keep their own rules).
- **Conditions:** `assets.condition` is one of `none`, `dementia`, `autism`, set by the owner and editable by the owner only. It changes only rule selection and tiers. It is never shown in a push, an SMS or the public share page. The police sheet shows it only when the owner ticks "include in the police sheet" (it helps searchers: an autistic child may not answer their name and may hide or head for water).
- **Profile defaults** (starting values, to be measured):
  - **child:** today's rules unchanged. `HAZARD_PROXIMITY` P1.
  - **teen:** today's rules, plus `CURFEW` (P2, owner may raise to P1) and `COASTAL_NIGHT` (P1, only when the owner draws a coast place).
  - **elder:** `NIGHT_EXIT` P1 with 2 readings instead of 3 to confirm; `HAZARD_PROXIMITY` P1.
  - **elder + dementia:** as elder, plus `PAST_ADDRESS_HEADING` P1.
  - **autism (any person type):** `HAZARD_PROXIMITY` P0 with 1 reading inside to confirm, plus `RUN_AWAY_SPEED` P0. The P0 ack window is unchanged (60 s).
  - **Overrides** per asset may raise a tier, tighten a threshold or switch a rule off, except that **P0 rules can be lowered by the owner only, never switched off by anyone**. Every override change is audited.
- **Night** is a profile window in Africa/Algiers time, 22:00–06:00 by default, editable per asset.
- **Thresholds** (in `RuleConfig`): `nightExitReadings` 2; `hazardEntryReadingsAutism` 1; `runAwayKmh` 9 (a run, above a fast walk), `runAwayFixes` 2, `runAwayAccuracyMaxM` 25, `runAwayMinStepM` 15 (each fix farther from home than the last); `pastAddressHeadingFixes` 4 (consecutive fixes getting closer, total ≥ 300 m closer, outside home); `curfewGraceMin` 15.
- **Accuracy gates:** no new rule fires on a fix worse than its accuracy gate, and a coarse fix neither counts toward nor resets a streak (the P4 lesson).
- **Age transition:** `assets.birth_date` (optional). At 15 the owner gets a "review what your child sees about tracking" notice, once. At 18 every source on the asset is paused until the person gives adult consent on their own phone (the P1 adult consent flow). Missing birth date = no transition.
- **No tracking of the guardian:** `RUN_AWAY_SPEED` measures from home, not from a guardian's phone. Using the guardian's position needs the P2 "accompanied" link and is out of P5.

## Out of P5 (deliberate)

- **`LEASH_BREAK` with BLE** and running away from the guardian's phone: need a BLE presence channel in the companion; P6 with the BLE relay work.
- **`PACING_LOOP`**, **`UNUSUAL_STOP`**, learned routines: P7.
- **Voice messages in Algerian Darija:** need recordings; Arabic and French voice now.
- **Medical alerts (falls, heart rate):** not a tracker feature; needs certified hardware.

## Review Focus

1. **An elder going to the mosque for Fajr** (before 06:00) must not raise `NIGHT_EXIT` when the owner has added the mosque window as an exception. Pinned by `fajrToTheMosqueIsNotANightExit` (Task 3).
2. **An autistic child at the edge of a pool place with ±40 m accuracy** must not raise P0 from one coarse fix. Pinned by `coarseFixAtTheWaterIsNotEntry` (Task 2).
3. **A child cycling home** must not raise `RUN_AWAY_SPEED` (the rule runs only for autism, and only moving away from home). Pinned by `cyclingTowardHomeIsNotRunningAway` (Task 4).
4. **Lowering a P0 rule** by a guardian, or switching one off by anyone, must be refused. Pinned by `p0RulesCannotBeSwitchedOff` (Task 1).
5. **The condition never leaks:** no push, SMS or share page contains "autism" or "dementia" in any language. Pinned by `conditionNeverInPushSmsOrShare` (Task 1).

---

### Task 1: Profiles, conditions and overrides

**Files:** `shared/.../asset/Condition.kt` (`NONE`, `DEMENTIA`, `AUTISM`), `shared/.../api/Profiles.kt` (`ProfileDto`, `RuleSetting(rule, enabled, tier)`, `ProfileOverrideRequest`), `server/.../rules/Profiles.kt` (pure), `server/.../assets/ProfileRoutes.kt`, `V22__profiles.sql` (`assets.condition text NOT NULL DEFAULT 'none'`, `assets.profile_overrides jsonb NOT NULL DEFAULT '{}'`, `assets.night_window jsonb`, `assets.birth_date date`, `assets.condition_on_sheet boolean NOT NULL DEFAULT false`), `RulesEngine` (tier and readings from the profile), shared `RuleId` (+ `NIGHT_EXIT`, `HAZARD_PROXIMITY`, `RUN_AWAY_SPEED`, `PAST_ADDRESS_HEADING`, `CURFEW`, `COASTAL_NIGHT`), `PlaceKind` (+ `PAST_ADDRESS`, `COAST`). Tests: `rules/ProfilesTest.kt`, `assets/ProfileRoutesTest.kt`.

**Interfaces — Produces:**
- `Profiles.of(type, condition, overrides, cfg): Profile(rules: Map<RuleId, RuleSetting>, nightWindow, readings)`.
- `GET /v1/assets/{id}/profile` → `ProfileDto` (any member). `PUT /v1/assets/{id}/profile {condition?, overrides?, nightWindow?, birthDate?, conditionOnSheet?}`: owner only for condition, birth date and P0 changes; guardian may raise tiers or tighten thresholds.

- [ ] **Step 1: Failing tests:** `childProfileIsTodaysBehavior` (every P1 scenario still passes unchanged), `autismMakesHazardP0`, `elderGetsNightExit`, `p0RulesCannotBeSwitchedOff`, `guardianMayRaiseNotLower`, `overrideChangesAreAudited`, `conditionNeverInPushSmsOrShare`.
- [ ] **Step 2:** RED. **Step 3:** Implement. **Step 4:** GREEN.
- [ ] **Step 5: Commit:** `feat(core,shared): behavior profiles with conditions and per-asset overrides`

---

### Task 2: Hazard entry split from forbidden entry

**Files:** `RulesEngine.boundaries` (a `HAZARD` place raises `HAZARD_PROXIMITY` at the profile's tier; `FORBIDDEN` keeps `FORBIDDEN_ENTRY`), `PlaceBoundary` (entry readings from the profile: 1 for autism, accuracy gate unchanged). Tests: `rules/HazardTest.kt`.

- [ ] **Step 1: Failing tests:** `hazardEntryIsP0ForAutismAfterOneGoodFix`, `coarseFixAtTheWaterIsNotEntry`, `hazardForAChildIsP1AfterTwoReadings`, `forbiddenEntryUnchanged`.
- [ ] **Step 2:** RED. **Step 3:** Implement. **Step 4:** GREEN.
- [ ] **Step 5: Commit:** `feat(core): hazard proximity as its own rule, P0 for autism`

---

### Task 3: `NIGHT_EXIT`

**Files:** `server/.../rules/PersonRules.kt` (pure: `nightExit(...)`), `V23__person_rule_state.sql` (`person_rule_state(asset_id, rule, state jsonb, updated_at)`), `RulesEngine` (people branch). Tests: `rules/NightExitTest.kt`.

**Behavior:** a confirmed exit from a `HOME` place (2 good readings outside, as `PLACE_EXIT` counts them) while the night window is active raises `NIGHT_EXIT` (P1) instead of `PLACE_EXIT`. An exception window (e.g. Fajr prayer 04:30–06:30 to the mosque) suppresses it while the asset is heading to or inside a place the owner named in the exception.

- [ ] **Step 1: Failing tests:** `leavingHomeAt2amRaisesNightExit`, `leavingHomeAt2pmIsPlaceExitAsBefore`, `fajrToTheMosqueIsNotANightExit`, `driftAtTheDoorIsNotAnExit` (accuracy gate).
- [ ] **Step 2:** RED. **Step 3:** Implement. **Step 4:** GREEN.
- [ ] **Step 5: Commit:** `feat(core): night exit for elders`

---

### Task 4: `RUN_AWAY_SPEED` (autism)

**Files:** `PersonRules.runAway(...)`, `RulesEngine`. Tests: `rules/RunAwayTest.kt`.

**Behavior:** 2 consecutive good fixes (accuracy ≤ 25 m) at ≥ 9 km/h, each at least 15 m farther from home than the last, and the asset outside home → `RUN_AWAY_SPEED` P0.

- [ ] **Step 1: Failing tests:** `boltingFromHomeIsP0`, `cyclingTowardHomeIsNotRunningAway`, `oneFastFixIsNotEnough`, `coarseFixesNeitherCountNorReset`, `ruleOffForNonAutism`.
- [ ] **Step 2:** RED. **Step 3:** Implement. **Step 4:** GREEN.
- [ ] **Step 5: Commit:** `feat(core): running away at speed for autism`

---

### Task 5: `PAST_ADDRESS_HEADING` (dementia)

**Files:** `PersonRules.pastAddress(...)`, `RulesEngine`. Tests: `rules/PastAddressTest.kt`.

**Behavior:** for a `dementia` asset outside home, 4 consecutive good fixes each closer to the same `PAST_ADDRESS` place, together ≥ 300 m closer → `PAST_ADDRESS_HEADING` P1 with the place's name in the reason. Reaching the place raises nothing more (the incident already exists); leaving home already raised `PLACE_EXIT` or `NIGHT_EXIT`.

- [ ] **Step 1: Failing tests:** `walkingTowardChildhoodHomeRaises`, `passingByOnTheWayElsewhereDoesNot` (closer then farther), `noPastAddressNoRule`.
- [ ] **Step 2:** RED. **Step 3:** Implement. **Step 4:** GREEN.
- [ ] **Step 5: Commit:** `feat(core): heading toward a past address for dementia`

---

### Task 6: Teen — `CURFEW` and `COASTAL_NIGHT`

**Files:** `PersonRules.curfew(...)`, `PersonRules.coastalNight(...)`, `jobs/CurfewScanner.kt` (a teen still outside home 15 min after the curfew time, checked every minute, once per night), `RulesEngine` (coast entry at night). Tests: `rules/TeenRulesTest.kt`.

**Behavior:** `CURFEW` P2 (owner may set P1) when the teen is outside every `HOME`/`RELATIVE` place 15 min after the curfew time and there is no authorization. `COASTAL_NIGHT` P1 on confirmed entry into a `COAST` place inside the night window. The coast place kind is offered in the place editor only under "more" with a neutral label; nothing in the app or store text mentions migration.

- [ ] **Step 1: Failing tests:** `outsideAfterCurfewRaisesOncePerNight`, `atAuntsAfterCurfewIsFine`, `coastAtNightRaises`, `coastByDayDoesNot`.
- [ ] **Step 2:** RED. **Step 3:** Implement. **Step 4:** GREEN.
- [ ] **Step 5: Commit:** `feat(core): curfew and coastal-night rules for teens`

---

### Task 7: Age transition

**Files:** `jobs/AgeTransitionJob.kt` (daily), `V24__age_transition.sql` (`assets.age_notice_15_at`, `sources.paused_reason`), ingest (a source paused for `age_18` answers 403 `consent_required` and the companion shows the adult consent screen). Tests: `assets/AgeTransitionTest.kt`.

- [ ] **Step 1: Failing tests:** `at15TheOwnerIsToldOnce`, `at18SourcesPauseUntilAdultConsent`, `consentResumesTracking`, `noBirthDateNoTransition`.
- [ ] **Step 2:** RED. **Step 3:** Implement. **Step 4:** GREEN.
- [ ] **Step 5: Commit:** `feat(core): age transition at 15 and 18`

---

### Task 8: App — profile editor, new place kinds, elder simple mode

**Files:** `guardian/ProfileCard.kt`, `guardian/ProfileLogic.kt` (+ `ProfileLogicTest.kt`), place editor (hazard, past address, coast under "more"), `ReasonText` (+ `night_exit`, `hazard_entered`, `running_away`, `heading_past_address`, `curfew`, `coastal_night` in ar/fr/en), `companion/SimpleMode.kt` (+ `CompanionLogicTest` cases): one SOS button filling most of the screen, a check-in "I'm fine" button, Arabic/French voice prompt on SOS and on a check-in request, text ≥ 24 sp, touch ≥ 72 dp.

- [ ] **Step 1: Failing tests:** `p0RowsShowNoSwitchOff`, `conditionLabelOnlyInProfileScreen`, `everyNewReasonReadsInThreeLanguages`, `simpleModeHasOnlySosAndFine`.
- [ ] **Step 2:** RED. **Step 3:** Implement. **Step 4:** GREEN; both flavors build; forbidden-claims guard passes (no "prevents wandering", no medical claims).
- [ ] **Step 5: Commit:** `feat(app): profile editor, hazard and past-address places, elder simple mode`

---

### Task 9: Scenario library and confusion matrix (exit gate)

**Files:** `server/src/test/resources/people/*.json` (at least one positive and one negative per new rule, AC-19), `people/PeopleScenariosTest.kt` (same runner as `VehicleScenariosTest`), `people/ConfusionMatrixTest.kt` (runs every scenario under every profile; prints and asserts a per-profile table of expected vs. raised rules), `docs/evidence/P5-gate.md`, `docs/runbooks/P5-family-trial.md`.

Scenarios: elder 2 am exit; elder Fajr to the mosque; elder drift at the door; autistic child bolting; child cycling home; autistic child at the pool edge with coarse fixes; dementia walk toward the childhood home; passing by a past address; teen out after curfew; teen at an aunt's after curfew; coast at night; coast by day.

- [ ] **Step 1:** Add the scenarios; RED where rules are missing (they are added in Tasks 2–6, so this task runs last and should be GREEN; any failure is a defect to fix).
- [ ] **Step 2:** Confusion matrix: every off-diagonal cell is either zero or explained in `P5-gate.md`.
- [ ] **Step 3: Family trial (owner-dependent):** one family with an elder or an autistic child, 2 weeks, companion simple mode or a watch tracker; record every alert and whether it was real.
- [ ] **Step 4: Commit:** `test(core): P5 scenario library and confusion matrix`

---

## P5 Exit Gate
- [ ] CI green, including every P5 scenario and the confusion matrix; P1–P4 stay green.
- [ ] AC-19 for every new rule (positive and negative scenario).
- [ ] AC-20: no change in `incidents/`, `escalation/` or `notify/` beyond reading the profile tier.
- [ ] Family trial recorded in `P5-gate.md` (or marked owner-dependent, not run).
