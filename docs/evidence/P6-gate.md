# P6 gate — evidence

## Automated (CI)
- **Livestock profile and herds** (`ProfilesTest.livestockProfileDefaults`, `HerdTest` 3): the livestock profile has barn night exit, herd night move, dispersion, truck speed, collar cut and stillness, with pasture exit at P2. It is late-tolerant and its silence threshold is 6 h. Herds group one circle's livestock under one leader, and an animal belongs to one herd at a time.
- **Store-and-forward** (`LateReportsTest` 4): fixes up to 72 h old are judged at their own time. A late finding says "reported N min late" and is capped at P1. People still ignore stale fixes.
- **Livestock rules** (`HerdRulesTest` 8):
  - `HERD_NIGHT_MOVE`: the leader leaves the barn at 2 am.
  - Market day: moving the herd on market day raises nothing.
  - `HERD_DISPERSION`: wolves scatter the herd, while normal grazing spread stays quiet.
  - `ANIMAL_STILL`: a head still for 6 h raises it, but too few fixes count as silence, not stillness.
  - `VEHICLE_SPEED_UNEXPLAINED`: a head carried on a truck.
  - `COLLAR_CUT`: raised from the device's removal alarm.
- **Eid al-Adha and market days** (`EidTest` 4): for the 14 days before Eid, livestock P2 rules rise to P1. The owner's announced date overrides the built-in estimate, the calendar never creates a P0, and people are never affected.
- **BLE tags and the relay** (`BleTest` 6, `BleFramesTest` 2):
  - A sighting becomes a fix for the tag's asset. The relay is never revealed to the tag's circle.
  - Unknown tags and repeats within 30 s are dropped.
  - A weak sighting never confirms a boundary.
  - A tag registers only with proof of possession.
  - Eddystone-UID/TLM and iBeacon frames are parsed.
- **Shepherd relay** (`RelayLogicTest` 4): only the circle's own tags leave the phone. Sightings are throttled per tag and queued in order while offline. Scanning runs 10 s of every minute.
- **`LEASH_BREAK`** (`LeashTest` 4): fires after 60 s and 50 m apart, only during an outing the guardian started. It is P0 for autism, and outings end by themselves.
- **Owner away and keyless** (`PresenceArmTest` 4): a tagged car arms 2 min after the family's phones stop hearing it. A start with no family phone near raises `KEYLESS_NO_OWNER`. Cars without a tag keep the P4 behavior.
- **App** (`HerdLogicTest` 3): livestock and presence reasons read in ar/fr/en, late reports say how late, and the market window is built and labelled correctly.
- **Scenarios:** 3 new livestock replay scenarios (barn at 2 am, truck, grazing). The confusion matrix now includes a livestock row and the truck-speed rule.
- **LoRaWAN:** research only (`docs/research/res-lorawan.md`). It is deferred to a bench trial.

## Whole-branch review
A fresh review found four critical defects, all fixed with tests (CI 41199985433):
- Late findings were capped at P1 for every profile, so an autistic child's run-away P0 uploaded three minutes late
  rang as P1. Only store-and-forward profiles get the late treatment now.
- A shepherd's offline buffer was uploaded with the phone's current position, placing the whole herd in the village
  hours later. Each sighting now carries the fix it was heard at, within 60 s, or is dropped.
- Any Hirz phone could post sightings of another circle's tag and move that animal. A relay now carries only its own
  circle's tags, with sane fixes.
- KEYLESS_NO_OWNER rang on every start of a tagged car whose family never relayed, and owner-away arming never let go.
  Keyless now needs an armed car whose family relayed in the last day; a later family sighting releases the latch;
  scans are filtered so Android delivers them with the screen off.
Also fixed: tags are never silent sources; the leash needs a fresh fix; one herd alarm per night; livestock night ends
at 04:30; stillness only in a pasture; an old Eid date does not erase next year's season; sightings purged after a week.
Accepted risk: proof of possession at tag registration is the phone's own report (no trusted hardware).

A second independent review (Codex could not run: usage limit) found three more critical defects, fixed with tests
(CI 41203403206):
- The herd's once-per-night alarm silenced a second theft the same night. It now holds only while its incident is open
  and re-arms when the family closes it.
- LEASH_BREAK could not fire for a tagged child who went silent, and the outing stopped pinging when its card scrolled
  away. A tag the guardian's phone stopped hearing now raises "lost contact"; the outing runs in its own session and
  sends the tags it hears.
- Owner-away arming tripped on the owner's own errands. It now waits 6 min, and a start gets 90 s for the owner's
  phone to be heard before it counts as a theft.
Also: a collar a few minutes late keeps its P0 (demotion only after 30 min); coarse fixes cannot break the leash; only
phones an owner or guardian allowed can relay tags.

## Breeder trial
**Not run yet.** It needs one breeder for two weeks:
- a GSM leader collar;
- 5–10 Eddystone or iBeacon collars;
- the shepherd's phone as the relay.

Steps: `docs/runbooks/P6-breeder-trial.md`. Results (guided tests, false alarms per rule per 1000 head-days, coverage holes and late uploads, battery life) go here.

## Out of P6
- LoRaWAN deployment (bench trial first).
- Sunset/sunrise night and learned routines (P7).
- Bus route mode and `LEFT_IN_VEHICLE` (P9).
- Closed BLE networks (never).
