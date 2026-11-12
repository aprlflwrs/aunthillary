// The colour of each voice slot (0–3), shared by everything that shows a voice: the trail
// ribbons, the piano roll, and anything in audio/ that wants to label a voice. A voice keeps
// its slot for its whole life (see Interfaces in ProjectPlan.md), so it keeps its colour too.
//
// Plain hex strings with no three.js import, so any module (or a Node test) can use them.
// They match the colours in debug/view2d.js.

export const SLOT_COLORS = Object.freeze(['#e6194b', '#3cb44b', '#4363d8', '#f58231']);

/** The colour for a slot, as a CSS hex string. Out-of-range slots wrap rather than fail. */
export function slotColor(slot) {
  const n = SLOT_COLORS.length;
  return SLOT_COLORS[((slot % n) + n) % n];
}
