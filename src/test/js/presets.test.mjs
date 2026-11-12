// Tests for presets: the pure store (ui/presets.js), and saving and loading a real lil-gui
// tuning panel (ui/tuning.js) built on a fake DOM, checking that loading a preset sends a
// setParam for every server parameter and restores the client settings.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { STATIC } from './resolve-vendor.mjs';
import { installFakeDom } from './fake-dom.mjs';

installFakeDom();
const { createPresetStore, stripPreset, isPresetState, STORAGE_KEY } = await import(new URL('ui/presets.js', STATIC));
const { createTuningPanel } = await import(new URL('ui/tuning.js', STATIC));

function memoryStorage() {
  const data = {};
  return { getItem: (k) => data[k] ?? null, setItem: (k, v) => { data[k] = String(v); }, data };
}

const throwingStorage = {
  getItem() { throw new Error('denied'); },
  setItem() { throw new Error('denied'); },
};

const sample = {
  controllers: {},
  folders: {
    Simulation: { controllers: { Pause: true, Speed: 2 }, folders: {} },
    Presets: { controllers: { Name: 'x', 'Saved presets': 'x' }, folders: {} },
    Pheromone: { controllers: { 'Evaporation (per second)': 0.08 }, folders: {} },
    Sound: { controllers: { Volume: 0.7, Mapping: 'turning', Muted: false }, folders: {} },
  },
};

test('stripPreset drops the Presets folder and the Pause checkbox, and nothing else', () => {
  const s = stripPreset(sample);
  assert.equal(s.folders.Presets, undefined);
  assert.deepEqual(s.folders.Simulation.controllers, { Speed: 2 });
  assert.deepEqual(s.folders.Pheromone, sample.folders.Pheromone);
  assert.deepEqual(s.folders.Sound, sample.folders.Sound);
  assert.ok(sample.folders.Presets, 'the input is not modified');
});

test('isPresetState accepts gui.save() shapes and rejects others', () => {
  assert.ok(isPresetState(sample));
  assert.ok(isPresetState({ controllers: {}, folders: {} }));
  assert.ok(!isPresetState(null));
  assert.ok(!isPresetState([]));
  assert.ok(!isPresetState({ controllers: { a: {} }, folders: {} }));
  assert.ok(!isPresetState({ controllers: {}, folders: { F: 3 } }));
});

test('the store saves, lists, reloads from storage and deletes', () => {
  const storage = memoryStorage();
  const store = createPresetStore(storage);
  assert.ok(store.put('Quiet', sample));
  assert.ok(store.put('Busy', sample));
  assert.deepEqual(store.names(), ['Busy', 'Quiet']);
  assert.ok(storage.data[STORAGE_KEY]);

  const again = createPresetStore(storage); // a new page load
  assert.deepEqual(again.names(), ['Busy', 'Quiet']);
  assert.deepEqual(again.get('Quiet'), stripPreset(sample));
  again.remove('Busy');
  assert.deepEqual(createPresetStore(storage).names(), ['Quiet']);
});

test('the store keeps working in memory when storage throws', () => {
  const errors = [];
  const store = createPresetStore(throwingStorage, { onError: (e) => errors.push(e) });
  assert.equal(store.put('A', sample), false, 'put reports that storage failed');
  assert.deepEqual(store.names(), ['A']);
  assert.ok(errors.length >= 2);
  const none = createPresetStore(null);
  none.put('B', sample);
  assert.deepEqual(none.names(), ['B']);
});

test('the store ignores corrupt storage', () => {
  const storage = memoryStorage();
  storage.setItem(STORAGE_KEY, '{not json');
  assert.deepEqual(createPresetStore(storage, { onError() {} }).names(), []);
  storage.setItem(STORAGE_KEY, JSON.stringify({ good: sample, bad: 5 }));
  assert.deepEqual(createPresetStore(storage).names(), ['good']);
});

test('export and import round-trip, and import rejects other files', () => {
  const a = createPresetStore(null);
  a.put('One', sample);
  a.put('Two', { controllers: {}, folders: {} });
  const text = a.exportJson();

  const b = createPresetStore(null);
  assert.equal(b.importJson(text), 2);
  assert.deepEqual(b.names(), ['One', 'Two']);
  assert.deepEqual(b.get('One'), a.get('One'));

  assert.throws(() => b.importJson('{"presets": {}}'), /Not an Ant Fugue presets file/);
  assert.throws(() => b.importJson('nonsense'));
  assert.throws(() => b.importJson(JSON.stringify({ format: 'ant-fugue-presets', version: 99, presets: {} })), /newer/);
});

test('a preset saved from the panel reloads every server and client parameter', () => {
  globalThis.localStorage = memoryStorage();
  const sent = [];
  const panel = createTuningPanel({ send: (m) => sent.push(m) });
  const params = [
    { key: 'pheromone.evaporation', label: 'Evaporation', group: 'Pheromone', min: 0, max: 1, step: 0.001, default: 0.05, value: 0.05 },
    { key: 'pheromone.deposit', label: 'Deposit', group: 'Pheromone', min: 0, max: 10, step: 0.1, default: 1, value: 1 },
    { key: 'fake.antCount', label: 'Ants', group: 'Fake colony', min: 0, max: 100000, step: 1000, default: 30000, value: 30000 },
  ];
  panel.applyParams(params);

  // A client folder added by other code, the way the View and Sound folders are.
  const client = { volume: 0.8, mapping: 'compass' };
  const sound = panel.gui.addFolder('Sound');
  sound.add(client, 'volume', 0, 1).name('Volume');
  sound.add(client, 'mapping', ['compass', 'turning']).name('Mapping');

  // Save with some values, then change everything.
  const values = { 'pheromone.evaporation': 0.2, 'pheromone.deposit': 3, 'fake.antCount': 5000 };
  panel.applyParams(params.map((p) => ({ ...p, value: values[p.key] })));
  assert.ok(panel.presets.save('Mine'));
  assert.deepEqual(panel.presets.names(), ['Mine']);

  panel.applyParams(params);
  client.volume = 0.1;
  client.mapping = 'turning';
  sent.length = 0;

  assert.ok(panel.presets.load('Mine'));
  const setParams = Object.fromEntries(sent.filter((m) => m.type === 'setParam').map((m) => [m.key, m.value]));
  assert.deepEqual(setParams, values, 'one setParam per server parameter, with the saved value');
  assert.ok(!sent.some((m) => m.type === 'control' && (m.action === 'pause' || m.action === 'resume')),
    'loading a preset does not pause or resume the colony');
  assert.ok(sent.some((m) => m.type === 'control' && m.action === 'speed'), 'the speed is restored');
  assert.equal(client.volume, 0.8);
  assert.equal(client.mapping, 'compass');

  // And it survived in storage, for the next page load.
  assert.ok(globalThis.localStorage.getItem(STORAGE_KEY).includes('Mine'));
});

test('a preset loaded before the server parameters arrive is applied when they do', () => {
  globalThis.localStorage = memoryStorage();
  const sent = [];
  const first = createTuningPanel({ send() {} });
  const p = { key: 'k', label: 'K', group: 'G', min: 0, max: 1, step: 0.1, default: 0, value: 0.7 };
  first.applyParams([p]);
  first.presets.save('Early');

  const panel = createTuningPanel({ send: (m) => sent.push(m) });
  panel.presets.load('Early');
  assert.equal(sent.filter((m) => m.type === 'setParam').length, 0);
  panel.applyParams([{ ...p, value: 0 }]);
  assert.deepEqual(sent.filter((m) => m.type === 'setParam'), [{ type: 'setParam', key: 'k', value: 0.7 }]);
});
