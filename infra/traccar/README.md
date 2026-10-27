# Traccar for Hirz

Hirz uses Traccar **only as the device gateway**. Zones, rules and notifications live in Hirz Core, not in Traccar.

- Version: **6.16.0** (pinned). Never run anything below 6.12, because of CVE-2025-68930 (WebSocket origin check).
- Config: `hirz.template.xml`. Render `${HIRZ_TRACCAR_DB_PASSWORD}` before use, then check it:
  `node infra/traccar/tools/check-config.mjs <rendered.xml>`. The template itself is checked with `--template`.

| Port | Use |
|---|---|
| 8082 | Web/REST API (bound to 127.0.0.1; reached through the reverse proxy only) |
| 5023 | GT06 family devices (TCP) |
| 5055 | OsmAnd protocol (HTTP) |

## Run locally

- **Docker:**
  `docker run -d --name traccar -p 8082:8082 -p 5023:5023 -p 5055:5055 -v <rendered.xml>:/opt/traccar/conf/traccar.xml traccar/traccar:6.16.0`
- **Without Docker:**
  1. Unzip `traccar-other-6.16.0.zip`.
  2. Copy the rendered config to `conf/traccar.xml`.
  3. Run `java -jar tracker-server.jar conf/traccar.xml` with **JDK ≥ 21**. JDK 25.0.2 was measured working in the KidGuard v2 lab; JDK 17 does not start it.

## Lessons carried from KidGuard v2 (measured, see docs/legacy/v2)

- `geocoder.enable` must stay `false`. When it is on, alert delivery waits on the geocoder (up to 114 s measured), and the geocoder leaks an address resolved from the coordinates.
- WKT in Traccar is `lat lon`, while Leaflet and GeoJSON use `[lng, lat]`.
- `notificator.timeThreshold` suppresses older events. `event.forward.*` runs before that check.
