// One colour per voice slot (0–3), used by the piano roll. These are the same colours as
// debug/view2d.js. Piece C is building a shared slot-colour module in ui/; Phase 2 should point
// this file, the debug view and the renderer at that one module so the colours cannot drift apart.

export const SLOT_COLORS = ['#e6194b', '#3cb44b', '#4363d8', '#f58231'];
