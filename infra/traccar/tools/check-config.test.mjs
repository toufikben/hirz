import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { validateConfig } from './check-config.mjs';

const template = await readFile(new URL('../hirz.template.xml', import.meta.url), 'utf8');
const set = (xml, key, value) =>
  xml.replace(new RegExp(`<entry key='${key.replace('.', '\.')}'>[^<]*</entry>`), `<entry key='${key}'>${value}</entry>`);
const errorsOf = (xml, opts = { allowPlaceholders: true }) => validateConfig(xml, opts).errors;

test('template passes with placeholders allowed', () => {
  assert.deepEqual(errorsOf(template), []);
});

test('unrendered placeholder fails when placeholders are not allowed', () => {
  assert.ok(errorsOf(template, { allowPlaceholders: false }).some((e) => e.includes('unrendered placeholder')));
});

// v2 lessons (kidguard-core lab/tests/config.test.mjs): geocoder, quote styles, duplicates.
test('geocoder.enable=true is rejected with the rule message', () => {
  const errors = errorsOf(set(template, 'geocoder.enable', 'true'));
  assert.ok(errors.some((e) => e.includes('geocoder.enable') && e.includes('leaks')), errors.join('\n'));
});

test('a missing geocoder key is rejected', () => {
  const without = template.replace(/<entry key='geocoder\.enable'>[^<]*<\/entry>/, '');
  assert.ok(errorsOf(without).some((e) => e === 'geocoder.enable is required'));
});

test('a double-quoted entry is parsed, not ignored', () => {
  const dq = template.replace("<entry key='geocoder.enable'>false</entry>", '<entry key="geocoder.enable">true</entry>');
  assert.ok(errorsOf(dq).some((e) => e.startsWith('geocoder.enable:')));
});

test('a duplicated key is rejected and the last definition is the one judged', () => {
  const dup = template.replace('</properties>', "    <entry key='geocoder.enable'>true</entry>\n</properties>");
  const errors = errorsOf(dup);
  assert.ok(errors.includes('geocoder.enable is defined twice in one config'));
  assert.ok(errors.some((e) => e.startsWith('geocoder.enable:')));
});

// New Hirz rules.
test('rejectsUnknownProtocols', () => {
  const errors = errorsOf(set(template, 'protocols.enable', 'gt06,osmand,teltonika'));
  assert.ok(errors.some((e) => e.includes('protocols.enable') && e.includes('teltonika')), errors.join('\n'));
});

test('rejectsPublicWebAddress', () => {
  const errors = errorsOf(set(template, 'web.address', '0.0.0.0'));
  assert.ok(errors.some((e) => e.startsWith('web.address:') && e.includes('localhost')), errors.join('\n'));
});

test('rejectsRegisterUnknown', () => {
  const errors = errorsOf(set(template, 'database.registerUnknown', 'true'));
  assert.ok(errors.some((e) => e.startsWith('database.registerUnknown:') && e.includes('whitelist')), errors.join('\n'));
});

test('rejects an empty database password', () => {
  const errors = errorsOf(set(template, 'database.password', ''), { allowPlaceholders: false });
  assert.ok(errors.some((e) => e.startsWith('database.password:')), errors.join('\n'));
});

test('rejects self-registration keys', () => {
  const reg = template.replace('</properties>', "    <entry key='web.registration'>true</entry>\n</properties>");
  assert.ok(errorsOf(reg).some((e) => e.includes('web.registration')));
});

// Final review (Important #1): comments, spaced attributes and CDATA must not hide or fake entries.
test('a commented-out entry does not count as present', () => {
  const commented = template.replace("<entry key='geocoder.enable'>false</entry>", "<!-- <entry key='geocoder.enable'>false</entry> -->");
  assert.ok(errorsOf(commented).includes('geocoder.enable is required'));
  const web = template.replace("<entry key='web.address'>127.0.0.1</entry>", "<!-- <entry key='web.address'>127.0.0.1</entry> -->");
  assert.ok(errorsOf(web).includes('web.address is required'));
});

test('spaces around = are parsed, so a later override is caught', () => {
  const spaced = template.replace('</properties>', "    <entry key = 'geocoder.enable'>true</entry>\n</properties>");
  const errors = errorsOf(spaced);
  assert.ok(errors.includes('geocoder.enable is defined twice in one config'), errors.join('\n'));
  assert.ok(errors.some((e) => e.startsWith('geocoder.enable:')));
});

test('an entry the parser cannot read fails instead of being skipped', () => {
  const cdata = template.replace("<entry key='geocoder.enable'>false</entry>", "<entry key='geocoder.enable'><![CDATA[true]]></entry>");
  assert.ok(errorsOf(cdata).some((e) => e.includes('unreadable')), errorsOf(cdata).join('\n'));
  const entity = template.replace("<entry key='geocoder.enable'>false</entry>", "<entry key='geocoder.enable'>&#116;rue</entry>");
  assert.ok(errorsOf(entity).some((e) => e.includes('unreadable')), errorsOf(entity).join('\n'));
});

test('h02 (SinoTrack ST-901) is an allowed protocol', () => {
  assert.deepEqual(errorsOf(set(template, 'protocols.enable', 'gt06,h02,osmand')), []);
});

test('an unknown protocol is still refused', () => {
  assert.ok(errorsOf(set(template, 'protocols.enable', 'gt06,watch')).some((e) => e.includes('remove watch')));
});
