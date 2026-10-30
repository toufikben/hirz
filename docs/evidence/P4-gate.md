# P4 gate — evidence

## Automated (CI)
- **End to end, every push** (`e2e` job): a simulated GT06 car is claimed and proved, armed through the API, then sends
  an ACC-on heartbeat and two fixes at 30 km/h about 200 m and 400 m away through a real Traccar 6.16.0. Core opens
  `ARMED_MOVEMENT` (`EndToEndIT.armedVehicleMovedOpensIncident`). The skip guard now expects 4 `EndToEndIT` tests.
- **AC-22 replay** (`VehicleScenariosTest`, 8 scenarios over the real HTTP API with a moving clock): armed then driven
  away, authorized drive without alarm, parked GPS drift without alarm, towed with ignition off, power cut while armed,
  jamming in a garage vs. during a theft, auto-arm after parking, and an authorization ending mid-drive.
- **Modes** (`VehicleModesTest`, 9): resolution order, auto-arm 10 min after ignition off, a manual disarm held until
  the next parking, no disarm or authorization while a P0/P1 incident is open (409 `incident_open`).
- **Rules** (`VehicleRulesTest` 10, `VehicleRulesHttpTest` 3): movement measured from the armed position net of
  accuracy, P0 at speed or 500 m, towing, tilt and door only when armed, power cut, jamming and antenna cut P1 armed /
  P2 otherwise, and an open incident's tier raised (P1 → P0) with the ladder restarted.
- **Engine stop** (`RemoteEngineTest`, 9): refused for a guardian, without a theft incident, on a model without a
  relay, with the device offline, with a fix over 60 s old, above 20 km/h, and when Traccar queues instead of sends.
  A sent stop is recorded in `engine_commands`, the audit log and the incident. Resume needs only the owner.
- **App** (`VehicleLogicTest`, 3): the mode line says why and since when; the stop is offered only to the owner during
  a theft on a relay-capable tracker; every refusal and vehicle reason reads plainly in ar/fr/en.

## Defects found on the way
- Rules ran after the ignition tracker, so a report was judged against the mode it had just changed, and auto-arm never
  caught an engine start. The tracker now runs after the rules engine.
- Traccar has no API to drop a queued command. A queued stop gets a resume queued behind it (net effect: the engine
  runs), and Core asks Traccar 6.7+ not to queue at all (`noQueue`).

## Vehicle trial
**Not run yet.** It needs the owner's car with a GT06N wired to ACC, a relay for the engine stop, and P3 staging.
Steps: `docs/runbooks/P4-vehicle-trial.md`. Results (arm → alarm times over 5 runs, the 30-minute authorized drive,
power cut, engine stop stationary only, refusal texts verbatim, failures) go here.

## Out of P4
BLE auto-arm and `KEYLESS_NO_OWNER` (P6), `LEFT_IN_VEHICLE` (P9), fuel drop, siren, bus route mode.
