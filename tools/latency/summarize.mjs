// Summarizes alert latency from incident exports (GET /v1/incidents/{id}) for the P1 gate.
// Both ends of each measurement come from the server clock: incident opened → receipt recorded,
// so phone clock skew cannot distort it. Usage: node tools/latency/summarize.mjs <incidents.json>...
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

/** Nearest-rank percentile of [values] (no interpolation); null when empty. */
export function percentile(values, p) {
  if (values.length === 0) return null;
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.max(0, Math.ceil((p / 100) * sorted.length) - 1)];
}

const stats = (values) => ({ count: values.length, p50Ms: percentile(values, 50), p95Ms: percentile(values, 95), maxMs: values.length ? Math.max(...values) : null });

export function summarize(incidents) {
  const delivered = [];
  const opened = [];
  let undelivered = 0;
  for (const inc of incidents) {
    for (const d of inc.deliveries ?? []) {
      if (d.deliveredAtMs != null) delivered.push(d.deliveredAtMs - inc.openedAtMs);
      else undelivered += 1;
      if (d.openedAtMs != null) opened.push(d.openedAtMs - inc.openedAtMs);
    }
  }
  return { incidents: incidents.length, delivered: stats(delivered), opened: stats(opened), undelivered };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const files = process.argv.slice(2);
  if (files.length === 0) { console.error('usage: summarize.mjs <incident.json>...'); process.exit(2); }
  // Each file holds one incident or an array of incidents (e.g. the SQL export in the P1 runbook).
  const incidents = files.flatMap((f) => [JSON.parse(readFileSync(f, 'utf8'))].flat());
  console.log(JSON.stringify(summarize(incidents), null, 2));
}
