// The Sound folder of the tuning panel, built from the client parameter list (params.js), the
// same way the server's folders are built from the params message. It is added to the panel's
// lil-gui with panel.gui.addFolder('Sound'), so ui/tuning.js does not change.

import { CLIENT_PARAMS } from './params.js';
import { SLOT_COLORS } from './colors.js';

/** Builds the folder. `engine.setParam` applies every change. Returns { refresh } for presets. */
export function addSoundFolder(gui, engine) {
  const folder = gui.addFolder('Sound');
  const values = { ...engine.getParams() };
  const controllers = {};
  for (const spec of CLIENT_PARAMS) {
    let c;
    if (spec.options) {
      // lil-gui takes { label: value } for a dropdown.
      c = folder.add(values, spec.key, Object.fromEntries(Object.entries(spec.options).map(([v, l]) => [l, v])));
    } else if (typeof spec.default === 'boolean') {
      c = folder.add(values, spec.key);
    } else {
      c = folder.add(values, spec.key, spec.min, spec.max, spec.step);
    }
    const mute = /^mute(\d)$/.exec(spec.key);
    const name = mute
      ? `<span style="display:inline-block;width:.8em;height:.8em;border-radius:2px;margin-right:.4em;vertical-align:-.1em;background:${SLOT_COLORS[+mute[1]]}"></span>${spec.label}`
      : spec.label;
    c.name(name).onChange((v) => engine.setParam(spec.key, v));
    controllers[spec.key] = c;
  }
  return {
    folder,
    /** Re-reads every value from the engine (after a preset or setParam from elsewhere). */
    refresh() {
      Object.assign(values, engine.getParams());
      for (const c of Object.values(controllers)) c.updateDisplay();
    },
  };
}
