// The sound check page: the engine wired to the colony (the fake one, for now) exactly as
// Phase 2 should wire it into main.js, plus a stand-in for piece C's level slider and the 2D
// debug view so you can see the trails you are hearing.

import { connect } from '../net/connection.js';
import { createTuningPanel } from '../ui/tuning.js';
import { createDebugView } from '../debug/view2d.js';
import { createSoundEngine } from './engine.js';
import { SLOT_COLORS } from './colors.js';

const view = createDebugView(document.getElementById('debug'));
const status = document.getElementById('status');

const connection = connect({
  onStatus: (s) => { status.textContent = s; },
  onMessage(message) {
    switch (message.type) {
      case 'ants': engine.onAnts(message); view.setAnts(message); break;
      case 'pheromone': view.setPheromone(message); break;
      case 'terrain': engine.onTerrain(message); break;
      case 'state': engine.onState(message); view.setState(message); break;
      case 'params': panel.applyParams(message.params); break;
    }
  },
});
const panel = createTuningPanel(connection);
const engine = createSoundEngine({ panel });

// Join: audio may only start from a user gesture.
const join = document.getElementById('join');
join.addEventListener('click', async () => {
  join.disabled = true;
  join.textContent = 'Loading samples…';
  await engine.start();
  join.textContent = 'Playing';
});

// Stand-in for piece C's level slider.
const level = document.getElementById('level');
const levelName = document.getElementById('levelName');
level.addEventListener('input', () => {
  const v = Number(level.value);
  engine.setLevel(v);
  levelName.textContent = v < 0.5 ? 'Ants' : v < 1.5 ? 'Signals' : 'Symbols';
});

engine.attachPianoRoll(document.getElementById('roll'));

// Which voices are playing, in their colours.
setInterval(() => {
  const voices = engine.activeVoices();
  document.getElementById('voices').innerHTML = voices.length
    ? voices.map((v) => `<span style="color:${SLOT_COLORS[v.slot]}">voice ${v.id} · slot ${v.slot + 1} · ${v.strength.toFixed(2)}${v.fading ? ' · fading' : ''}</span>`).join('')
    : 'no voices';
}, 500);
