// Named presets for the tuning panel, as plain data with no DOM or lil-gui code, so it can be
// tested under Node. ui/tuning.js connects it to the panel.
//
// A preset is exactly what lil-gui's gui.save() returns for the whole panel:
//   { controllers: { <label>: value, ... }, folders: { <folder title>: { controllers, folders } } }
// so every folder anyone adds to the panel (the server's parameter groups, View, piece D's
// Sound) is included without this code knowing about it. Controllers are keyed by their
// label, and folders by their title, because that is how lil-gui matches them on load.
//
// Some parts of the panel are controls, not settings, and are left out of presets (see
// EXCLUDED): the Presets folder itself (loading would change the preset being loaded) and the
// Pause checkbox (loading a preset should not pause or resume the colony).
//
// Presets are kept in localStorage under one key, as { name: preset }. Every access is wrapped
// in try/catch: storage can be missing or refuse writes (private windows, blocked site data,
// a full quota). If it fails, presets still work for this page's lifetime, held in memory,
// and the export button saves them to a JSON file so they are never lost.

export const STORAGE_KEY = 'antFugue.presets.v1';
export const FILE_FORMAT = 'ant-fugue-presets';
export const FILE_VERSION = 1;

/** Paths left out of every preset: a folder path, and optionally a controller in it. */
export const EXCLUDED = [
  { folders: ['Presets'] },
  { folders: ['Simulation'], controller: 'Pause' },
];

const clone = (value) => JSON.parse(JSON.stringify(value));

/** A copy of a gui.save() result with the EXCLUDED parts removed. */
export function stripPreset(state, excluded = EXCLUDED) {
  const out = clone(state);
  for (const rule of excluded) {
    let node = out;
    const path = rule.folders;
    const last = rule.controller === undefined ? path.length - 1 : path.length;
    for (let i = 0; i < last && node; i++) node = node.folders?.[path[i]];
    if (!node) continue;
    if (rule.controller === undefined) delete node.folders?.[path[last]];
    else delete node.controllers?.[rule.controller];
  }
  return out;
}

/** True if `state` has the shape of a gui.save() result (checked all the way down). */
export function isPresetState(state) {
  if (!state || typeof state !== 'object' || Array.isArray(state)) return false;
  const { controllers = {}, folders = {} } = state;
  if (typeof controllers !== 'object' || Array.isArray(controllers)) return false;
  if (typeof folders !== 'object' || Array.isArray(folders)) return false;
  for (const v of Object.values(controllers)) {
    const t = typeof v;
    if (t !== 'number' && t !== 'string' && t !== 'boolean') return false;
  }
  return Object.values(folders).every(isPresetState);
}

/** localStorage, or null if this page may not use it. */
export function browserStorage() {
  try {
    const s = globalThis.localStorage;
    return s ?? null;
  } catch {
    return null;
  }
}

/**
 * The preset store. `storage` is anything with getItem/setItem (localStorage, or a stand-in
 * in the tests); null keeps presets in memory only.
 */
export function createPresetStore(storage = browserStorage(), { onError = () => {} } = {}) {
  let presets = read();

  function read() {
    try {
      const text = storage?.getItem(STORAGE_KEY);
      if (!text) return {};
      const data = JSON.parse(text);
      const out = {};
      for (const [name, state] of Object.entries(data ?? {})) if (isPresetState(state)) out[name] = state;
      return out;
    } catch (e) {
      onError(e);
      return {};
    }
  }

  function write() {
    try {
      storage?.setItem(STORAGE_KEY, JSON.stringify(presets));
      return true;
    } catch (e) {
      onError(e);
      return false;
    }
  }

  return {
    /** Preset names, sorted. */
    names: () => Object.keys(presets).sort((a, b) => a.localeCompare(b)),
    get: (name) => (name in presets ? clone(presets[name]) : null),
    /** Saves a preset (a gui.save() result; the EXCLUDED parts are removed). Returns false if storage failed. */
    put(name, state) {
      const key = String(name).trim();
      if (!key) throw new Error('A preset needs a name');
      presets[key] = stripPreset(state);
      return write();
    },
    remove(name) {
      delete presets[name];
      return write();
    },
    /** Every preset as the text of a JSON file. */
    exportJson() {
      return JSON.stringify({ format: FILE_FORMAT, version: FILE_VERSION, presets }, null, 2);
    },
    /**
     * Adds the presets from an exported file (a preset with the same name is replaced).
     * Returns how many were imported; throws if the file is not a presets file.
     */
    importJson(text) {
      const data = JSON.parse(text);
      if (data?.format !== FILE_FORMAT || typeof data.presets !== 'object' || data.presets === null) {
        throw new Error('Not an Ant Fugue presets file');
      }
      if (data.version > FILE_VERSION) throw new Error(`Presets file version ${data.version} is newer than this app`);
      let count = 0;
      for (const [name, state] of Object.entries(data.presets)) {
        if (!isPresetState(state)) continue;
        presets[name] = stripPreset(state);
        count++;
      }
      write();
      return count;
    },
  };
}
