// The level of description the viewer has chosen: a continuous value from 0 (Ants) through
// 1 (Signals) to 2 (Symbols). The slider in ui/viewControls.js sets it; anything that should
// follow it subscribes here. This module has no DOM code and imports nothing, so the sound
// engine can use it without depending on the renderer.
//
//   import { getLevel, onLevelChange } from './ui/level.js';
//   const stop = onLevelChange((level) => engine.setLevel(level));   // Phase 2 wires this
//
// The callback is also called once straight away with the current level, so a late
// subscriber starts in step. onLevelChange returns a function that unsubscribes.

let level = 0;
const listeners = new Set();

export const getLevel = () => level;

/** Sets the level (clamped to 0..2) and tells every subscriber, if it changed. */
export function setLevel(value) {
  const n = Number(value);
  const v = Math.min(2, Math.max(0, Number.isFinite(n) ? n : 0));
  if (v === level) return;
  level = v;
  for (const cb of listeners) cb(level);
}

export function onLevelChange(cb) {
  listeners.add(cb);
  cb(level);
  return () => { listeners.delete(cb); };
}
