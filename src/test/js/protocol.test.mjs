// Decodes the bytes the Kotlin encoder wrote (build/protocol-fixtures/, produced by ProtocolTest)
// with the browser's decoder, so both sides of the protocol are checked against the same bytes.
// Run with ./gradlew jsTest (or ./gradlew check, which runs it after the Kotlin tests).

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { decodeBinary, decodeText, positionToWorld, headingToRadians } from '../../main/resources/static/net/protocol.js';

const fixture = (name) => {
  const b = readFileSync(new URL(`../../../build/protocol-fixtures/${name}`, import.meta.url));
  return b.buffer.slice(b.byteOffset, b.byteOffset + b.byteLength);
};

test('ant frame', () => {
  const m = decodeBinary(fixture('ants.bin'));
  assert.equal(m.type, 'ants');
  assert.equal(m.tick, 7);
  assert.equal(m.count, 2);
  assert.deepEqual([...m.x], [0, 32768]);
  assert.deepEqual([...m.y], [65535, 16384]);
  assert.deepEqual([...m.heading], [64, 192]);
  assert.deepEqual([...m.flags], [3, 4]);
  assert.ok(Math.abs(positionToWorld(m.x[1]) - 256) < 0.01);
  assert.ok(Math.abs(headingToRadians(m.heading[0]) - Math.PI / 2) < 1e-9);
});

test('pheromone frame', () => {
  const m = decodeBinary(fixture('pheromone.bin'));
  assert.equal(m.type, 'pheromone');
  assert.equal(m.tick, 9);
  assert.equal(m.n, 2);
  assert.deepEqual([...m.food], [0, 128, 255, 255]);
  assert.deepEqual([...m.home], [51, 0, 0, 0]);
});

test('terrain frame', () => {
  const m = decodeBinary(fixture('terrain.bin'));
  assert.equal(m.type, 'terrain');
  assert.equal(m.n, 2);
  assert.equal(m.maxHeight, 4);
  const expected = [0, 1, 2, 4];
  m.heights.forEach((h, i) => assert.ok(Math.abs(h - expected[i]) < 1e-3, `height ${i}: ${h}`));
  assert.deepEqual([...m.blocked], [0, 1, 0, 0]);
});

test('state message', () => {
  const m = decodeText(readFileSync(new URL('../../../build/protocol-fixtures/state.json', import.meta.url), 'utf8'));
  assert.equal(m.type, 'state');
  assert.deepEqual(m.voices[0].points[0], [268.1, 259.0, 1.4, 0.91]);
  assert.equal(m.symbols[0].voiceIds[0], 3);
  assert.equal(m.headings.length, 16);
});
