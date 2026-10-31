# P8 gate — evidence

## Software (this PR, CI 41237157081, all jobs green)
- **The budget as code** (`BudgetGateTest` 6, `BudgetDocTest`): 8 lines.
  - Lines: false P0 ≤ 1/300 asset-days, false P1 ≤ 1/30, 0 missed guided tests, P0 delivered p95 ≤ 30 s, P0 claimed median ≤ 3 min, coverage ≥ 90%, digest useful ≥ 50%, outcome coverage ≥ 80%.
  - Not enough data is a fail on every line.
  - The published table (`docs/research/false-alarm-budget.md`) is checked against the code.
- **Outcomes** (`OutcomeTest` 5): every closed alarm can be marked real, device problem, wrong rule, test or not sure.
  - Only device and rule problems count as false alarms, so a test never feeds verify-first.
  - Digest lines take 👍/👎, and the last vote counts.
  - Owners and guardians get the list of closed alarms they have not judged yet.
- **Trials** (`TrialTest` 8):
  - Operators come from a server allow-list (`HIRZ_OPERATORS`).
  - The owner joins with the current consent text.
  - Leaving deletes the circle's trial data and its problem reports from the trial, and forgets its pseudonym. An ended trial can still be left.
  - Guided tests are accepted only for rules that alert on that asset, and not while such an incident is open.
- **Metrics** (`TrialMetricsTest` 11): one row per asset-day, keyed by pseudonyms, with no identifiers stored. Each night:
  - every day is recomputed until 14 days after the end;
  - nothing from before consent is counted;
  - undelivered or unclaimed P0s are counted;
  - "test" counts only against an announced guided test, and "unsure" is not a decided outcome.
- **Operator report** (`TrialReportTest` 6): JSON and CSV, aggregates only.
  - A P0 that never arrived fails delivery.
  - Coverage counts silent days.
  - Problem reports appear under the circle's pseudonym.
- **App** (`TrialLogicTest` 5):
  - consent text in ar/fr/en;
  - leaving takes two taps;
  - outcome choices;
  - only rules that alert are offered as guided tests;
  - a problem report carries no location.
- **Rehearsal** (`TrialRehearsalTest`): ten people over ten days through the real API.
  - Covers hourly fixes, six guided SOS tests (pushed, received, claimed, closed by two guardians, reviewed) and rated digests.
  - The report passes every line.
  - Four false P1s make it fail on `false_p1` and nothing else.
- **Runbook:** `docs/runbooks/P8-field-trial.md` (Arabic), plus `ops/staging-checklist.md`.

## Whole-branch review
A fresh review found two critical defects, both fixed with tests:
- Each day's metrics were frozen after one night, so a P0 judged false two days later was never counted. Now every day is recomputed until 14 days after the trial ends.
- A family could clear false alarms and fill outcome coverage by answering "test" or "unsure". Now "test" counts only against an announced guided test, and neither answer counts as decided; both are shown in the report.

Also fixed:
- pre-consent days;
- a leave racing the nightly job;
- undelivered P0s hidden from latency;
- dead devices hidden from coverage;
- guided tests passed by quiet or P2 incidents;
- tests offered for rules that cannot ring;
- rejoin linkability and problem reports kept after leaving;
- no way to leave once the trial ended;
- the outcome prompt shown again for alarms already answered;
- minimum samples per line;
- CSV quoting.

Accepted:
- Pseudonyms protect against the operator API, not against database administrators.
- Coverage uses hourly buckets.

## Field (owner)
**Not run yet.** It needs 5–10 households and owners for 4 weeks on staging (`docs/runbooks/P8-field-trial.md`). The
operator report (JSON and CSV) at the end is attached here, and the gate passes only if every line says **pass**.
