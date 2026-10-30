// Static checks on the staging stack: what is published, what is pinned, what never holds a value.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const read = (name) => readFile(new URL(name, import.meta.url), 'utf8');
const compose = await read('./compose.yml');

const published = [...compose.matchAll(/^\s*-\s*"?(\d+):(\d+)"?\s*$/gm)].map((m) => m[1]);

test('only the device and web ports are published', () => {
  assert.deepEqual(published.sort(), ['443', '5013', '5023', '80'].sort());
});

test('Traccar web (8082) and PostgreSQL (5432) are never published', () => {
  assert.ok(!published.includes('8082') && !published.includes('5432'));
});

test('every image is pinned by tag, never latest', () => {
  const images = [...compose.matchAll(/^\s*image:\s*(\S+)/gm)].map((m) => m[1]);
  assert.ok(images.length >= 3, images.join(','));
  for (const image of images) {
    assert.ok(!image.endsWith(':latest'), image);
    assert.ok(/:[^/]+$/.test(image) || image.includes('${'), `${image} has no tag`);
  }
});

test('core.env.example lists keys with no values', async () => {
  const lines = (await read('./core.env.example')).split('\n').filter((l) => l.trim() && !l.trim().startsWith('#'));
  assert.ok(lines.length >= 10);
  for (const line of lines) assert.match(line, /^[A-Z0-9_]+=$/, line);
  for (const key of ['HIRZ_TRACCAR_SECRET', 'HIRZ_DATA_KEY', 'HIRZ_DEVICE_HOST', 'HIRZ_DATABASE_PASSWORD']) {
    assert.ok(lines.includes(`${key}=`), key);
  }
});

test('Caddy sets HSTS', async () => {
  assert.match(await read('./Caddyfile'), /Strict-Transport-Security/);
});

test('secrets come from the env file, never from compose', () => {
  assert.ok(!/PASSWORD\s*[:=]\s*\S/.test(compose.replace(/\$\{[^}]+\}/g, '')), 'compose holds a literal password');
  assert.match(compose, /env_file:/);
});
