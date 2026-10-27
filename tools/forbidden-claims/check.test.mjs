import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { findClaims, scanFiles } from './check.mjs';

test('flagsArabicWithDiacritics', () => {
  assert.deepEqual(findClaims('حِرز يَمنعُ الاختطافَ'), ['يمنع الاختطاف']);
});

test('flagsFrench', () => {
  assert.deepEqual(findClaims("Hirz Empêche l'enlèvement"), ["empêche l'enlèvement"]);
});

test('flags a typographic apostrophe the same as a straight one', () => {
  assert.deepEqual(findClaims('Empêche l’enlèvement'), ["empêche l'enlèvement"]);
});

test('flagsEnglishCaseInsensitive', () => {
  assert.deepEqual(findClaims('Tamper-Proof watch'), ['tamper-proof']);
});

test('passesHonestTagline', () => {
  assert.deepEqual(findClaims('يساعدك على الاكتشاف المبكر والإبلاغ السريع'), []);
});

test('reportsFileAndLine', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'claims-'));
  const file = join(dir, 'strings.xml');
  await writeFile(file, '<resources>\n  <string name="a">ok</string>\n  <string name="b">guaranteed live location</string>\n</resources>\n');
  assert.deepEqual(scanFiles([file]), [`${file}:3: guaranteed live location`]);
});

test('the CLI fails when the globs match no file', async () => {
  const { spawnSync } = await import('node:child_process');
  const script = new URL('./check.mjs', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');
  const run = spawnSync(process.execPath, [script, 'no/such/dir/**/strings.xml'], { encoding: 'utf8' });
  assert.equal(run.status, 1, run.stderr);
  assert.match(run.stderr, /no file matched/);
});

// Final review (Important #2): Android string escaping and Unicode variants must not hide a claim.
test('flags the escaped apostrophe Android strings.xml requires', () => {
  assert.deepEqual(findClaims("Empêche l\'enlèvement"), ["empêche l'enlèvement"]);
  assert.deepEqual(findClaims('Empêche l&apos;enlèvement'), ["empêche l'enlèvement"]);
  assert.deepEqual(findClaims('Empêche l&#39;enlèvement'), ["empêche l'enlèvement"]);
});

test('flags decomposed accents and odd spacing', () => {
  assert.deepEqual(findClaims('empêche l\'enlèvement'), ["empêche l'enlèvement"]);
  assert.deepEqual(findClaims('prevents kidnapping'), ['prevents kidnapping']);
  assert.deepEqual(findClaims('prevents   kidnapping'), ['prevents kidnapping']);
});
