// Follows the voices from one state message to the next. The server sends full state, not
// events, so entries and fades are worked out here by comparing successive states (see
// "ColonyState" in the plan):
//   - a voice enters when its id first appears;
//   - a voice is fading while its strength falls from one state to the next (a fading voice
//     stays in the list with falling strength until it is gone);
//   - a voice exits when its id is no longer in the list.
// At most four voices play, one per slot (0–3). The server already keeps to that; if a message
// ever had more, voices that are already playing keep their slots and the strongest of the rest
// fill any free ones.
//
// Each tracked voice has an entry step: the step of the 8th-note grid that counts as its first
// note. So that voices which were already playing when the page joined do not all start their
// phrases together, the entry step is set back by the time the voice has existed on the server
// (from bornTick). A voice that is born while we listen therefore enters on the next step.

export const MAX_VOICES = 4;
/** Server ticks per second (World.DT is 1/30 s). */
export const TICKS_PER_SECOND = 30;
/** Strength must fall by more than this between states to count as fading (ignores jitter). */
export const FADE_EPSILON = 0.002;

export function createVoiceTracker() {
  /** id → { id, slot, entryStep, strength, fading, voice } */
  const active = new Map();

  /**
   * Compares a new list of voices with the last one.
   * `nextStep` is the next grid step to be booked, `stepDuration` its length in seconds, and
   * `tick` the state message's tick.
   * Returns { entered, faded, exited } as arrays of ids, and updates `active`.
   */
  function update(voices, { tick, nextStep, stepDuration }) {
    const chosen = choose(voices ?? []);
    const entered = [], faded = [], exited = [];

    for (const id of [...active.keys()]) {
      if (!chosen.has(id)) { active.delete(id); exited.push(id); }
    }
    for (const [id, voice] of chosen) {
      const rec = active.get(id);
      if (!rec) {
        const ageSeconds = Math.max(0, (tick - (voice.bornTick ?? tick)) / TICKS_PER_SECOND);
        active.set(id, {
          id, slot: voice.slot, voice, strength: voice.strength ?? 0, fading: false,
          entryStep: nextStep - Math.floor(ageSeconds / stepDuration + 1e-9), // (guard: 6 / 0.3 is 19.999…)
        });
        entered.push(id);
      } else {
        const strength = voice.strength ?? 0;
        const fading = strength < rec.strength - FADE_EPSILON;
        if (fading && !rec.fading) faded.push(id);
        // A voice whose strength holds steady keeps its last state; one that rises is not fading.
        rec.fading = fading || (rec.fading && strength <= rec.strength + FADE_EPSILON);
        rec.strength = strength;
        rec.voice = voice;
      }
    }
    return { entered, faded, exited };
  }

  // Picks at most one voice per slot 0–3: already-playing voices first, then the strongest.
  function choose(voices) {
    const valid = voices.filter((v) => Number.isInteger(v.slot) && v.slot >= 0 && v.slot < MAX_VOICES);
    const ordered = [...valid].sort((a, b) =>
      (active.has(b.id) - active.has(a.id)) || ((b.strength ?? 0) - (a.strength ?? 0)));
    const bySlot = new Map();
    const out = new Map();
    for (const v of ordered) {
      if (out.has(v.id)) continue;
      // A tracked voice keeps the slot it entered with, even if a message says otherwise.
      const rec = active.get(v.id);
      const slot = rec ? rec.slot : v.slot;
      if (bySlot.has(slot)) continue;
      bySlot.set(slot, v.id);
      out.set(v.id, rec ? { ...v, slot } : v);
    }
    return out;
  }

  return { active, update };
}
