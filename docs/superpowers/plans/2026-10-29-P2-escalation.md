# Hirz P2 — Escalation Implementation Plan

> **ملخص بالعربية:** المرحلة P2 تجعل الإنذار **لا يُهمَل**:
> - **سلّم تصعيد** بنوافذ تأكيد تبدأ من وصول الإشعار. الأقرب يتولى بشرط فرق لا يقل عن 300 م، وإلا الحاضن، وإلا الدوران بالترتيب.
> - **نصاب وليَّين** لإغلاق P0، و"تأكيد زمني ثانٍ" إذا كان في الدائرة ولي واحد.
> - **أفعال مشتركة** مثل «اتُّصل بـ1548 بواسطة خالد».
> - **كلمة التحقق العائلية.**
> - **ورقة الحادث والمنشور الجاهز** (PDF وصورة)، و**حزمة الشرطة** (CSV وGPX وبصمات وشهادة اكتمال).
> - **رابط مشاركة حي مؤقت.**
> - **بوابة SMS** على هاتف أندرويد، مع رد «1» = أتولى.
> - **أفعال عن بعد:** رنين الجهاز، وتردد عالٍ تلقائي عند P0.
> - **قاعدة «لم يرد على Check-in».**
> - **الحضانة والتسليم المؤقت**، و**أمان الحساب.**
> - إصلاح الملاحظات المؤجلة من P1 التي تمس السلامة.
>
> **بوابة الخروج:** يُقاس السلّم والنصاب بسيناريوهات زمنية في CI. وعلى الأجهزة: إنذار بلا تأكيد يصل الولي الثاني ثم جهة الاتصال برسالة SMS، والرد «1» يُسند الحادث.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** No P0/P1 alert can be silently missed. An unacknowledged alert climbs a measured ladder to the next guardian and then to no-app contacts by SMS. Closing a P0 needs two people, or a timed second confirmation when the circle has only one guardian. Every incident produces, in seconds, a sheet to share and a tamper-evident police pack.

**Architecture:** Server side, the incident layer gains:
- an `EscalationEngine`: pure state transitions, driven by a scanner tick and by receipts/claims;
- a `ClosePolicy` (quorum or the timed confirmation);
- an `outbound_sms` queue drained by gateway phones;
- export renderers.

The app gains:
- guardian location during an active P0/P1 incident only;
- an SMS-gateway mode;
- incident-sheet and police-pack screens;
- the family code word;
- custody and handover editors.

Everything builds on branch `p1/live-core` (P1 is not merged yet). The work happens on `p2/escalation`, cut from it.

**Tech Stack:** P1 stack. New pieces:
- Android `SmsManager` and an SMS broadcast receiver (gateway mode only);
- `PdfDocument` (Android) for the sheet;
- server-side zip (`java.util.zip`) and SHA-256;
- `pgcrypto` for the code word, with the key in env `HIRZ_DATA_KEY`.

**Spec:** `docs/05-hirz-design.md` §5 (all of it), §5.1, §12.1 (shared custody, handover), §12.2 (`NO_RESPONSE_CHECKIN`), §12.3 (1, 2, 3, 5, 6, 7, 9, 10, 11), §12.5 (3, 4, 7). P1 plan and ledger: `docs/superpowers/plans/2026-10-27-P1-live-core.md`, `.superpowers/sdd/2026-10-27-P1-live-core/progress.md`.

## Global Constraints

- Every P1 Global Constraint applies unchanged. That includes the DTOs in `:shared`, explicit lat/lon, nullable fields defaulting to null, no coordinates or reasons in any push or SMS, and RED-before-GREEN in CI.
- **Ladder timing** comes from `RuleConfig`. Starting values to be measured:
  - ack windows: P0 60 s, P1 180 s;
  - `delivered`-start fallback after 20 s with no receipt (the window then runs from `sent`, and this is logged);
  - contact stage after all guardians time out;
  - "everyone unavailable" retries every 120 s;
  - nearest-guardian margin 300 m;
  - single-guardian confirmation window 120 s.
- **Guardian locations** are collected only while the member's circle has an open P0/P1 incident. They are deleted when it closes (spec §5.11). They never appear on the map outside an incident.
- **SMS text** never contains a name of the carrier beyond the asset's display name, never coordinates, and never the code word. Each message carries a short incident reference (`H-4821`) and the reply instructions. Arabic is the default; the language follows each contact's `lang`.
- **Gateway SMS sending is a sideload/dev capability.** Google Play restricts `SEND_SMS`/`RECEIVE_SMS` to default SMS handlers. The gateway mode ships in a separate build flavor `gateway`, never in the Play flavor. Recorded as a P2 constraint, not a defect.
- **Exports:**
  - files are UTF-8, times in both UTC and Africa/Algiers, WGS84;
  - every export is audited (`audit_log`, `incidents.exported_at`) and carries a SHA-256 manifest;
  - a gap is any interval between consecutive fixes longer than 2 × the expected cadence (60 s normal, 15 s high-rate), and every gap is listed.
- **Code word:** encrypted at rest with `pgp_sym_encrypt` using `HIRZ_DATA_KEY`. It is returned only by `GET /v1/circles/{id}/code-word` to members, and never logged, pushed or exported.

## Out of P2 (deliberate)

- **Quiet hours** (§12.3.6): nothing to build, because P2 findings are never pushed. They stay in-app.
- **Linking incidents across assets** (§12.3.5, child + bus or car): this needs vehicle and bus assets, so it moves to P4 and P9. Same-asset dedupe already exists (P1).
- **Evidence snapshot at P0** (§12.3.10): covered by the police pack window (opened − 2 h) plus the append-only incident events and deliveries.
- **Voice calls**: an interface only. A provider and payment come later (owner decision, §4 of decisions).
- **Audio recording** (§5.2): gated on the Play policy review and a legal opinion. It is not started.
- **Account recovery and remote session revoke** (§12.5.3–4): moved to P10 (launch hardening). Sign-out push-token cleanup is in Task 11.

## Review Focus

1. **Two guardians acknowledging the same alert in the same second** must yield exactly one owner and no second escalation. Pinned by `concurrentClaimsYieldOneOwner` (Task 1).
2. **A receipt arriving after the window already escalated** must not undo the escalation or reopen a closed step. Pinned by `lateReceiptAfterEscalationIsRecorded` (Task 1).
3. **The only guardian closing a P0** must get a confirmation request, not an instant close, and the incident must stay open if nobody confirms. Pinned by `singleGuardianCloseNeedsTimedConfirm` (Task 3).
4. **An SMS reply from an unknown number, or "1" to an incident that is already closed,** must not change anything. Pinned by `unknownNumberReplyIgnored` and `replyToClosedIncidentIgnored` (Task 8).
5. **A police pack for a track with a 20-minute silence** must list the gap, never interpolate it. Pinned by `packListsGapsNeverInterpolates` (Task 6).

---

### Task 1: Escalation engine (server)

**Files:** `server/.../escalation/{EscalationEngine.kt, EscalationScanner.kt, Ladder.kt}`, `V5__escalation.sql`, tests `escalation/LadderTest.kt` (pure) and `escalation/EscalationTest.kt` (HTTP + DB).

**Interfaces — Produces:**
- Table `escalation_state(incident_id PK, stage text CHECK IN ('guardians','contacts','everyone_unavailable','claimed','closed'), current_member uuid, attempt int, window_started_at, window_from text CHECK IN ('delivered','sent'), next_check_at)`.
- Table `escalation_log` (append-only): `incident_id, kind, member_id, contact_phone, at, data jsonb`.
- `object Ladder`: a pure function `next(state, event, now, config, order: List<Candidate>): Pair<LadderState, List<LadderAction>>`.
  - Events: `Opened`, `Delivered(member)`, `Opened(member)`, `Claimed(member)`, `Contested(member)`, `Tick`, `Closed`.
  - Actions: `NotifyMember(member)`, `NotifyContacts(list)`, `LogWindowFrom(sent|delivered)`, `RetryAllChannels`.
- The ordering of `order` is Task 2's responsibility. Task 1 uses circle priority.
- `EscalationScanner` ticks every 5 s and loads `escalation_state` rows with `next_check_at <= now`.
- `IncidentService.open` (P1) calls `engine.onOpened` after fan-out. `receipt`, `claim`, `contest` and `close` forward events.
- The P1 fan-out stays exactly as it is: everyone at once, design §5.2. The ladder decides **who owns it** and **when to widen**: a renewed push to the next owner and SMS to contacts. It never decides who may know.

- [ ] **Step 1: Write the failing tests.**
  - Pure `LadderTest`:
    - `firstOwnerIsFirstCandidate`
    - `windowStartsAtDeliveredWhenItArrives`
    - `windowFallsBackToSentAfter20sAndLogsIt`
    - `noClaimWithinP0WindowMovesToNextCandidate` (at 60 s + delivered time)
    - `afterLastGuardianContactsAreNotified`
    - `everyoneUnavailableRetriesEvery120s`
    - `claimStopsTheLadder`
    - `contestRestartsAtNextCandidate`
    - `lateReceiptAfterEscalationIsRecorded`
    - `closedIgnoresEverything`
  - HTTP `EscalationTest` with a moving clock and the scanner invoked directly:
    - `p1UnclaimedReachesSecondGuardianThenContacts` (the contacts land in `outbound_sms`, Task 8 table created here)
    - `concurrentClaimsYieldOneOwner` (two coroutines claim at once; exactly one `claimed` log row)
    - `p2NeverEscalates`
- [ ] **Step 2:** RED in CI.
- [ ] **Step 3: Implement.** The scanner `SELECT … FOR UPDATE SKIP LOCKED` rows due, applies `Ladder.next`, executes actions, and writes state and log in one transaction.
- [ ] **Step 4:** Green.
- [ ] **Step 5: Commit:** `feat(core): escalation ladder with delivered-based windows, contacts stage and retries`

---

### Task 2: Owner assignment (nearest ≥ 300 m, custodian, rotation) and guardian location during incidents

**Files:** `escalation/OwnerOrder.kt`, `V6__member_locations.sql`, `incidents/MemberLocationRoutes.kt`, tests `OwnerOrderTest.kt`, `MemberLocationTest.kt`.

**Interfaces — Produces:**
- `POST /v1/incidents/{id}/my-location {position: LatLon, accuracyM}` (member auth).
  - Accepted only while the incident is P0/P1 and open; otherwise 409.
  - Stored in `member_locations(incident_id, member_id, geog, accuracy_m, at)`.
  - Deleted on close by the close transaction.
- `fun ownerOrder(members, lastAssetFix, memberLocations, custodian: UUID?, now): List<Candidate>`:
  - The nearest member comes first **only if** it is ≥ 300 m closer than the second.
  - Otherwise the current custodian: the member with an active `handovers` row, else the member who last saw the asset inside a home/school place.
  - Then priority order.
  - Ties break by member id.
- The guardian app sends its location every 30 s while the incident screen is open **and** the incident is open (Task 11).

- [ ] **Step 1: Write the failing tests:**
  - `nearestNeeds300mLead`: 250 m vs 400 m gives priority order; 100 m vs 600 m gives nearest first.
  - `custodianBeatsPriorityWhenNoClearNearest`
  - `deterministicTieBreak`
  - `locationRejectedWithoutOpenIncident`
  - `locationsDeletedOnClose`
  - `assignmentDoesNotFlipFlopWithJitter`: alternating 20–50 m jitter keeps the same owner across 10 ticks.
- [ ] **Step 2:** RED.
- [ ] **Step 3: Implement.** `Ladder` receives `ownerOrder(...)` at each stage change.
- [ ] **Step 4:** Green.
- [ ] **Step 5: Commit:** `feat(core): nearest-guardian ownership with a 300 m lead and incident-only guardian locations`

---

### Task 3: Close policy — quorum, single-guardian timed confirmation, "found safe" proof, shared actions

**Files:** `incidents/ClosePolicy.kt`, `V7__close_requests.sql`, `incidents/ActionRoutes.kt`, tests `ClosePolicyTest.kt`, `SharedActionsTest.kt`.

**Interfaces — Produces:**
- `POST /v1/incidents/{id}/close {reason}`:
  - For **P1/P2** it closes as in P1.
  - For **P0** it creates `close_requests(incident_id, requested_by, reason, at, expires_at)` and returns `IncidentDto(state = "closing")`. A different owner/guardian confirms with `POST /v1/incidents/{id}/close/confirm`, which closes the incident.
  - **The only guardian** (owner/guardian count = 1) gets a confirmation push after 0 s and confirms within 120 s from a second tap (`confirm` by the same member, allowed only in that case). If the window expires, the request lapses and the incident stays open with event `close_request_expired`.
- `reason = found_safe` additionally needs **proof**:
  - the asset's last fix (≤ 120 s old) inside a home/school/relative place, or
  - a member location within 100 m of the last fix.
  - Otherwise it needs a quorum even for P1.
- Claim limited to owner/guardian/temporary (P1 minor #14). Contest stays open to every member.
- `POST /v1/incidents/{id}/actions {kind: called_number|went_to_last_position|checked_school|other, value?: String}`:
  - appends an event and appears on every member's screen ("1548 — Khaled — 15:44");
  - the same `called_number` value twice within 10 minutes returns 409 `already_done` with who did it.
- Post-close review: `POST /v1/incidents/{id}/review {falseAlarm: Boolean, note?}` (owner/guardian). It is stored and counted per rule for the false-alarm budget.

- [ ] **Step 1: Write the failing tests:**
  - `p0CloseNeedsSecondGuardian`
  - `sameGuardianCannotConfirmWhenTwoExist`
  - `singleGuardianCloseNeedsTimedConfirm`
  - `closeRequestExpiresAndIncidentStaysOpen`
  - `foundSafeWithoutProofNeedsQuorumEvenForP1`
  - `foundSafeWithAssetHomeIsAllowed`
  - `viewerCannotClaimButCanContest`
  - `duplicateCallIsReportedWithWhoDidIt`
  - `reviewFeedsFalseAlarmCounter`
- [ ] **Step 2:** RED.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Green.
- [ ] **Step 5: Commit:** `feat(core): quorum and timed confirmation for closing P0, found-safe proof and shared actions`

---

### Task 4: Family code word and emergency-number registry

**Files:** `circles/CodeWordRoutes.kt`, `V8__code_word.sql` (`CREATE EXTENSION IF NOT EXISTS pgcrypto`, `circles.code_word_enc bytea`), `emergency/EmergencyRoutes.kt`, tests.

**Interfaces — Produces:**
- `PUT /v1/circles/{id}/code-word {word}` (owner/guardian, 3–40 chars).
- `GET /v1/circles/{id}/code-word` (members) returns `{word}`. Audited as `code_word_viewed`.
- `GET /v1/emergency-numbers?country=DZ` returns `[{number, label, verifiedAtMs?}]`.
- `POST /v1/emergency-numbers/{number}/confirm` (any member, records who confirmed for the circle's country). The app shows unverified numbers with "check for your wilaya".

- [ ] **Step 1: Write the failing tests:**
  - `codeWordEncryptedAtRest`: the raw column does not contain the word.
  - `codeWordOnlyForMembers`
  - `codeWordNeverInPushOrSmsOrExport`: render each of them and assert the word is absent.
  - `registryListsSeededNumbersUnverified`
- [ ] **Step 2:** RED.
- [ ] **Step 3:** Implement. `HIRZ_DATA_KEY` is required at startup; CI sets a random one.
- [ ] **Step 4:** Green.
- [ ] **Step 5: Commit:** `feat(core): encrypted family code word and the emergency-number registry`

---

### Task 5: Incident sheet data and asset descriptors

**Files:** `incidents/SheetService.kt`, `assets/PhotoRoutes.kt`, `V9__asset_media.sql` (`asset_photos(asset_id PK, jpeg bytea ≤ 512 KB, updated_at)`, `assets.today_clothes text, today_clothes_at`), tests.

**Interfaces — Produces:**
- `PUT /v1/assets/{id}/photo` (JPEG ≤ 512 KB, editors).
- `GET /v1/assets/{id}/photo` (members).
- `PUT /v1/assets/{id}/today {clothes}`. It is shown only on the same local day.
- `GET /v1/incidents/{id}/sheet` returns `SheetDto`:
  - incident, asset name, type and descriptors, today's clothes (same day only);
  - last fix with age and accuracy, the last 30 minutes of track;
  - sources (kind, model, IMEI if any);
  - owner and open actions, emergency numbers (with verified flags), and the share link if one is active.
  - **Code word excluded.** The app shows it separately after re-auth (Task 4).

- [ ] **Step 1: Write the failing tests:**
  - `sheetHasTrackOf30Minutes`
  - `clothesExpireAtLocalMidnight`
  - `photoSizeLimited`
  - `sheetNeverContainsCodeWord`
  - `sheetLogsLocationView`
- [ ] **Step 2:** RED.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Green.
- [ ] **Step 5: Commit:** `feat(core): incident sheet data, asset photo and today's clothes`

---

### Task 6: Police pack export

**Files:** `exports/PolicePack.kt`, `exports/ExportRoutes.kt`, tests `PolicePackTest.kt`.

**Interfaces — Produces:**
- `GET /v1/incidents/{id}/police-pack` (owner/guardian) returns `application/zip` containing:
  - `positions.csv`, with header `fix_time_utc,fix_time_algiers,server_time_utc,lat,lon,accuracy_m,accuracy_estimated,fix_source,speed_kmh,heading,battery,source_id,source_kind,device_model,quality`;
  - `track.gpx`;
  - `incident.json` (the incident DTO, events, deliveries);
  - `completeness.txt`, listing every gap with start, end and duration, plus counts;
  - `manifest.json`, with SHA-256 per file, export time, the exporting member and the server version.
- Window: from opened_at − 2 h to closed_at, or to now while open.
- Sets `exported_at`, writes `audit_log('police_pack_exported')` and logs a location view.

- [ ] **Step 1: Write the failing tests:**
  - `packContainsAllFilesWithMatchingHashes`
  - `packListsGapsNeverInterpolates`: one 20-minute silence gives one gap line, and the CSV has no row inside the gap.
  - `timesInUtcAndAlgiers`
  - `viewerCannotExport`
  - `exportIsAudited`
- [ ] **Step 2:** RED.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Green.
- [ ] **Step 5: Commit:** `feat(core): tamper-evident police pack with gap certificate`

---

### Task 7: Temporary live share link

**Files:** `share/ShareRoutes.kt`, `V10__share_links.sql`, `server/src/main/resources/share/page.html`, tests.

**Interfaces — Produces:**
- `POST /v1/incidents/{id}/share {ttlMinutes: 15..240}` (owner/guardian) returns `{url: "<base>/s/<token>", expiresAtMs}`. The token is 32 bytes; only its hash is stored.
- `DELETE /v1/incidents/{id}/share` revokes it.
- `GET /s/{token}` is a public HTML page with no third-party scripts. It contains:
  - the asset name;
  - the last position with its age and accuracy, as a link to openstreetmap.org;
  - a list of the last-30-minute fixes;
  - an auto-refresh every 30 s.
- `GET /s/{token}/data` serves the same data as JSON.
- Every open is logged in `share_views(token_hash, at, ip_hash, user_agent)`.
- The link stops working when the incident closes.

- [ ] **Step 1: Write the failing tests:**
  - `shareWorksUntilExpiry`
  - `revokedShare404`
  - `closedIncidentShare404`
  - `shareViewsLogged`
  - `pageHasNoExternalScripts`
- [ ] **Step 2:** RED.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Green.
- [ ] **Step 5: Commit:** `feat(core): revocable, expiring live share link with an access log`

---

### Task 8: SMS gateway (server side) and contacts

**Files:** `sms/{SmsQueue.kt, SmsRoutes.kt, SmsText.kt}`, `V11__sms.sql`, tests.

**Interfaces — Produces:**
- Contacts:
  - `circle_members.phone text`, `circle_members.lang`;
  - `POST /v1/circles/{id}/contacts {name, phone (E.164 or 0XXXXXXXXX normalised to +213), lang}` (owner) creates a `contact` member without an app.
- Gateways:
  - `POST /v1/circles/{id}/gateways` (owner) returns a pairing code;
  - `POST /v1/gateways/pair {code}` returns a gateway token (kind `gateway`, stored like sources);
  - `GET /v1/gateways/me/outbox` (gateway auth) returns pending `outbound_sms` items;
  - `POST /v1/gateways/me/outbox/{id}/result {sent|failed, error?}`;
  - `POST /v1/gateways/me/inbox {from, body, receivedAtMs}`.
- The server also sends a data push `t=sms_pending` to the gateway when the outbox gains an item, so delivery doesn't wait for polling.
- Inbox handling:
  - Contact replies normalise the body (Arabic-Indic digits → ASCII, trim). `1` = claim on behalf of that contact. `2` = "can't, escalate". Anything else is logged only.
  - The reply targets the **latest** open incident that contact was notified about.
  - An unknown number or a closed incident leaves no effect beyond a log row.
- `SmsText.alert(contactLang, assetName, tier, ref)`, for example in Arabic: «تنبيه حِرز H-4821: أحمد. افتح الرابط إن وُجد أو اتصل بالعائلة. للرد: 1 = أتولى، 2 = لا أستطيع».
  - Max 160 GSM-7 / 70 UCS-2 characters per part, at most 2 parts.
  - Never coordinates. The share link is included **only** if the owner ticked "include link for contacts" on that incident.

- [ ] **Step 1: Write the failing tests:**
  - `contactsStageQueuesOneSmsPerContact`
  - `replyOneClaimsForContact`
  - `replyTwoEscalatesFurther`
  - `unknownNumberReplyIgnored`
  - `replyToClosedIncidentIgnored`
  - `arabicIndicDigitsAccepted` (`١`)
  - `smsTextFitsTwoPartsAndHasNoCoordinates` (in all 3 languages)
  - `gatewayTokenCannotReadOtherCircles`
- [ ] **Step 2:** RED.
- [ ] **Step 3:** Implement. The Task 1 contact stage writes into `outbound_sms`.
- [ ] **Step 4:** Green.
- [ ] **Step 5: Commit:** `feat(core): SMS gateway queue, contacts and SMS replies`

---

### Task 9: Remote actions, high rate on P0, and NO_RESPONSE_CHECKIN

**Files:** `incidents/RemoteActions.kt`, `rules/CheckInRule.kt`, `V12__checkins.sql` (`checkins(id, asset_id, sent_at, answered_at, answer)`), tests.

**Interfaces — Produces:**
- `POST /v1/assets/{id}/ring` (editors) sends a data push `t=ring` to the sources. The companion plays the alarm sound for 30 s even when silent (`USAGE_ALARM`), with a stop button.
- When an incident opens at P0, the server pushes `t=high_rate` to every source of the asset. The companion moves to 15 s reporting until it is told otherwise or the incident closes (`t=normal_rate`).
- `POST /v1/assets/{id}/checkin` (P1) now records a `checkins` row.
  - A companion reply (`checkInReply` or a cancel-PIN hash) answers the latest open check-in.
  - **NO_RESPONSE_CHECKIN:** no answer within 5 minutes gives a P1 finding (`no_checkin_reply{minutes}`). With an open incident on the asset, it adds the reason to that incident instead.
- The cancel PIN now appends the neutral reason `cancel_pin_entered` to an open SOS (P1 minor #13). It never closes anything.

- [ ] **Step 1: Write the failing tests:**
  - `ringPushesToSources`
  - `p0OpensWithHighRateToSources`
  - `closeSendsNormalRate`
  - `unansweredCheckinAfter5MinIsP1`
  - `answeredCheckinRaisesNothing`
  - `cancelPinAddsReasonNotClose`
- [ ] **Step 2:** RED.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Green.
- [ ] **Step 5: Commit:** `feat(core): remote ring, automatic high rate on P0 and the unanswered check-in rule`

---

### Task 10: Custody windows, temporary handovers, shared custody between circles

**Files:** `custody/{HandoverRoutes.kt, ConsentRoutes.kt, Custody.kt}`, tests.

**Interfaces — Produces:**
- `POST /v1/assets/{id}/handovers {memberId, startsAtMs, endsAtMs, mutePlaceExit: Boolean}` (owner/guardian).
  - During the window the member is the **custodian** (Task 2).
  - If `mutePlaceExit`, PLACE_EXIT findings are recorded but not raised (spec §12.1, v2 amendment ج-5).
  - SOS, duress and silence are never muted.
- `POST /v1/assets/{id}/consents {otherCircleCode}` creates a consent request. The other circle's owner accepts with `POST /v1/consents/{id}/accept`. Then both circles see the asset, and either side can revoke.
  - Every grant and revocation is pushed (in-app, P2-tier, no alarm) to every member of both circles (spec §12.1).
- `RulesEngine` consults `Custody.muted(asset, at)` for PLACE_EXIT only.

- [ ] **Step 1: Write the failing tests:**
  - `handoverMakesCustodian`
  - `mutedExitStillRecordedNotRaised`
  - `sosNeverMuted`
  - `consentNeedsOtherOwner`
  - `revocationNotifiesBothCircles`
  - `expiredHandoverStopsMuting`
- [ ] **Step 2:** RED.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Green.
- [ ] **Step 5: Commit:** `feat(core): custody windows, temporary handovers and consented shared custody`

---

### Task 11: App — escalation, close, actions, code word, sheet, pack, share

**Files:** `app/.../guardian/{IncidentDetail.kt, SheetScreen.kt, ActionsBar.kt, CodeWordScreen.kt}`, `androidMain/.../share/{SheetPdf.kt, ShareIntents.kt}`, `androidMain/.../guardian/IncidentLocationReporter.kt`, strings (ar/fr/en), tests `IncidentDetailLogicTest.kt`.

**Behavior:**
- The incident detail shows:
  - the ladder stage ("بانتظار أمينة حتى 15:43:00" — "waiting for Amina until 15:43:00");
  - owner, actions and close requests;
  - "found safe" disabled with an explanation when proof is missing and no quorum is possible.
- **Location reporter:** while an incident detail is visible **and** the incident is open, the guardian app sends `my-location` every 30 s (foreground only). It stops on close or on leaving the screen.
- **Sheet:**
  - it renders in under 10 s;
  - the "PDF" button uses `PdfDocument` (Arabic and French pages);
  - "منشور" ("post") composes an image (photo, name, clothes, last-seen place and time, family phone) and opens the Android share sheet. **Nothing is posted without that tap.**
- **Police pack:** downloads the zip to Downloads through the MediaStore API and shows the manifest hash.
- **Share link:** create with a duration picker, copy, revoke.
- **Code word:**
  - set and change from Circle settings;
  - viewing requires device credential re-auth (`BiometricPrompt` with `DEVICE_CREDENTIAL`);
  - never shown in notifications.
- **Sign-out** deletes the member push token on the server (`DELETE /v1/me/push-token/{token}`, added in Task 4) before Firebase sign-out (P1 minor #20).

- [ ] **Step 1: Write the failing logic tests:**
  - `stageTextShowsOwnerAndDeadline`
  - `foundSafeExplainsMissingProof`
  - `postTextNeverContainsCoordinatesOrCodeWord`
  - `reporterRunsOnlyWhileOpenAndVisible`
- [ ] **Step 2:** RED.
- [ ] **Step 3:** Implement the UI.
- [ ] **Step 4:** Green; `assembleDebug`; the forbidden-claims guard passes.
- [ ] **Step 5: Commit:** `feat(app): ladder, close requests, shared actions, code word, sheet, post, pack and share link`

---

### Task 12: App — SMS gateway mode (flavor `gateway`) and companion remote actions

**Files:** `app/build.gradle.kts` (product flavors `play` and `gateway`; only `gateway` declares `SEND_SMS` and `RECEIVE_SMS` in `src/gateway/AndroidManifest.xml`), `app/src/gateway/kotlin/.../{GatewayService.kt, SmsReceiver.kt}`, `app/.../gateway/GatewayScreen.kt`, companion `RingActivity`, tests `GatewayLogicTest.kt`.

**Behavior:**
- **Gateway mode:**
  - Pairing works by code.
  - A foreground service drains the outbox on push and every 60 s. Sending uses `SmsManager.divideMessage` + `sendMultipartTextMessage`, and the sent/delivered PendingIntents report results.
  - `SmsReceiver` forwards every received SMS to `/gateways/me/inbox`. The server decides relevance.
  - A "heartbeat" every 5 minutes; silence raises a P2 "gateway silent" to the owner (server rule `GATEWAY_SILENT`, part of this task).
- **Companion:**
  - `t=ring` opens `RingActivity` (alarm stream, 30 s, stop button).
  - `t=high_rate` and `t=normal_rate` drive `CompanionCadence`.
  - The notification SOS action asks for one confirmation tap (P1 minor #19).

- [ ] **Step 1: Write the failing tests:**
  - `outboxResultMapping`
  - `inboxForwardsAllMessages`
  - `gatewayHeartbeatEvery5Min`
  - server `gatewaySilenceRaisesP2`
  - `ringStopsAfter30s` (logic)
  - `notificationSosNeedsConfirm` (state machine)
- [ ] **Step 2:** RED.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Green; `assembleGatewayDebug` and `assemblePlayDebug` both build; a manifest check asserts the play flavor has no SMS permissions.
- [ ] **Step 5: Commit:** `feat(app): SMS gateway flavor, remote ring and rate commands, SOS action confirmation`

---

### Task 13: Carried P1 minors that touch safety

**Files:** as named.

- [ ] **Step 1: Write the failing tests:**
  - `overlappingIngestsKeepBoundaryCounts` (#11): two concurrent batches for one asset do not lose an exit. Fix with `pg_advisory_xact_lock(hashtext(asset_id))` around load/save.
  - `silenceEpisodesSurviveRestart` (#17): persist `episodes` in `sources.silence_episode`.
  - `highRateSameForAnyPin` (#12): after any PIN entry, `highRate` is true for 10 minutes regardless of meaning.
  - `wsTokenIsUrlEncodedAndNeverNull` (#21): app-side unit test of the URL builder; the server rejects a `null` token (4401).
- [ ] **Step 2:** RED.
- [ ] **Step 3:** Fix each.
- [ ] **Step 4:** Green.
- [ ] **Step 5: Commit:** `fix(core,app): P1 minors — rule-state lock, persistent silence episodes, PIN-blind high rate, safe WS token`

---

### Task 14: P2 verification — scenarios in CI and the device trial

**Files:** `server/src/test/resources/escalation/*.json` (time scenarios), `docs/runbooks/P2-trial.md`, `docs/evidence/P2-gate.md`.

- [ ] **Step 1:** Escalation replay scenarios run through the HTTP API with a moving clock. Each one is a list of `{t, action}` and `expect` (ladder log kinds and owners). Scenarios:
  - `nobody_answers_p0` (owner1 → owner2 → contacts → retries)
  - `second_guardian_claims`
  - `contest_restarts`
  - `single_guardian_close_expires`
  - `found_safe_with_proof`
  - `contact_replies_1`
  - `gateway_silent`
- [ ] **Step 2: Device trial** (needs P1 Task 0 plus a third Android phone or an emulator as the gateway with a SIM):
  1. 5 × P1 with no tap on the first guardian: record the time to the second guardian's alarm and to the contact's SMS.
  2. 3 × a contact reply "1": the incident shows the contact as owner.
  3. 2 × a P0 close by a single guardian: confirmed, and left to expire.
  4. 1 police pack opened on a PC: hashes match the manifest, and gaps are listed.
  5. 1 share link opened from a phone without the app, then revoked.
- [ ] **Step 3:** Write `P2-gate.md` with the measured times, screenshots and every failure as it happened.
- [ ] **Step 4: Commit:** `docs(evidence): P2 escalation trial`

---

## P2 Exit Gate
- [ ] CI green, including every escalation scenario. P1 tests stay green.
- [ ] On devices: an unacknowledged P1 reaches the 2nd guardian within window + measured delivery, and a contact SMS follows. Times are reported.
- [ ] A contact SMS reply "1" assigns the incident.
- [ ] A P0 can't be closed by one person without the timed second confirmation.
- [ ] The police pack hashes verify on a PC, and the gap certificate lists every silence.
- [ ] No push, SMS, sheet, post or export contains coordinates (except the pack and the share page by design) or the code word. Checked by tests and by reading one of each.
