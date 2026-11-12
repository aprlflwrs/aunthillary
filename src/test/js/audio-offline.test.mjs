// The sound engine must never reach the internet: every sample it names is a file in
// vendor/samples/, and no audio module mentions a remote URL or a smplr loader with one built in.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));
const AUDIO = join(STATIC, 'audio');

function walk(dir) {
  return readdirSync(dir).flatMap((f) => {
    const p = join(dir, f);
    return statSync(p).isDirectory() ? walk(p) : [p];
  });
}

test('no audio module contains a remote URL or uses smplr\'s remote loaders', () => {
  for (const file of walk(AUDIO).filter((f) => /\.(js|html)$/.test(f))) {
    const text = readFileSync(file, 'utf8');
    assert.ok(!/https?:\/\//.test(text), `${file} mentions a URL`);
    for (const loader of ['Soundfont', 'Reverb', 'SplendidGrandPiano', 'DrumMachine', 'Mellotron', 'Versilian', 'Smolken', 'ElectricPiano', 'Mallet']) {
      assert.ok(!new RegExp(`\\b${loader}\\s*\\(`).test(text), `${file} calls smplr's ${loader}`);
    }
  }
});

test('every sample the instruments load is self-hosted', async () => {
  // instruments.js imports smplr through the import map, so read its lists as text instead.
  const src = readFileSync(join(AUDIO, 'instruments.js'), 'utf8');
  const list = (name) => JSON.parse(src.match(new RegExp(`${name} = (\\[[^\\]]*\\])`))[1].replace(/'/g, '"').replace(/\s+/g, ''));
  const name = (n) => src.match(new RegExp(`${n} = '([^']+)'`))[1];
  const base = join(STATIC, 'vendor/samples/fluidr3-gm');
  let bytes = 0;
  for (const [instrument, notes] of [[name('MELLOW'), list('ORGAN_NOTES')], [name('BRIGHT'), list('ORGAN_NOTES')], [name('TICK'), list('TICK_NOTES')]]) {
    for (const note of notes) {
      const f = join(base, instrument, `${note}.mp3`);
      assert.ok(existsSync(f), `missing ${f}`);
      bytes += statSync(f).size;
    }
  }
  assert.ok(bytes < 2_000_000, `samples total ${bytes} bytes`);
  assert.ok(existsSync(join(STATIC, 'vendor/samples/CREDITS.txt')), 'attribution for the samples');
});
