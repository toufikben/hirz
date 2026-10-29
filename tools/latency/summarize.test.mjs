import { test } from 'node:test';
import assert from 'node:assert/strict';
import { percentile, summarize } from './summarize.mjs';

const incident = (opened, deliveries) => ({ id: `i${opened}`, openedAtMs: opened, deliveries });

test('nearest-rank percentile', () => {
  const ten = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10];
  assert.equal(percentile(ten, 50), 5);
  assert.equal(percentile(ten, 95), 10);
  assert.equal(percentile([], 95), null);
});

test('summarizes open→delivered and open→opened per member delivery', () => {
  const rows = Array.from({ length: 10 }, (_, i) =>
    incident(1_000 * i, [{ memberId: 'm', sentAtMs: 1_000 * i + 100, deliveredAtMs: 1_000 * i + 1_000 * (i + 1), openedAtMs: 1_000 * i + 2_000 * (i + 1) }]));
  const s = summarize(rows);
  assert.equal(s.incidents, 10);
  assert.equal(s.delivered.count, 10);
  assert.equal(s.delivered.p50Ms, 5_000);
  assert.equal(s.delivered.p95Ms, 10_000);
  assert.equal(s.opened.p95Ms, 20_000);
  assert.equal(s.undelivered, 0);
});

test('a delivery that never arrived is counted, not dropped', () => {
  const s = summarize([incident(0, [{ memberId: 'a', sentAtMs: 10, deliveredAtMs: 900 }, { memberId: 'b', sentAtMs: 10 }, { memberId: 'c', error: 'no_push_token' }])]);
  assert.equal(s.delivered.count, 1);
  assert.equal(s.undelivered, 2);
});
