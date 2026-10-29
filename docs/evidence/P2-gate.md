# P2 gate — evidence

## Automated (CI)
- Server: every P2 test class green on `p2/escalation`, including `EscalationScenariosTest` (7 replay scenarios in
  `server/src/test/resources/escalation/`: nobody answers P0, second guardian claims, contest restarts, single-guardian
  close expires, found safe with proof, contact replies 1, gateway silent).
- App: `IncidentDetailLogicTest`, `RemoteCommandsTest`, `GatewayLogicTest`, `LiveUrlTest` green; `play` and `gateway`
  debug builds; CI asserts that only the gateway build declares SMS permissions.
- Content checks by test: no code word in push, SMS, incident list, sheet or police pack; SMS text has no coordinates
  and fits 2 parts in ar/fr/en; the post text has no coordinates; the sheet PDF prints no coordinates.

## Device trial
**Not run yet.** It needs P1 Task 0 (Firebase project, secrets folder, Supabase, cloudflared) plus a gateway phone with a
SIM. Steps: `docs/runbooks/P2-trial.md`. Results (times, screenshots, failures) go here.
