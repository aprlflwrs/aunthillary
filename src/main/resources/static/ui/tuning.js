// The tuning panel. It builds a slider for every server parameter from the params message, so
// declaring a parameter in the Kotlin code is all it takes to get a slider here. Also holds
// the tick loop controls (pause, step, speed, reset) and the flood button.

import GUI from 'lil-gui';

export function createTuningPanel(connection) {
  const gui = new GUI({ title: 'Tuning' });

  // ---- simulation controls ----
  const controls = {
    paused: false,
    speed: 1,
    step: () => connection.send({ type: 'control', action: 'step' }),
    reset: () => connection.send({ type: 'control', action: 'reset' }),
    flood: () => connection.send({ type: 'flood' }),
  };
  const sim = gui.addFolder('Simulation');
  sim.add(controls, 'paused').name('Pause').onChange((p) =>
    connection.send({ type: 'control', action: p ? 'pause' : 'resume' }));
  sim.add(controls, 'step').name('Step one tick');
  sim.add(controls, 'speed', 0.25, 16, 0.25).name('Speed').onFinishChange((v) =>
    connection.send({ type: 'control', action: 'speed', value: v }));
  sim.add(controls, 'reset').name('Reset colony');
  sim.add(controls, 'flood').name('Flood');

  // ---- server parameters, one folder per group ----
  const values = {};      // key -> current value; lil-gui edits this object
  const sliders = {};     // key -> lil-gui controller
  const folders = {};     // group -> lil-gui folder

  function applyParams(params) {
    for (const p of params) {
      if (!(p.key in sliders)) {
        values[p.key] = p.value;
        const folder = folders[p.group] ??= gui.addFolder(p.group);
        sliders[p.key] = folder
          .add(values, p.key, p.min, p.max, p.step)
          .name(p.label)
          .onChange((v) => connection.send({ type: 'setParam', key: p.key, value: v }));
      } else if (values[p.key] !== p.value) {
        // The server's value wins (it may have been clamped, or changed by a reset).
        values[p.key] = p.value;
        sliders[p.key].updateDisplay();
      }
    }
  }

  return { gui, applyParams };
}
