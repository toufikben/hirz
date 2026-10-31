# Hirz P9 — Community, Schools and Buses Implementation Plan

> **ملخص بالعربية:** P9 تفتح حِرز للمؤسسات وللمجتمع، دون أن يفقد الولي قراره على طفله:
> - **المؤسسات** (مدرسة، شركة نقل، دار مسنين): دائرة من نوع «مؤسسة» بموظفين وأدوار. **الولي يشارك طفله مع المدرسة بموافقة**، محدودة بساعات الدراسة، قابلة للسحب، وتنتهي بنهاية السنة الدراسية. المدرسة ترى الطفل في تلك الساعات فقط، ولا تغيّر ملفه أبدًا.
> - **الحافلات:** نفس جهاز السيارة بجدول أوضاع: «مسار» في ساعات الخدمة، و«متوقفة/مسلّحة» ليلًا. قواعد جديدة:
>   - `ROUTE_DEVIATION`: الخروج عن المسار.
>   - تأخر الوصول للمحطات.
>   - التوقف غير المجدول.
>   - السرعة الزائدة في الخدمة.
>   - **`UNBOARDED`/`NOT_DROPPED`:** طفل لم يصعد في محطته، أو لم ينزل حيث يجب.
> - **«محمول في»:** طفل يتحرك مع الحافلة على نفس المسار، فسرعته مُفسَّرة ولا إنذار.
> - **السائق:** في تطبيقه «بدء/إنهاء النوبة» و«صعد/نزل» لكل طفل.
> - **سلسلة التصعيد للمؤسسات:** الحافلة ← المشرف ← المدرسة ← أولياء الأطفال الذين على متنها.
> - **لوحة ويب للمؤسسة:** تُفتح على الحاسوب **برمز من التطبيق** (مثل واتساب ويب)، بلا كلمة سر. فيها: الحافلات الآن، الأطفال على كل حافلة، الحوادث مع «أتولى»، والتقارير.
> - **الشبكة المجتمعية بضوابط** (قرار §23):
>   - المشاركة اختيارية.
>   - **لا يُرسل شيء إلا بضغطة الولي** على حادث P0.
>   - صورة الطفل فقط إذا أرفقها الولي في تلك اللحظة.
>   - منطقة تنبيه محدودة (حتى 3 كم).
>   - المتطوع يرى منطقة تقريبية لا المسار.
>   - المتطوعون **موثَّقو الهوية**.
>   - زر «بلاغ كاذب».
>   - التنبيه ينتهي وحده.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **Never build locally: push and read CI** (the owner's machine is weak).

**Goal:**
- A school and its bus company can watch the children in their care during the hours they are responsible, and only then. A missed boarding or a missed drop-off reaches the right person in the right order.
- A family facing a P0 can, by its own tap, ask verified neighbours nearby to look out, without exposing the child's track to strangers.

**Architecture:**
- **Institutions are circles** (`circles.kind = 'family' | 'institution'`, V40) with the existing roles: owner = admin, guardian = staff/supervisor, viewer.
  - A family shares a child with an institution through the existing `asset_consents` (P2), extended with `scope = 'institution'`, active windows and an expiry (V40).
  - `SHARED_ASSETS_SQL` already lets the other circle see shared assets. P9 adds a time check: the institution sees the child only inside the consent's windows, and never edits the profile, routines, places or sources.
- **Buses** are `AssetType.BUS` with a mode schedule (`asset_modes`, V41): `route` in service hours, `armed` otherwise, `maintenance` by hand. Switching mode during an open incident needs the two-guardian quorum. Every switch is audited.
  - A route is a `PlaceKind.ROUTE` corridor (a polyline plus width) with ordered stops (`route_stops`, V41). A run is one route on one day (`bus_runs`).
- **Bus rules** (`BusRules` pure, `BusScanner` every minute, under the per-asset lock), in route mode only:
  - `ROUTE_DEVIATION` P1: outside the corridor for at least 60 s, with good fixes.
  - `STOP_LATE` P2: more than 10 min behind at a stop.
  - `UNSCHEDULED_STOP` P2: still for at least 5 min off every stop.
  - `OVERSPEED_IN_SERVICE` P2: at least 90 km/h for 60 s.
  - In armed mode the P4 vehicle rules apply unchanged.
- **Carried in** (`rides`, V42): a child assigned to a bus with a boarding stop and a drop stop. The child counts as aboard when any of these holds:
  - the driver tapped "boarded";
  - the bus's BLE relay hears the child's tag (P6 relay);
  - the child's fixes co-move with the bus: within 50 m, and with matching speed for 3 readings.
  - While aboard, the child's vehicle-speed findings are explained and suppressed. A P0 is never suppressed.
- **`UNBOARDED`** (P1): the bus left the child's boarding stop on a run day and the child is not aboard within 5 min.
- **`NOT_DROPPED`** (P1): the run ended, or the bus left the drop stop, and the child is still aboard. If the child is still aboard 10 min after the engine stops, it is P0 (`LEFT_IN_VEHICLE`).
- **B2B escalation:** an incident on a bus, or on a child while aboard, climbs a ladder in this order:
  1. the bus's supervisor (a staff member assigned to the run);
  2. the institution's guardians;
  3. the owner;
  4. the parents of every child aboard (for a bus incident) or of that child.
  - Parents are told at once for P0; for P1, after the second step goes unanswered.
- **Web dashboard** (`/web`, server-rendered HTML with no build step, plus Leaflet from a pinned CDN with SRI):
  - Staff open it with a 6-digit code from the app (`web_sessions`, V43): the code lasts 2 min, the session 12 h, and it is revocable from the app.
  - The session cookie is HttpOnly, Secure and SameSite=Strict, the page carries a CSP, and every POST requires a CSRF token.
  - Views: buses now, roster per bus, incidents with claim/"not convinced", and a run report as CSV.
  - The dashboard is read-mostly. It can claim and acknowledge, but it never edits places, rules or members.
- **Community network** (`volunteers`, `community_alerts`, `community_replies`, V44):
  - **Volunteers** opt in from the app and are verified by an operator (identity checked off-app, the same `HIRZ_OPERATORS` list). Each sets a home area and a radius of up to 3 km.
  - **Alert:** on an open P0 incident, an owner or guardian taps "Ask the community". They choose the radius (≤ 3 km) and optionally attach one photo taken or chosen now. The text is prefilled from the case sheet and editable.
  - **Who receives it:** verified volunteers whose area intersects the circle around the last fix, rounded to a 500 m grid. Volunteers see that rounded area, the description and the photo; never the track, the live position, the family's names or phones.
  - **Replies:** "Seen here" sends the volunteer's own location to the family only. "False report" counts against the alert.
  - **Limits:**
    - the alert expires after 6 h, or when the incident closes;
    - at most 2 alerts a day per circle;
    - a volunteer reported 3 times is suspended pending an operator's review;
    - every send is audited.

**Tech stack:** as P8. The only new dependency is Leaflet, from a pinned CDN with an SRI hash and no npm build.

---

### Task 1: Institutions and the school consent
- **Files:** `V40__institutions.sql` (`circles.kind`; `asset_consents.scope`/`windows`/`expires_at`); `CircleService` (create with kind); `custody/` (an `institution` consent: windows required, the school year as the default expiry, revocable by either side, the parent's circle stays owner); `SHARED_ASSETS_SQL` time check.
- **Tests:** `InstitutionTest`:
  - `parentSharesChildWithSchool`
  - `schoolSeesChildOnlyInWindows`
  - `schoolCannotEditProfileOrPlaces`
  - `parentRevokesAtOnce`
  - `consentExpiresAtYearEnd`
  - `staffRolesAreGuardianAndViewer`
- [ ] RED → implement → GREEN. Commit `feat(core): institutions and the school consent`.

### Task 2: Bus modes and routes
- **Files:** `V41__bus_routes.sql` (`asset_modes`, `route_stops`, `bus_runs`); `buses/BusService.kt`; routes for modes, routes and stops (owner/guardian of the institution); quorum for a mode switch during an open incident.
- **Tests:** `BusModeTest`:
  - `scheduleSwitchesRouteAndArmed`
  - `manualMaintenanceAudited`
  - `switchDuringIncidentNeedsQuorum`
  - `routeNeedsOrderedStops`
- [ ] RED → implement → GREEN. Commit `feat(core): bus modes and routes`.

### Task 3: Bus rules
- **Files:** `rules/BusRules.kt` (pure: `routeDeviation`, `stopLate`, `unscheduledStop`, `overspeed`); `jobs/BusScanner.kt`; `RuleId` additions and app rule names.
- **Tests:** `BusRulesTest` (pure), `BusScannerTest`:
  - `offCorridor60sIsP1`
  - `gpsJitterOnCorridorIsNot`
  - `lateAtStopIsP2`
  - `trafficLightIsNotAnUnscheduledStop`
  - `armedModeUsesVehicleRules`
  - `onlyInRouteMode`
- [ ] RED → implement → GREEN. Commit `feat(core): bus route rules`.

### Task 4: Riding the bus
- **Files:** `V42__rides.sql` (`rides(child_asset, bus_asset, boarding_stop, drop_stop, days)`, `ride_events`); `buses/Rides.kt` (aboard detection: driver tap, BLE, co-movement); the vehicle-speed suppression while aboard.
- **Tests:** `RideTest`:
  - `coMovementMeansAboard`
  - `driverTapMeansAboard`
  - `bleTagMeansAboard`
  - `aboardExplainsVehicleSpeed`
  - `p0NeverSuppressed`
  - `walkingBesideTheBusIsNotAboard`
- [ ] RED → implement → GREEN. Commit `feat(core): riding the bus`.

### Task 5: Not boarded, not dropped, left in the bus
- **Files:** `BusScanner` (`UNBOARDED`, `NOT_DROPPED`, `LEFT_IN_VEHICLE`); `RuleId` additions.
- **Tests:** `RideAlarmTest`:
  - `busLeftStopWithoutChildIsUnboarded`
  - `absentChildMarkedByParentIsNot` (the parent marks "not riding today")
  - `childStillAboardAfterDropStopIsNotDropped`
  - `engineOffChildInsideIsLeftInVehicleP0`
  - `schoolHolidayNoRun`
- [ ] RED → implement → GREEN. Commit `feat(core): unboarded, not dropped, left in vehicle`.

### Task 6: The B2B escalation chain
- **Files:** `escalation/` (an institution ladder: supervisor → institution guardians → owner → parents of the children aboard); `IncidentService` linking (a bus incident links the children aboard; a child incident while aboard links the bus).
- **Tests:** `InstitutionLadderTest`:
  - `busIncidentClimbsSupervisorSchoolParents`
  - `p0TellsParentsAtOnce`
  - `parentsOnlyOfChildrenAboard`
  - `childIncidentAboardLinksBus`
- [ ] RED → implement → GREEN. Commit `feat(core): the institution escalation chain`.

### Task 7: Driver mode in the app
- **Files:** companion template `driver`: start/end shift (= route mode for the run), the roster for the current run with "boarded"/"dropped" taps, and the BLE relay on; `DriverLogic` (+ test).
- **Tests:** `DriverLogicTest`:
  - `rosterInStopOrder`
  - `tapsQueueOffline`
  - `shiftEndClosesRun`
- [ ] RED → implement → GREEN. Commit `feat(app): driver mode`.

### Task 8: Web dashboard
- **Files:** `V43__web_sessions.sql`; `web/WebAuth.kt` (code from app → cookie session; revocation); `web/WebRoutes.kt` (server-rendered pages: buses, roster, incidents, run report CSV); `web/static/` (CSS and a small JS file; Leaflet pinned with SRI); the app's "Open on a computer" screen.
- **Tests:** `WebAuthTest`:
  - `codeIsSingleUseAnd2Min`
  - `cookieIsHttpOnlySecureStrict`
  - `csrfRequiredOnPost`
  - `revokedSessionLogsOut`
  - `staffSeeOnlyTheirInstitution`
  - `familyCircleHasNoDashboard`

  `WebPagesTest`:
  - `busesPageListsRunsNow`
  - `incidentClaimFromWeb`
  - `cspHeaderPresent`
  - `noPositionsOutsideConsentWindows`
- [ ] RED → implement → GREEN. Commit `feat(core): institution web dashboard`.

### Task 9: Community volunteers
- **Files:** `V44__community.sql` (`volunteers`); routes: opt in, set area (≤ 3 km), operator verify/suspend; app volunteer card.
- **Tests:** `VolunteerTest`:
  - `optInIsPendingUntilVerified`
  - `radiusCappedAt3km`
  - `onlyOperatorsVerify`
  - `volunteerCanLeaveAndIsForgotten`
- [ ] RED → implement → GREEN. Commit `feat(core): community volunteers`.

### Task 10: Community alerts
- **Files:** `community_alerts`, `community_replies` (V44); `community/CommunityService.kt`: "Ask the community" on an open P0, the 500 m grid, push to verified volunteers in range, replies, false reports, expiry, daily cap, suspension; `media` reuse for the one photo; app: "Ask the community" button on P0 incidents and the volunteer's alert screen.
- **Tests:** `CommunityTest`:
  - `onlyGuardiansOfOpenP0CanAsk`
  - `onlyVerifiedVolunteersInRangeReceive`
  - `volunteersSeeRoundedAreaNotTrack`
  - `photoOnlyIfAttachedNow`
  - `seenHereGoesToFamilyOnly`
  - `alertExpiresWithIncidentOr6h`
  - `twoAlertsADayCap`
  - `threeFalseReportsSuspend`
  - `everySendAudited`
- [ ] RED → implement → GREEN. Commit `feat(core,app): community alerts with safeguards`.

### Task 11: Scenarios
- **Files:** `src/test/resources/buses/*.json`, a positive and a negative per new rule (AC-19); `BusScenariosTest`; the P7 `NormalMonthTest` extended with a school bus run every school day for 6 children, still at zero P1 and zero P0.
- [ ] Commit `test(core): bus scenarios; the normal month rides the bus`.

### Task 12: Gate
- [ ] CI green.
- [ ] A whole-branch review by a fresh agent, every finding fixed.
- [ ] `docs/evidence/P9-gate.md` and `docs/runbooks/P9-school-pilot.md` (Arabic): one school, one bus, a week.
- [ ] PR. **Merge only on the owner's approval.**

---

## P9 Exit Gate
- CI green: every scenario library (P4–P9) and the normal month with buses.
- AC-19 for every new rule.
- The school sees a child only inside the parent's consent windows (`schoolSeesChildOnlyInWindows`).
- Nothing reaches the community without a guardian's tap (`onlyGuardiansOfOpenP0CanAsk`), and volunteers never see a track (`volunteersSeeRoundedAreaNotTrack`).
- **Field (owner):** a school pilot with one bus for a week (runbook). The P8 trial machinery measures it.

## Out of scope (P10)
ANPDP filing, store listing, privacy policy, marketing. A human monitoring centre ("all unavailable" fallback) stays a later decision.
