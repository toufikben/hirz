// Validates a Hirz Traccar config (template or rendered). Parsing lessons come from the KidGuard v2
// lab checker: both quote styles are valid Spring XML, the last definition of a key wins, and a
// duplicated key is an error. Every message names the rule, not only the key.
import { resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { readFile } from 'node:fs/promises';

const ALLOWED_PROTOCOLS = ['gt06', 'osmand'];

const RULES = {
  'database.registerUnknown': (v) => v === 'false'
    || 'must be false: only whitelisted devices may report (design §8)',
  'web.address': (v) => v === '127.0.0.1'
    || 'must bind localhost; the web API is reached through the reverse proxy only (design §8)',
  'web.port': (v) => v === '8082' || 'expected 8082',
  'protocols.enable': (v) => {
    const listed = v.split(',').map((p) => p.trim()).filter(Boolean);
    const unknown = listed.filter((p) => !ALLOWED_PROTOCOLS.includes(p));
    if (unknown.length > 0) return `only ${ALLOWED_PROTOCOLS.join(',')} may be open in P0; remove ${unknown.join(',')}`;
    if (listed.length === 0) return 'at least one protocol must be enabled';
    return true;
  },
  'geocoder.enable': (v) => v === 'false'
    || 'must be false: the geocoder blocks alert delivery and leaks an address resolved from the coordinates (v2 §9.12)',
  'database.password': (v) => v.length > 0 || 'must not be empty (H2 ships with sa and no password)',
  'logger.console': (v) => v === 'true' || 'expected true',
};

const FORBIDDEN_KEYS = ['web.path', 'web.debug', 'web.mcp.enable', 'web.register', 'web.registration'];

export function validateConfig(xml, { allowPlaceholders = false } = {}) {
  const errors = [];
  const entries = new Map();
  const duplicates = new Set();
  const entryRe = /<entry\s+key=(['"])([^'"]+)\1\s*(?:\/>|>([^<]*?)<\/entry>)/g;
  for (const match of xml.matchAll(entryRe)) {
    const key = match[2];
    if (entries.has(key)) duplicates.add(key);
    entries.set(key, (match[3] ?? '').trim());
  }

  for (const key of duplicates) errors.push(`${key} is defined twice in one config`);

  for (const [key, check] of Object.entries(RULES)) {
    if (!entries.has(key)) { errors.push(`${key} is required`); continue; }
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
  const { errors } = validateConfig(xml, { allowPlaceholders: allow });
  if (errors.length > 0) {
    for (const e of errors) console.error(`FAIL ${e}`);
    process.exit(1);
  }
  console.log(`config ok: ${fileURLToPath(target)}`);
}
