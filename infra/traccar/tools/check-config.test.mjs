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
  const errors = errorsOf(set(template, 'protocols.enable', 'gt06,osmand,h02'));
  assert.ok(errors.some((e) => e.includes('protocols.enable') && e.includes('h02')), errors.join('\n'));
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
