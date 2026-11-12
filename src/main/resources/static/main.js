// Entry point. Wires the connection to the Three.js view (render/), the view controls and the
// tuning panel (ui/), and the 2D debug view (debug/), which stays available as a toggle.
// The sound engine (audio/) attaches in Phase 2 at the points marked "AUDIO".

import { connect } from './net/connection.js';
import { createTuningPanel } from './ui/tuning.js';
import { createViewControls, addViewFolder } from './ui/viewControls.js';
import { onLevelChange } from './ui/level.js';
import { createRenderView } from './render/scene.js';
import { createDebugView } from './debug/view2d.js';

const status = document.getElementById('status');
const debugCanvas = document.getElementById('debug');

let lastStats = { tick: 0, ants: 0, voices: 0, symbols: 0 };
let antFrames = 0;

// The 3D view is the default. The 2D debug view is only created the first time it is switched
// on, and is handed no ants while it is hidden: its own frame loop draws every ant it has.
const view = createRenderView(document.getElementById('view'), {
  onPlaceFood: (x, y) => connection.send({ type: 'placeFood', x, y }),
});
let debugView = null;
let debugOn = false;
let lastPheromone = null;
let lastState = null;

function setDebugView(on) {
  debugOn = on;
  if (on && !debugView) debugView = createDebugView(debugCanvas);
  debugCanvas.style.display = on ? 'block' : 'none';
  view.setVisible(!on);
  if (on) {
    if (lastPheromone) debugView.setPheromone(lastPheromone);
    if (lastState) debugView.setState(lastState);
  } else if (debugView) {
    debugView.setAnts(null);
    debugView.setState(null);
  }
}

const connection = connect({
  onStatus: (s) => { status.dataset.connection = s; },
  onMessage(message) {
    switch (message.type) {
      case 'ants':
        antFrames++;
        lastStats.tick = message.tick;
        lastStats.ants = message.count;
        view.setAnts(message);
        if (debugOn) debugView.setAnts(message);
        break;
      case 'pheromone':
        lastPheromone = message;
        view.setPheromone(message);
        if (debugOn) debugView.setPheromone(message);
        break;
      case 'terrain':
        view.setTerrain(message);
        break;
      case 'state':
        lastState = message;
        lastStats.voices = message.voices.length;
        lastStats.symbols = message.symbols.length;
        view.setState(message);
        if (debugOn) debugView.setState(message);
        // AUDIO (Phase 2): hand the state to the sound engine here, e.g. engine.setState(message).
        break;
      case 'params':
        panel.applyParams(message.params);
        break;
    }
  },
});

const panel = createTuningPanel(connection);
addViewFolder(panel.gui, view);
// AUDIO (Phase 2): the sound engine adds its "Sound" folder here, through panel.gui, so
// presets include it automatically.

createViewControls(document.getElementById('controls'), {
  onTilt: (t) => view.setTilt(t),
  onDebugView: setDebugView,
});

// The level of description drives the picture here, and the sound in Phase 2.
onLevelChange((level) => view.setLevel(level));
// AUDIO (Phase 2): onLevelChange((level) => engine.setLevel(level));

// Clicking the 2D debug view places food too (the 3D view handles its own clicks).
debugCanvas.addEventListener('click', (event) => {
  const { x, y } = debugView.toWorld(event);
  connection.send({ type: 'placeFood', x, y });
});

// Once a second: connection, tick, ants, voices, how many ant frames arrived, and how many
// frames the 3D view drew (it should match the display's refresh rate).
setInterval(() => {
  const s = lastStats;
  status.textContent =
    `${status.dataset.connection ?? '…'} · tick ${s.tick} · ${s.ants} ants · ` +
    `${s.voices} voices · ${s.symbols} symbols · ${antFrames} ant frames/s · ${view.takeFrameCount()} fps`;
  antFrames = 0;
}, 1000);
