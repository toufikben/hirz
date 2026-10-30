# P5 gate — evidence

## Automated (CI)
- **Profiles** (`ProfilesTest` 4, `ProfileRoutesTest` 5): a person's type and condition decide which rules run and at
  what tier. A default-P0 rule can never be switched off. A guardian may only raise a tier; the owner owns the
  condition, birth date and anything lowered. The engine uses the profile's tier. The condition never appears in a push,
  an SMS or the public share page; it reaches the police sheet only when the owner ticks it.
- **Rules:**
  - `HAZARD_PROXIMITY` (`HazardTest` 4): P0 after one good fix for autism, P1 after two otherwise; fixes coarser than
    25 m neither count nor reset. Forbidden places keep `FORBIDDEN_ENTRY` and three readings.
  - `NIGHT_EXIT` (`NightExitTest` 5): leaving home 22:00–06:00 after two readings; a night exception (Fajr at the
    mosque) holds the alarm unless the outing goes 300 m past the excepted place.
  - `RUN_AWAY_SPEED` (`RunAwayTest` 5): autism only, two good fixes in a row away from home at 9 km/h or more.
  - `PAST_ADDRESS_HEADING` (`PastAddressTest` 3): dementia only, four steps in a row closer, 300 m closer in all.
  - `CURFEW` and `COASTAL_NIGHT` (`TeenRulesTest` 4): once per night 15 min after the curfew outside home and
    relatives; the shore only at night.
- **Age transition** (`AgeTransitionTest` 4): owner told once at 15; at 18 sources pause (ingest 403
  `consent_required`) until the person consents on their own phone.
- **App** (`ProfileLogicTest` 4): no off switch on P0 rows, guardian tier choices only raise, every new reason reads
  in ar/fr/en and never names a condition, the elder's companion shows only SOS and "I'm fine".
- **Scenario library** (`PeopleScenariosTest`): 13 replay scenarios, a positive and a negative for each P5 rule
  (AC-19), and a confusion matrix running every scenario under five person profiles (child, teen, elder,
  elder + dementia, autism). Any P5 rule raised under a profile that lacks it fails the build; the matrix is printed
  to the test report (stdout of `PeopleScenariosTest`).
- **AC-20:** no change under `escalation/` or `notify/`; under `incidents/` only the police sheet's opt-in condition
  line. Findings carry the profile's tier and the existing incident path does the rest.

## Family trial
**Not run yet.** It needs one family with an elder or an autistic child for two weeks. Steps:
`docs/runbooks/P5-family-trial.md`. Results (guided tests, every alert real or false, false alarms per rule per 1000
asset-days, the family's view of the simple mode) go here.

## Out of P5
`LEASH_BREAK` and running from the guardian's phone (P6, BLE), `PACING_LOOP` and `UNUSUAL_STOP` (P7), Darija voice,
medical alerts.
