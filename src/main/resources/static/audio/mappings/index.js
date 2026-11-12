// The mappings, by id, in menu order. Adding a mapping means writing a module with the shape
// below and listing it here; the Sound folder's menu is built from this list.
//
//   export default {
//     id, label,
//     // Called once per 8th note for each voice. Must be pure: same input, same output.
//     noteAt(p) { return { degree, velocity, length, timbre } or null for a rest },
//   };
//
// See "Mappings and path sources" in ProjectPlan.md for what `p` holds. A mapping must not
// depend on which voice it is playing: p.voice deliberately carries no id or slot.

import compass from './compass.js';
import turning from './turning.js';
import distanceHeight from './distance-height.js';

export const MAPPINGS = { [compass.id]: compass, [turning.id]: turning, [distanceHeight.id]: distanceHeight };
