# Hirz P8 — Field Trial Implementation Plan

> **ملخص بالعربية:** P8 هي **التجربة الميدانية**: من 5 إلى 10 عائلات ومالكين لمدة 4 أسابيع، على staging، بأجهزة حقيقية. البوابة: **ميزانية الإنذار الكاذب محققة ومنشورة**.
>
> ما أبنيه أنا قبل التجربة (برمجيات):
> - **الميزانية نفسها مكتوبة بالأرقام** (لا شيء منها اليوم): كم إنذارًا كاذبًا مسموحًا لكل مستوى ولكل 100 يوم-أصل، وكم إنذارًا حقيقيًا مسموحًا أن يفوت (صفر).
> - **تسجيل نتيجة كل إنذار:** بعد إغلاق كل حادث يُسأل الولي «هل كان حقيقيًا؟»، ولكل سطر من ملخص المساء «مفيد / غير مفيد».
> - **مقاييس يومية مجهولة الهوية:** إنذارات كاذبة لكل قاعدة، زمن وصول الإشعار، زمن الاستلام، فترات صمت الأجهزة. **بلا أسماء ولا إحداثيات.**
> - **تقرير المشغّل:** صفحة/ملف للمشرف على التجربة، يقارن الأرقام بالميزانية تلقائيًا ويقول: ناجح / راسب، ولماذا.
> - **التسجيل في التجربة والموافقة والانسحاب:** موافقة مكتوبة داخل التطبيق، وزر انسحاب يحذف بيانات التجربة.
> - **زر «أبلغ عن مشكلة»** في التطبيق، بلا موقع.
> - **دليل التجربة** بالعربية: التجنيد، الأجهزة، الجدول الأسبوعي، الاختبارات الموجّهة لكل المراحل P1–P7، وما يُسجَّل.
>
> ما يحتاجك أنت: تجنيد العائلات والمالكين، الأجهزة، تشغيل staging، وإدارة الأسابيع الأربعة.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **Never build locally: push and read CI** (the owner's machine is weak).

**Goal:** Run Hirz with 5–10 real households and owners for four weeks and prove, with numbers, that it rings when it should and stays quiet when it should. Every alarm gets an outcome, every outcome feeds a published budget, and the gate passes or fails on those numbers alone.

**Architecture:**
- **The budget is data, not prose.** `shared/rules/FalseAlarmBudget.kt` holds the thresholds. A pure `BudgetGate.judge(metrics)` returns pass/fail per line with the reason. The same object backs the CI test, the operator report and the published doc, so they cannot drift apart.
- **Outcomes.** The existing `incident_reviews` already records "false alarm?". P8 makes the review asked for every closed P0/P1, with a reason code (`real`, `false_device`, `false_rule`, `test`, `unsure`). Digest lines get a `useful` flag (`digest_feedback`). A `test` outcome (a guided test) never counts as false.
- **Trial enrolment** (`trials`, `trial_circles`, V36): an operator opens a trial with start and end dates. A circle owner joins it in the app with explicit consent, and can leave at any time. Leaving deletes that circle's trial metrics.
- **Metrics** (`trial_daily`, V37) are computed nightly by `TrialMetricsJob`, per asset-day, keyed by a per-trial pseudonym and never the asset id.
  - Counts per rule and tier: findings, incidents, outcomes.
  - Push latency: incident opened to `delivered_at` and to `opened_at` (already in receipts, V1).
  - Time to first claim. Silence minutes and the share of the day with a fresh fix.
  - No names, no coordinates, no reasons text.
- **Operators** are a server allow-list (`HIRZ_OPERATORS`, Firebase uids, set in the staging env, never in the repo). They see `GET /v1/operator/trials/{id}/report` (JSON and CSV), which holds aggregates only. Operators never see circles, assets or positions.
- **App:**
  - an outcome prompt after an incident closes;
  - 👍/👎 on digest lines;
  - a trial card (join, consent text, leave);
  - "Report a problem": free text, app version and device model, with no location.

**Tech stack:** as P7. No new dependencies.

---

## The budget (to be approved with the plan)
Per trial, over all enrolled asset-days. An "asset-day" is one asset with a live source for at least 12 h of that day.

| Line | Threshold | Why |
|---|---|---|
| False P0 | ≤ 1 per 300 asset-days | A false P0 wakes the whole family and the ladder; it must be rare enough to be trusted. |
| False P1 | ≤ 1 per 30 asset-days (≈ one a month per asset) | A family tolerates a monthly check; more and they mute the app. |
| Missed real events in guided tests | 0 | Every guided test that should ring must ring. |
| P0 push delivered | p95 ≤ 30 s from incident open | Design AC-1 (real alarm reaches the phone fast). |
| P0 acknowledged (first claim) | median ≤ 3 min | The ladder is working. |
| Fresh-fix coverage | ≥ 90% of asset-hours | Silence is measured, not hidden. |
| Digest usefulness | ≥ 50% of rated lines marked useful | Otherwise P2 rules are noise and go back to tuning. |
| Outcome coverage | ≥ 80% of closed P0/P1 reviewed | Without outcomes the budget is unmeasured, and the gate fails. |

A line with too little data (fewer than 100 asset-days, or fewer than 5 P0 guided tests) is **"not enough data"**, which is a fail, never a pass.

---

### Task 1: The budget as code
**Files:** `shared/rules/FalseAlarmBudget.kt` (thresholds), `shared/rules/BudgetGate.kt` (pure `judge(TrialMetrics): List<BudgetLine>`), `docs/research/false-alarm-budget.md` (published; generated table matches the code).
**Tests:** `BudgetGateTest`: `withinBudgetPasses`, `oneFalseP0Too_manyFails`, `missedGuidedTestFails`, `notEnoughDataIsAFail`, `testOutcomesNeverCountAsFalse`.
- [ ] RED → implement → GREEN. Commit `feat(shared): the false-alarm budget as code`.

### Task 2: Outcomes for every alarm
**Files:** V36 part 1 (`incident_reviews.outcome` enum, `digest_feedback(digest_id, item_index, member_id, useful, at)`), `IncidentService.review` (outcome, owner/guardian), `DigestRoutes` (`POST /v1/digests/{id}/feedback`).
**Tests:** `OutcomeTest`: `reviewTakesAnOutcome`, `testOutcomeIsNotAFalseAlarm` (also for verify-first: a `test` never counts), `digestFeedbackOncePerMemberAndLine`, `strangerCannotReview` (404).
- [ ] RED → implement → GREEN. Commit `feat(core): alarm outcomes and digest feedback`.

### Task 3: Trials, consent and leaving
**Files:** V36 part 2 (`trials`, `trial_circles(trial_id, circle_id, consent_at, consent_text_version, left_at, pseudonym_salt)`), `trials/TrialService.kt`, routes `GET /v1/trials/open`, `POST /v1/circles/{id}/trial` (owner only), `DELETE /v1/circles/{id}/trial`, operator `POST /v1/operator/trials`.
**Tests:** `TrialTest`: `ownerJoinsWithConsent`, `guardianCannotEnrol` (404), `leavingDeletesTrialMetrics`, `operatorAllowListOnly` (403 for any other member), `trialEndsByItself`.
- [ ] RED → implement → GREEN. Commit `feat(core): trial enrolment with consent`.

### Task 4: Nightly anonymous metrics
**Files:** V37 `trial_daily`, `jobs/TrialMetricsJob.kt` (runs 03:30 Algiers, once per day per trial, idempotent upsert).
**Tests:** `TrialMetricsTest`: `countsFalseAlarmsPerRuleAndTier`, `pushLatencyFromReceipts`, `coverageFromFixes`, `noIdentifiersStored` (columns contain no asset id, name, coordinates or reason text), `rerunIsIdempotent`, `onlyEnrolledCircles`.
- [ ] RED → implement → GREEN. Commit `feat(core): nightly anonymous trial metrics`.

### Task 5: The operator report
**Files:** `trials/TrialReport.kt` (aggregates `trial_daily` into `TrialMetrics`, runs `BudgetGate`), `GET /v1/operator/trials/{id}/report` (JSON) and `?format=csv`.
**Tests:** `TrialReportTest`: `reportJudgesAgainstTheBudget`, `csvHasOneLinePerBudgetLineAndRule`, `reportHasNoPersonalData`, `notEnoughDataSaysSo`.
- [ ] RED → implement → GREEN. Commit `feat(core): operator trial report against the budget`.

### Task 6: Problem reports
**Files:** V37 part 2 `problem_reports(id, circle_id, member_id, text, app_version, device, at)`, `POST /v1/feedback` (members), operator list in the report (text only, circle pseudonym).
**Tests:** `ProblemReportTest`: `memberReportsAProblem`, `textIsCappedAndNoLocationField`, `operatorSeesPseudonymNotCircle`.
- [ ] RED → implement → GREEN. Commit `feat(core): problem reports`.

### Task 7: App
**Files:** `OutcomePrompt` (after close: real / device problem / rule was wrong / it was a test / not sure), digest 👍/👎, `TrialCard` (open trials, consent text ar/fr/en, join, leave with confirmation), "Report a problem" in settings, `TrialLogic` (+ test).
**Tests:** `TrialLogicTest`: `consentTextInThreeLanguages`, `leaveNeedsConfirmation`, `outcomeChoicesMapToCodes`, `reportCarriesNoLocation`.
- [ ] RED → implement → GREEN. Commit `feat(app): outcomes, digest feedback, trial card, problem reports`.

### Task 8: Trial readiness on staging
**Files:** `docs/runbooks/P8-field-trial.md` (Arabic). It covers:
- recruitment and consent;
- device matrix: phones, 2 trackers, a vehicle, a herd if a breeder joins;
- the week-by-week schedule;
- the guided tests from every runbook P1–P7, with what to record;
- weekly check-in questions;
- data handling;
- leaving the trial;
- reading the report.

Also `ops/staging-checklist.md`: backups, the `HIRZ_OPERATORS` env, push health and a disk alert.
- [ ] Commit `docs: P8 field trial runbook`.

### Task 9: Rehearsal (simulated trial)
**Files:** `TrialRehearsalTest`. It runs a simulated trial on top of the P7 normal-month generator: 10 assets, 30 days, guided tests injected and outcomes recorded. The report it produces must pass the budget. A deliberately noisy variant must fail on the right line.
- [ ] Commit `test(core): a rehearsed trial passes the budget; a noisy one fails it`.

### Task 10: Gate
- [ ] CI green. Whole-branch review by a fresh agent, every finding fixed. `docs/evidence/P8-gate.md`. PR. **Merge only on the owner's approval.**

---

## P8 Exit Gate
- **Software (this PR):** CI green; the rehearsal passes the budget and the noisy rehearsal fails it; the review is clean.
- **Field (owner):** 5–10 households and owners for 4 weeks on staging; the operator report says **pass** on every budget line; the report and its CSV attached to `docs/evidence/P8-gate.md`.
- If a line fails: the rule behind it is tuned or demoted, and the trial is extended by two weeks for that line only.

## Out of scope (P9/P10)
Community network, buses, web dashboard (P9); ANPDP filing, store listing, privacy policy, marketing (P10).
