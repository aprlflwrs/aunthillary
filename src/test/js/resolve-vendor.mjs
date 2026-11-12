// Makes the browser's import map work under Node: the bare specifiers 'three' and 'lil-gui'
// resolve to the vendored copies, exactly as index.html maps them. Import this first, then
// import the client modules dynamically (static imports are resolved before any code runs).

import { registerHooks } from 'node:module';

const vendor = new URL('../../main/resources/static/vendor/', import.meta.url);
const map = {
  three: new URL('three/three.module.js', vendor).href,
  'lil-gui': new URL('lil-gui/lil-gui.module.min.js', vendor).href,
};

registerHooks({
  resolve(specifier, context, next) {
    if (specifier in map) return { url: map[specifier], format: 'module', shortCircuit: true };
    return next(specifier, context);
  },
});

export const STATIC = new URL('../../main/resources/static/', import.meta.url);
