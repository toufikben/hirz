# Staging checklist before a field trial (P8)

Tick every line on the staging host before the first family joins. Secrets live only in the host's environment and
`C:\Users\Administrator\hirz-secrets\`, never in the repository.

- [ ] **Version:** the trial build of `main` is deployed. `GET /health` answers, and the image tag is recorded in `docs/evidence/P8-gate.md`.
- [ ] **Migrations:** Flyway is at V38 or later (`SELECT max(version) FROM flyway_schema_history`).
- [ ] **Operators:** `HIRZ_OPERATORS` holds the operator's Firebase uid and nothing else; a second account gets 403 on `/v1/operator/trials`.
- [ ] **Backups:** the nightly `pg_dump` ran in the last 24 h, and one restore was tested on a scratch database.
- [ ] **Disk:** at least 30% free on the database volume. An alert fires at 80% used.
- [ ] **Push:** FCM credentials are set. A test SOS reaches two phones, with `delivered_at` recorded in `deliveries`.
- [ ] **Traccar:** the trial trackers report, and the forwarder secret matches `HIRZ_TRACCAR_SECRET`.
- [ ] **Clock:** the host runs NTP, and its time zone is UTC (rules use Africa/Algiers internally).
- [ ] **Jobs:** the logs show the digest at 20:00, learning at night and trial metrics after 03:30.
- [ ] **Retention:** the circle retention (default 30 days) is unchanged; the trial adds no raw data, only aggregates.
- [ ] **Rollback:** the previous image tag is noted, and `docker compose up -d` with it was rehearsed once.
