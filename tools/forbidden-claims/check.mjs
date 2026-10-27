// Fails when user-facing text makes a claim Hirz must never make (design §8.7, v2 amendment §د).
// Usage: node tools/forbidden-claims/check.mjs "<glob>" [...]
import { readFileSync, globSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const PHRASES = JSON.parse(readFileSync(new URL('./phrases.json', import.meta.url), 'utf8'));

/** Strips Arabic diacritics and tatweel, folds alef/yeh variants and apostrophes, lowercases. */
export function normalize(text) {
  return text
    .replace(/[ً-ٰٟـ]/g, '')
    .replace(/[أإآ]/g, 'ا')
    .replace(/ى/g, 'ي')
    .replace(/[’‘`´]/g, "'")
    .toLowerCase();
}

const NORMALIZED = PHRASES.map((phrase) => [phrase, normalize(phrase)]);

/** Returns the forbidden phrases (as listed in phrases.json) found in [text]. */
export function findClaims(text) {
  const haystack = normalize(text);
  return NORMALIZED.filter(([, needle]) => haystack.includes(needle)).map(([phrase]) => phrase);
}

/** Returns one `path:line: phrase` entry per hit, in file and line order. */
export function scanFiles(paths) {
  const hits = [];
  for (const path of paths) {
    readFileSync(path, 'utf8').split(/\r?\n/).forEach((line, index) => {
      for (const phrase of findClaims(line)) hits.push(`${path}:${index + 1}: ${phrase}`);
    });
  }
  return hits;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const patterns = process.argv.slice(2);
  if (patterns.length === 0) {
    console.error('usage: check.mjs "<glob>" [...]');
    process.exit(2);
  }
  const files = patterns.flatMap((pattern) => globSync(pattern)).sort();
  if (files.length === 0) {
    console.error(`no file matched: ${patterns.join(" ")}`);
    process.exit(1);
  }
  const hits = scanFiles(files);
  for (const hit of hits) console.error(hit);
  if (hits.length > 0) process.exit(1);
  console.log(`no forbidden claims in ${files.length} file(s)`);
}
