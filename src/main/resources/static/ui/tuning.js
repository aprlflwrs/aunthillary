// The tuning panel. It builds a slider for every server parameter from the params message, so
// declaring a parameter in the Kotlin code is all it takes to get a slider here. Also holds
// the tick loop controls (pause, step, speed, reset), the flood button, and named presets.
//
// Other code adds its own folders through the returned `gui` (the renderer's View folder,
// the sound engine's Sound folder); presets include them automatically.
//
// Presets (see ui/presets.js) are lil-gui's own gui.save() / gui.load() over the whole panel.
// Loading sets every controller through its normal path, which fires its onChange (and
// onFinishChange): each server slider sends its setParam, the speed control sends its speed,
// and client settings apply themselves, exactly as if the viewer had moved each one.

import GUI from 'lil-gui';
import { createPresetStore, stripPreset } from './presets.js';

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

  // ---- presets ----
  let serverReady = false; // true once the first params message has built the server sliders
  const presets = createPresetsFolder(gui, () => serverReady);

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
    if (!serverReady && params.length > 0) {
      serverReady = true;
      presets.serverParamsArrived();
    }
  }

  return { gui, applyParams, presets };
}

/**
 * The Presets folder: a name box and Save button, a list of saved presets with Load and
 * Delete, and export / import of all presets as a JSON file. Returns a small API so other
 * code (or the console) can save and load presets too.
 */
function createPresetsFolder(gui, isServerReady) {
  const store = createPresetStore(undefined, {
    onError: (e) => { console.warn('Presets: browser storage unavailable', e); },
  });
  const folder = gui.addFolder('Presets');
  const ui = {
    name: 'my preset',
    selected: '',
    status: '',
    save: () => save(ui.name),
    load: () => load(ui.selected),
    remove: () => { if (ui.selected) { store.remove(ui.selected); refreshList(); setStatus(`deleted "${ui.selected}"`); } },
    exportFile,
    importFile,
  };
  let pending = null; // a preset asked for before the server's parameters had arrived

  const nameBox = folder.add(ui, 'name').name('Name');
  folder.add(ui, 'save').name('Save current settings');
  let list = null;
  const loadButton = folder.add(ui, 'load').name('Load selected');
  folder.add(ui, 'remove').name('Delete selected');
  folder.add(ui, 'exportFile').name('Export all to file…');
  folder.add(ui, 'importFile').name('Import from file…');
  const status = folder.add(ui, 'status').name('').disable();
  refreshList();

  function setStatus(text) {
    ui.status = text;
    status.updateDisplay();
  }

  // lil-gui can only change a dropdown's options by replacing the controller (it is re-added
  // at the end of the folder), so move the new one back above the Load button.
  function refreshList() {
    const names = store.names();
    if (!names.includes(ui.selected)) ui.selected = names[0] ?? '';
    const options = names.length ? names : [''];
    const next = list ? list.options(options) : folder.add(ui, 'selected', options);
    next.name('Saved presets').onChange((name) => { if (name) { ui.name = name; nameBox.updateDisplay(); } });
    folder.$children.insertBefore(next.domElement, loadButton.domElement);
    list = next;
  }

  function save(name) {
    let state;
    try {
      state = gui.save();
    } catch (e) {
      // gui.save() refuses two controllers with the same label in one folder.
      setStatus(`could not save: ${e.message}`);
      return false;
    }
    const stored = store.put(name, state);
    ui.selected = String(name).trim();
    refreshList();
    setStatus(stored ? `saved "${ui.selected}"` : `saved "${ui.selected}" for this session only (storage unavailable; export to keep it)`);
    return true;
  }

  function load(name) {
    const state = store.get(name);
    if (!state) { setStatus('no preset selected'); return false; }
    if (!isServerReady()) {
      pending = name;
      setStatus(`will load "${name}" once connected`);
      return true;
    }
    // Strip again in case the preset came from an older file that still has Pause in it.
    gui.load(stripPreset(state));
    setStatus(`loaded "${name}"`);
    return true;
  }

  function exportFile() {
    const blob = new Blob([store.exportJson()], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `ant-fugue-presets-${new Date().toISOString().slice(0, 10)}.json`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  function importFile() {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = 'application/json,.json';
    input.addEventListener('change', async () => {
      const file = input.files?.[0];
      if (!file) return;
      try {
        const count = store.importJson(await file.text());
        refreshList();
        setStatus(`imported ${count} preset${count === 1 ? '' : 's'}`);
      } catch (e) {
        setStatus(`import failed: ${e.message}`);
      }
    });
    input.click();
  }

  return {
    save,
    load,
    names: () => store.names(),
    /** Called by the panel once the server's sliders exist, to apply a preset asked for earlier. */
    serverParamsArrived() {
      if (pending) { const name = pending; pending = null; load(name); }
    },
  };
}
