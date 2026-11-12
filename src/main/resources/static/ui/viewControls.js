// The on-screen view controls along the top of the page: the level slider (Ants, Signals,
// Symbols), the tilt control for the stacked view, and the toggle to the 2D debug view.
// Plus the View folder of the tuning panel, which holds the renderer's own settings.
//
// The level and tilt are deliberately not in the tuning panel: they are how the viewer is
// looking, not how the colony is tuned, so presets leave them alone.

import { getLevel, setLevel, onLevelChange } from './level.js';
import { levelName } from '../render/levels.js';

/**
 * Builds the controls into `container`. `onTilt(t)` gets 0 (flat) to 1 (tilted), and
 * `onDebugView(on)` is called when the 2D debug view is switched on or off.
 */
export function createViewControls(container, { onTilt, onDebugView }) {
  container.innerHTML = `
    <label class="control level">
      <span class="label">Level <b data-name></b></span>
      <input type="range" min="0" max="2" step="0.01" value="${getLevel()}" data-level>
      <span class="ticks"><span>Ants</span><span>Signals</span><span>Symbols</span></span>
    </label>
    <label class="control tilt">
      <span class="label">Tilt</span>
      <input type="range" min="0" max="1" step="0.01" value="0" data-tilt>
    </label>
    <label class="control debug">
      <input type="checkbox" data-debug> 2D debug view
    </label>
  `;
  const level = container.querySelector('[data-level]');
  const name = container.querySelector('[data-name]');
  const tilt = container.querySelector('[data-tilt]');
  const debug = container.querySelector('[data-debug]');

  level.addEventListener('input', () => setLevel(Number(level.value)));
  onLevelChange((l) => {
    if (Number(level.value) !== l) level.value = String(l);
    name.textContent = levelName(l);
  });
  tilt.addEventListener('input', () => onTilt(Number(tilt.value)));
  debug.addEventListener('change', () => {
    // Tilt means nothing in the 2D view.
    tilt.disabled = debug.checked;
    onDebugView(debug.checked);
  });
}

/**
 * Adds the View folder: the renderer's settings, which are client parameters, so presets
 * save and reload them along with everything else.
 */
export function addViewFolder(gui, view) {
  const folder = gui.addFolder('View');
  const p = view.params;
  const apply = () => view.refreshParams();
  folder.add(p, 'antSize', 0.5, 6, 0.1).name('Ant size').onChange(apply);
  folder.add(p, 'ribbonWidth', 0.5, 12, 0.5).name('Ribbon width').onChange(apply);
  folder.add(p, 'relief', 0, 10, 0.5).name('Terrain relief').onChange(apply);
  folder.add(p, 'foodGain', 0, 3, 0.05).name('Food pheromone').onChange(apply);
  folder.add(p, 'homeGain', 0, 3, 0.05).name('Home pheromone').onChange(apply);
  folder.close();
  return folder;
}
