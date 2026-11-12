// Entry point. Phase 0 wires the connection to the tuning panel and the 2D debug view; the
// Three.js renderer (render/) and the sound engine (audio/) plug in here in Phase 1.

import { connect } from './net/connection.js';
import { createTuningPanel } from './ui/tuning.js';
import { createDebugView } from './debug/view2d.js';

const status = document.getElementById('status');
const view = createDebugView(document.getElementById('debug'));

let lastStats = { tick: 0, ants: 0, voices: 0, symbols: 0 };
let antFrames = 0;

const connection = connect({
  onStatus: (s) => { status.dataset.connection = s; },
  onMessage(message) {
    switch (message.type) {
      case 'ants':
        antFrames++;
        lastStats.tick = message.tick;
        lastStats.ants = message.count;
        view.setAnts(message);
        break;
      case 'pheromone':
        view.setPheromone(message);
        break;
      case 'terrain':
        break; // used by the renderer in Phase 1
      case 'state':
        lastStats.voices = message.voices.length;
        lastStats.symbols = message.symbols.length;
        view.setState(message);
        break;
      case 'params':
        panel.applyParams(message.params);
        break;
    }
  },
});

const panel = createTuningPanel(connection);

// Clicking places food.
document.getElementById('debug').addEventListener('click', (event) => {
  const { x, y } = view.toWorld(event);
  connection.send({ type: 'placeFood', x, y });
});

// Once a second: connection, tick, ants, voices, and how many ant frames arrived.
setInterval(() => {
  const s = lastStats;
  status.textContent =
    `${status.dataset.connection ?? '…'} · tick ${s.tick} · ${s.ants} ants · ` +
    `${s.voices} voices · ${s.symbols} symbols · ${antFrames} frames/s`;
  antFrames = 0;
}, 1000);
