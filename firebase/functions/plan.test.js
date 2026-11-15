'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { recipients, isNewAnnouncement, announcement, clean, MIN_COOLDOWN_MS } = require('./plan');

const tok = (n) => ['t'.repeat(25) + n];
const HOUR = 60 * 60 * 1000;
const pair = (over = {}) => ({ id: 'a_b', members: ['a', 'b'], status: 'accepted', tell: { a: true, b: false }, mute: { a: false, b: false }, lastAnnounce: {}, ...over });

test('tells an accepted friend the walker chose', () => {
  const r = recipients('a', [pair()], { b: tok(1) }, 10 * HOUR);
  assert.deepStrictEqual(r.map((x) => x.friend), ['b']);
  assert.strictEqual(r[0].pairId, 'a_b');
});

test('never tells someone not chosen, not accepted, or who muted the walker', () => {
  assert.strictEqual(recipients('a', [pair({ tell: { a: false, b: false } })], { b: tok(1) }, 10 * HOUR).length, 0);
  assert.strictEqual(recipients('a', [pair({ status: 'pending' })], { b: tok(1) }, 10 * HOUR).length, 0);
  assert.strictEqual(recipients('a', [pair({ mute: { a: false, b: true } })], { b: tok(1) }, 10 * HOUR).length, 0);
  assert.strictEqual(recipients('a', [pair({ members: ['b', 'c'] })], { b: tok(1), c: tok(2) }, 10 * HOUR).length, 0);
});

test('the server enforces a cooldown whatever the phone asks for', () => {
  const p = pair({ lastAnnounce: { a: 10 * HOUR } });
  assert.strictEqual(recipients('a', [p], { b: tok(1) }, 10 * HOUR + 30 * 60 * 1000, 0).length, 0); // 30 min later, phone says no cooldown
  assert.strictEqual(recipients('a', [p], { b: tok(1) }, 12 * HOUR, 0).length, 1);
  assert.strictEqual(recipients('a', [p], { b: tok(1) }, 12 * HOUR, 5 * HOUR).length, 0); // the phone's own longer cooldown is respected
  assert.strictEqual(MIN_COOLDOWN_MS, HOUR);
});

test('a friend without a push token is skipped, junk tokens ignored', () => {
  assert.strictEqual(recipients('a', [pair()], { b: [] }, 10 * HOUR).length, 0);
  assert.strictEqual(recipients('a', [pair()], { b: ['short', 5, null] }, 10 * HOUR).length, 0);
});

test('only a new announcement id counts', () => {
  assert.strictEqual(isNewAnnouncement(null, { announceId: 'x', state: 'walking' }), true);
  assert.strictEqual(isNewAnnouncement({ announceId: 'x' }, { announceId: 'x', state: 'walking', steps: 9 }), false);
  assert.strictEqual(isNewAnnouncement({ announceId: 'x' }, { announceId: 'y', state: 'running' }), true);
  assert.strictEqual(isNewAnnouncement({ announceId: 'x' }, null), false);
  assert.strictEqual(isNewAnnouncement(null, { state: 'walking' }), false);
});

test('names are cleaned the same way as on the phone', () => {
  assert.strictEqual(announcement('Maurice', 'walking'), 'Maurice is going for a walk. Join them?');
  assert.strictEqual(announcement('Mo\u0007', 'running'), 'Mo is going for a run. Join them?');
  assert.strictEqual(announcement('https://evil.example/x', 'walking'), 'A friend is going for a walk. Join them?');
  assert.strictEqual(clean('x'.repeat(100)).length, 24);
});

test('with tokens not yet fetched, finds who could be told', () => {
  assert.deepStrictEqual(recipients('a', [pair()], null, 10 * HOUR).map((x) => x.friend), ['b']);
  assert.strictEqual(recipients('a', [pair({ mute: { a: false, b: true } })], null, 10 * HOUR).length, 0);
});
