// Turning: the melody comes from how the trail bends, not from where it points.
//   - the scale degree is the trail's heading at the point relative to its heading where it
//     leaves the hub: one step up for every 22.5° it has turned left (counterclockwise), one step
//     down for every 22.5° it has turned right. A straight run repeats a note; a trail swinging
//     round a rock climbs or falls with it. Net turning (not curvature) keeps this a pure
//     function of the point and steady when the curve is re-smoothed.
//   - where the trail is thin (little food pheromone), the voice rests, so a busy trail plays
//     continuously and a fading one breaks up;
//   - loudness follows the pheromone strength; the more the trail has turned either way, the
//     brighter the stop.

/** Radians of net turning per scale step (22.5°). */
const RADIANS_PER_DEGREE = Math.PI / 8;
/** Below this point strength (0–1) the voice rests. */
const REST_BELOW = 0.2;

const clamp01 = (v) => Math.min(1, Math.max(0, v));

export default {
  id: 'turning',
  label: 'Turning',
  noteAt(p) {
    if (p.strength < REST_BELOW) return null;
    return {
      degree: Math.round(p.turning / RADIANS_PER_DEGREE),
      velocity: 0.35 + 0.6 * clamp01(p.strength),
      length: 1,
      timbre: clamp01(Math.abs(p.turning) / Math.PI),
    };
  },
};
