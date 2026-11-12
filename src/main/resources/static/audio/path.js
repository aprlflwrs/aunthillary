// Geometry of a voice's path: the curve the melody walks along. A path is built once per state
// message from the voice's points ([x, y, height, strength], about every 2 world units, starting
// at the hub's edge), and then sampled at any distance along it.
//
// Headings follow the plan's convention (0 is east, counterclockwise is positive) and are
// "unwrapped": instead of jumping from +π to −π, a heading keeps counting past π, so the
// difference between two headings is always the real amount the path turned.

/** Wraps an angle into (−π, π]. */
export function wrapAngle(a) {
  let r = a % (2 * Math.PI);
  if (r <= -Math.PI) r += 2 * Math.PI;
  if (r > Math.PI) r -= 2 * Math.PI;
  return r;
}

/**
 * Builds a sampled path from [x, y, height, strength] points. Repeated points are dropped
 * (they have no direction). Returns arrays indexed by point: s (distance along the path),
 * x, y, height, strength, heading (unwrapped), curvature (change of heading per world unit),
 * plus the total length.
 */
export function buildPath(points) {
  const pts = [];
  for (const p of points ?? []) {
    const last = pts[pts.length - 1];
    if (!last || Math.hypot(p[0] - last[0], p[1] - last[1]) > 1e-6) pts.push(p);
  }
  const n = pts.length;
  const s = new Float64Array(n);
  const x = new Float64Array(n), y = new Float64Array(n);
  const height = new Float64Array(n), strength = new Float64Array(n);
  const heading = new Float64Array(n), curvature = new Float64Array(n);
  for (let i = 0; i < n; i++) {
    x[i] = pts[i][0]; y[i] = pts[i][1];
    height[i] = pts[i][2] ?? 0; strength[i] = pts[i][3] ?? 0;
    if (i > 0) s[i] = s[i - 1] + Math.hypot(x[i] - x[i - 1], y[i] - y[i - 1]);
  }
  if (n >= 2) {
    // The heading at a point is the direction from its neighbour behind to its neighbour ahead
    // (one-sided at the two ends), which is steadier than either segment alone.
    for (let i = 0; i < n; i++) {
      const a = Math.max(0, i - 1), b = Math.min(n - 1, i + 1);
      const raw = Math.atan2(y[b] - y[a], x[b] - x[a]);
      heading[i] = i === 0 ? raw : heading[i - 1] + wrapAngle(raw - heading[i - 1]);
    }
    for (let i = 0; i < n; i++) {
      const a = Math.max(0, i - 1), b = Math.min(n - 1, i + 1);
      const ds = s[b] - s[a];
      curvature[i] = ds > 0 ? (heading[b] - heading[a]) / ds : 0;
    }
  }
  return { n, s, x, y, height, strength, heading, curvature, length: n ? s[n - 1] : 0 };
}

/**
 * The path at distance `at` along it (clamped to the ends), linearly interpolated between the
 * two neighbouring points. `turning` is the heading there relative to the heading at the start.
 */
export function sampleAt(path, at) {
  const { n, s } = path;
  if (n === 0) return null;
  const d = Math.min(Math.max(at, 0), path.length);
  // Binary search for the segment [i, i + 1] that contains d.
  let lo = 0, hi = Math.max(0, n - 2);
  while (lo < hi) {
    const mid = (lo + hi + 1) >> 1;
    if (s[mid] <= d) lo = mid; else hi = mid - 1;
  }
  const i = lo, j = Math.min(n - 1, i + 1);
  const span = s[j] - s[i];
  const f = span > 0 ? (d - s[i]) / span : 0;
  const lerp = (arr) => arr[i] + (arr[j] - arr[i]) * f;
  const heading = lerp(path.heading);
  return {
    s: d,
    x: lerp(path.x), y: lerp(path.y),
    height: lerp(path.height), strength: lerp(path.strength),
    heading: wrapAngle(heading),
    turning: heading - path.heading[0],
    curvature: lerp(path.curvature),
  };
}

/**
 * How much a path bends on average: the total absolute change of direction between successive
 * segments, divided by the length (radians per world unit). The noise path source matches this
 * number, so both path sources are measured by the same function.
 */
export function meanBending(points) {
  let total = 0, length = 0, prev = null;
  for (let i = 1; i < points.length; i++) {
    const dx = points[i][0] - points[i - 1][0], dy = points[i][1] - points[i - 1][1];
    const l = Math.hypot(dx, dy);
    if (l < 1e-9) continue;
    const dir = Math.atan2(dy, dx);
    if (prev !== null) total += Math.abs(wrapAngle(dir - prev));
    prev = dir;
    length += l;
  }
  return length > 0 ? total / length : 0;
}

/** Length of a polyline of points. */
export function polylineLength(points) {
  let length = 0;
  for (let i = 1; i < points.length; i++) {
    length += Math.hypot(points[i][0] - points[i - 1][0], points[i][1] - points[i - 1][1]);
  }
  return length;
}

/**
 * Resamples a polyline to points every `spacing` world units, ending exactly at `maxLength`
 * (or at the polyline's end, if that comes first). All four fields are interpolated.
 */
export function resample(points, spacing, maxLength = Infinity) {
  if (points.length < 2) return points.map((p) => [...p]);
  const out = [[...points[0]]];
  let want = spacing;           // distance along the polyline of the next output point
  let walked = 0;               // distance along the polyline at the start of segment i
  const end = Math.min(maxLength, polylineLength(points));
  for (let i = 1; i < points.length && want <= end + 1e-9; i++) {
    const a = points[i - 1], b = points[i];
    const l = Math.hypot(b[0] - a[0], b[1] - a[1]);
    while (l > 0 && want <= walked + l + 1e-9 && want <= end + 1e-9) {
      const f = (want - walked) / l;
      out.push(a.map((v, k) => v + ((b[k] ?? 0) - v) * f));
      want += spacing;
    }
    walked += l;
  }
  // Finish exactly at the end, unless the last point already lies there.
  const last = out[out.length - 1];
  if (end - polylineLength(out) > 1e-6) {
    const tail = resampleAt(points, end);
    if (Math.hypot(tail[0] - last[0], tail[1] - last[1]) > 1e-6) out.push(tail);
  }
  return out;
}

function resampleAt(points, at) {
  let walked = 0;
  for (let i = 1; i < points.length; i++) {
    const a = points[i - 1], b = points[i];
    const l = Math.hypot(b[0] - a[0], b[1] - a[1]);
    if (walked + l >= at - 1e-9) {
      const f = l > 0 ? (at - walked) / l : 0;
      return a.map((v, k) => v + ((b[k] ?? 0) - v) * f);
    }
    walked += l;
  }
  return [...points[points.length - 1]];
}

/**
 * Smooths a polyline with `passes` rounds of a [1, 2, 1] / 4 average of each point and its
 * neighbours, keeping both ends fixed (so the path still starts at the hub's edge). Only x and y
 * are smoothed; height and strength are carried along.
 */
export function smooth(points, passes) {
  let cur = points.map((p) => [...p]);
  for (let k = 0; k < passes; k++) {
    const next = cur.map((p) => [...p]);
    for (let i = 1; i < cur.length - 1; i++) {
      next[i][0] = (cur[i - 1][0] + 2 * cur[i][0] + cur[i + 1][0]) / 4;
      next[i][1] = (cur[i - 1][1] + 2 * cur[i][1] + cur[i + 1][1]) / 4;
    }
    cur = next;
  }
  return cur;
}
