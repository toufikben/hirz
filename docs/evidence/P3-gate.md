# P3 gate — evidence

## Automated (CI)
- **End to end, every push** (`e2e` job): a simulated GT06 logs in to a real Traccar 6.16.0, which forwards to a real
  Core on PostGIS. Pinned by `EndToEndIT`:
  - the first report after a claim activates it and the fix is stored (`simulatedTrackerAppearsInCore`);
  - a GT06 0x16 SOS alarm opens a P0 SOS incident (`simulatedSosOpensIncident`);
  - source → Core delay p95 under 5 s in CI over 13 fixes (`forwardLatencyUnder5sInCi`; the field target is 30 s).
- The e2e test found a real defect: Traccar forwards a position before storing it (`position.id = 0`), so every fix
  after the first was dropped as a duplicate. Reports are now keyed by device + device time + alarm.
- Server: tracker claims and proof of control (`TrackerTest`, 10), the mapper (`TraccarMapperTest`, 6), the REST
  client and backup sync (`TraccarAdminTest`, `TraccarSyncTest`), the catalogue (`DeviceCatalogTest`,
  `DeviceModelsTest`), shared-custody alerts (`SharedCustodyAlertsTest`, 4).
- Infra: the Traccar config checker refuses a public forward URL, a missing secret header or retries off; the staging
  compose publishes only 80, 443, 5013, 5023 and pins every image. The Core image builds for amd64 and arm64.

## Staging and device trial
**Not run yet.** They need the owner's Task 0: the Oracle Cloud VM (international card), a host name, and the two
trackers with SIMs. Steps: `docs/runbooks/P3-staging.md`, then `docs/runbooks/P3-device-trial.md`. Results (AC-1 p95,
SMS replies verbatim, firmware strings, failures) go here.
