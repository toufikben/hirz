// Validates a Hirz Traccar config (template or rendered). Parsing lessons come from the KidGuard v2
// lab checker: both quote styles are valid Spring XML, the last definition of a key wins, and a
// duplicated key is an error. Every message names the rule, not only the key.
import { resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { readFile } from 'node:fs/promises';

// Every protocol a catalogue model uses (shared/.../devices/DeviceCatalog.kt), and nothing else.
const ALLOWED_PROTOCOLS = ['gt06', 'h02', 'osmand'];

const RULES = {
  'database.registerUnknown': (v) => v === 'false'
    || 'must be false: only whitelisted devices may report (design §8)',
  'web.address': (v) => v === '127.0.0.1'
    || 'must bind localhost; the web API is reached through the reverse proxy only (design §8)',
  'web.port': (v) => v === '8082' || 'expected 8082',
  'protocols.enable': (v) => {
    const listed = v.split(',').map((p) => p.trim()).filter(Boolean);
    const unknown = listed.filter((p) => !ALLOWED_PROTOCOLS.includes(p));
    if (unknown.length > 0) return `only ${ALLOWED_PROTOCOLS.join(',')} may be open ; remove ${unknown.join(',')}`;
    if (listed.length === 0) return 'at least one protocol must be enabled';
    return true;
  },
  'geocoder.enable': (v) => v === 'false'
    || 'must be false: the geocoder blocks alert delivery and leaks an address resolved from the coordinates (v2 §9.12)',
  'database.password': (v) => v.length > 0 || 'must not be empty (H2 ships with sa and no password)',
  'logger.console': (v) => v === 'true' || 'expected true',
  'forward.type': (v) => v === 'json' || 'must be json: Core reads the JSON position forward',
  'forward.url': (v) => PRIVATE_CORE.test(v) && v.endsWith('/v1/traccar/positions')
    || 'must be http://core:8080/v1/traccar/positions (or localhost): positions never leave the private network',
  'event.forward.url': (v) => PRIVATE_CORE.test(v) && v.endsWith('/v1/traccar/events')
    || 'must be http://core:8080/v1/traccar/events (or localhost): events never leave the private network',
  'forward.header': (v) => /^Authorization: Bearer \S+$/.test(v) || 'must be "Authorization: Bearer <HIRZ_TRACCAR_SECRET>"',
  'event.forward.header': (v) => /^Authorization: Bearer \S+$/.test(v) || 'must be "Authorization: Bearer <HIRZ_TRACCAR_SECRET>"',
  'forward.retry.enable': (v) => v === 'true' || 'must be true: a position lost on a Core restart is retried',
};

/** Core on the Docker network or this host; never a public address. */
const PRIVATE_CORE = /^http:\/\/(core|localhost|127\.0\.0\.1):8080\//;

const FORBIDDEN_KEYS = ['web.path', 'web.debug', 'web.mcp.enable', 'web.register', 'web.registration'];

/**
 * [container]: the config runs inside Docker, where Traccar must bind 0.0.0.0 to be reachable by Core on the private
 * network; the compose file (checked by infra/staging/staging.test.mjs) never publishes port 8082.
 */
export function validateConfig(xml, { allowPlaceholders = false, container = false } = {}) {
  const errors = [];
  const entries = new Map();
  const duplicates = new Set();
  // Comments are invisible to Traccar, so they must be invisible here too.
  const body = xml.replace(/<!--[\s\S]*?-->/g, '');
  const entryRe = /<entry\s+key\s*=\s*(['"])([^'"]+)\1\s*(?:\/>|>([^<&]*?)<\/entry>)/g;
  let parsed = 0;
  for (const match of body.matchAll(entryRe)) {
    parsed += 1;
    const key = match[2];
    if (entries.has(key)) duplicates.add(key);
    entries.set(key, (match[3] ?? '').trim());
  }
  // Anything we cannot read (CDATA, entities, odd markup) could carry a value Traccar would load.
  const declared = (body.match(/<entry\b/g) ?? []).length;
  if (declared !== parsed) errors.push(`${declared - parsed} unreadable entry(ies): use plain values without CDATA or entities`);

  for (const key of duplicates) errors.push(`${key} is defined twice in one config`);

  for (const [key, check] of Object.entries(RULES)) {
    if (!entries.has(key)) { errors.push(`${key} is required`); continue; }
    if (key === 'web.address' && container && entries.get(key) === '0.0.0.0') continue;
    const result = check(entries.get(key));
    if (result !== true) errors.push(`${key}: ${result}`);
  }

  for (const key of FORBIDDEN_KEYS) {
    if (entries.has(key)) errors.push(`${key} must not appear: self-registration and debug surfaces stay off`);
  }

  if (!allowPlaceholders && xml.includes('${')) errors.push('unrendered placeholder present');
  return { entries, errors };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  const templateIdx = args.indexOf('--template');
  const allow = templateIdx !== -1 || args.includes('--allow-placeholders');
  const pathArg = templateIdx !== -1 ? args[templateIdx + 1] : args.find((a) => !a.startsWith('--'));
  const target = pathArg
    ? pathToFileURL(resolve(process.cwd(), pathArg))
    : new URL('../hirz.template.xml', import.meta.url);
  const xml = await readFile(target, 'utf8');
  const { errors } = validateConfig(xml, { allowPlaceholders: allow, container: args.includes('--container') });
  if (errors.length > 0) {
    for (const e of errors) console.error(`FAIL ${e}`);
    process.exit(1);
  }
  console.log(`config ok: ${fileURLToPath(target)}`);
}
