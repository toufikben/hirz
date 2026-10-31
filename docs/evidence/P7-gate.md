# P7 gate — evidence

## Automated (CI 41219780564, all jobs green)
- **Routines** (`RoutineTest`): templates (school Sun–Thu 08:00 / 16:30, mosque, lessons, pasture day, barn night) on
  the circle's own places; viewers cannot edit; every change is audited.
- **`MISSED_ARRIVAL`** (`MissedArrivalTest`): P1 ten minutes after arrive-by with no good fix inside the place. Not on
  Fridays, holidays or the circle's declared days off (Algerian school calendar 2026–2028, estimated), not when the
  phone is silent (that is `SILENT_SOURCE`), not during a handover that mutes exits; once a day.
- **After school and on the way** (`AfterSchoolTest`): 15 min still at an unknown place after dismissal is
  `STATIONARY_AFTER_DISMISSAL` P1; 10 min still on the way is `UNUSUAL_STOP` P2. Waiting at the gate, grandma's house,
  a slow walk and coarse fixes say nothing.
- **`PACING_LOOP`** (`PacingTest`): an elder circling one block (≥ 1 km walked within 250 m) is P2; a straight walk
  and laps in the garden are not.
- **Night by the sun and Ramadan** (`SunAndRamadanTest`): night from sunset to sunrise on request; Ramadan evenings
  21:30–23:30 are not night exits unless the owner says so; routines shift by their Ramadan offset.
- **Learned places** (`LearningTest`): a place dwelt at on 3 days in 30 is learned; one visit, or visits older than
  the window, teach nothing. A learned place only adds "toward a place never visited" to an existing finding;
  learning never raises (`learningNeverRaises`). CI caught a defect here: visits to one spot on different days were
  merged into one stay; a gap in the track now ends a stay.
- **Verify first** (`VerifyFirstTest`): after 3 false-alarm reviews of one rule on one asset in 30 days, its P1
  starts with a quiet push and escalates as usual; P0 is never affected (`historyLowersP1NotP0`); the owner can turn
  it off; old reviews expire.
- **Evening digest** (`DigestTest`): at 20:00 the day's P2 findings become one digest per circle and one quiet push
  with no tier, rule or place; nothing on a quiet day; never twice; condition rules show as `PERSON_ALERT`.
- **App** (`RoutineLogicTest`): routine labels (week from Sunday), templates per asset type, digest lines never name a
  condition, the new reasons in ar/fr/en, a quiet incident push does not ring and a digest is a notice.
- **Scenarios (AC-19)** (`RoutineScenariosTest`, `src/test/resources/routines`): 9 replays, a positive and a negative
  for each of `MISSED_ARRIVAL`, `STATIONARY_AFTER_DISMISSAL`, `UNUSUAL_STOP`, `PACING_LOOP`. The P4–P6 libraries stay
  green.

## The false-alarm budget
`NormalMonthTest.normalMonthWithinBudget`: ten people (seven children, a teen, two elders) through 9 Feb – 10 Mar 2027:
school days, weekends, all of Ramadan with tarawih evenings, Eid al-Fitr, Saturday visits to grandma, GPS drift at
night and a phone dead every night. It must ring no P1 and no P0; quiet P2 findings go to the digest.

The first run rang 328 times, all `PLACE_EXIT` P1. Two defects, both fixed with tests (`ExpectedExitTest` 7):
- Leaving home for school and leaving school after dismissal rang every day. On a day a routine runs, leaving home in
  the two hours before arrive-by (and up to an hour after, for a late confirmation) and leaving the routine's place
  from 15 min before dismissal are left to the routine rules. Other exits ring as before.
- A phone that came back the next morning confirmed the walk home at 07:15, outside the window. An expected trip is
  also judged at the reading before the confirming one. A guardian's outing (P6) now mutes the asset's place exits,
  as a Saturday visit with mum should.

The month now passes at zero P1 and zero P0.

Kept on purpose: leaving home by day with no routine and no outing still rings `PLACE_EXIT` (pinned since P5 by
`leavingHomeAt2pmIsPlaceExitAsBefore`). A family that does not want that sets the home's active hours.

## Family trial
**Not run yet.** It needs one family for a school month (two children with the school routine). Results (missed
arrivals that were real, false alarms per rule per 100 child-days, digest usefulness) go here.
