// Tests for the renderer's pure logic (coordinates, ant matrices, ground and ribbon geometry,
// camera maths, level weights, the level API), checked where possible against three.js itself
// so the hand-written maths and the real cameras agree. Also imports every render/ and ui/
// module, which catches a wrong import or export name that an HTTP check would not.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { STATIC } from './resolve-vendor.mjs';
import { installFakeDom } from './fake-dom.mjs';

installFakeDom();
const THREE = await import('three');
const coords = await import(new URL('render/coords.js', STATIC));
const geometry = await import(new URL('render/geometry.js', STATIC));
const rig = await import(new URL('render/cameraRig.js', STATIC));
const { levelWeights, levelName } = await import(new URL('render/levels.js', STATIC));
const { applyPose } = await import(new URL('render/scene.js', STATIC));
const level = await import(new URL('ui/level.js', STATIC));
const { SLOT_COLORS, slotColor } = await import(new URL('ui/slotColors.js', STATIC));

const close = (a, b, eps = 1e-4, msg = '') => assert.ok(Math.abs(a - b) < eps, `${msg} ${a} ≉ ${b}`);

test('every render/ and ui/ module imports', async () => {
  for (const dir of ['render', 'ui']) {
    for (const file of readdirSync(new URL(dir, STATIC)).filter((f) => f.endsWith('.js'))) {
      await import(new URL(`${dir}/${file}`, STATIC));
    }
  }
});

test('simulation ↔ three.js coordinates follow the plan', () => {
  // South-west corner, north-east corner, centre.
  assert.deepEqual([coords.simToX(0), coords.simToZ(0)], [-256, 256]);
  assert.deepEqual([coords.simToX(512), coords.simToZ(512)], [256, -256]);
  assert.deepEqual([coords.simToX(256), coords.simToZ(256)], [0, 0]);
  assert.equal(coords.xToSim(coords.simToX(123.5)), 123.5);
  assert.equal(coords.zToSim(coords.simToZ(77.25)), 77.25);
});

test('heading bytes: 0 is east, 64 is north', () => {
  close(coords.HEADING_COS[0], 1); close(coords.HEADING_SIN[0], 0);
  close(coords.HEADING_COS[64], 0); close(coords.HEADING_SIN[64], 1);
  close(coords.HEADING_COS[128], -1);
});

test('ant matrix: heading π/2 points the ant north (−Z), and matches three.js', () => {
  const m = new Float32Array(16);
  coords.writeAntMatrix(m, 0, 10, 2, -30, coords.HEADING_COS[64], coords.HEADING_SIN[64], 2);
  const forward = new THREE.Vector3(1, 0, 0).applyMatrix4(new THREE.Matrix4().fromArray(m));
  close(forward.x, 10); close(forward.y, 2); close(forward.z, -30 - 2);

  // The same matrix built by three.js from position, rotation about +Y and scale.
  for (const byte of [0, 13, 64, 100, 200]) {
    const angle = (byte / 256) * 2 * Math.PI;
    coords.writeAntMatrix(m, 0, 1, 2, 3, coords.HEADING_COS[byte], coords.HEADING_SIN[byte], 1.5);
    const expected = new THREE.Matrix4().compose(
      new THREE.Vector3(1, 2, 3),
      new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(0, 1, 0), angle),
      new THREE.Vector3(1.5, 1.5, 1.5));
    expected.elements.forEach((e, i) => close(m[i], e, 1e-5, `byte ${byte} element ${i}`));
  }
});

test('heightAt interpolates the terrain grid (rows south to north)', () => {
  // 2 × 2 grid: south-west 0, south-east 1, north-west 2, north-east 4 (the protocol fixture).
  const h = [0, 1, 2, 4];
  close(coords.heightAt(h, 2, 0, 0), 0);
  close(coords.heightAt(h, 2, 512, 0), 1);
  close(coords.heightAt(h, 2, 0, 512), 2);
  close(coords.heightAt(h, 2, 512, 512), 4);
  close(coords.heightAt(h, 2, 256, 256), 7 / 4);
  close(coords.heightAt(h, 2, -50, 900), 2, 1e-6, 'clamped to the edge');
  assert.equal(coords.heightAt(null, 0, 1, 1), 0);
});

test('ground: row 0 is south, v = 0 is south, u = 0 is west, faces point up', () => {
  const n = 3;
  const heights = [0, 0, 0, 0, 5, 0, 0, 0, 0];
  const g = geometry.buildGround(n, heights, [0, 0, 0, 0, 1, 0, 0, 0, 0], 2);
  // Vertex 0 is the south-west corner: X = −256, Z = +256 (south), uv (0, 0).
  assert.deepEqual([...g.positions.slice(0, 3)], [-256, 0, 256]);
  assert.deepEqual([...g.uvs.slice(0, 2)], [0, 0]);
  // The last vertex is the north-east corner.
  assert.deepEqual([...g.positions.slice(-3)], [256, 0, -256]);
  assert.deepEqual([...g.uvs.slice(-2)], [1, 1]);
  // The middle vertex has its height × relief, and is the blocked one.
  assert.equal(g.positions[4 * 3 + 1], 10);
  assert.equal(g.blocked[4], 1);
  // A pheromone byte in row r, column c of the frame sits at uv ((c + 0.5)/n, (r + 0.5)/n)
  // of a DataTexture (row 0 is v = 0), so the uv of a world point must be (x/512, y/512).
  // Vertex (row 1, col 2): x = 512, y = 256.
  const i = 1 * n + 2;
  assert.deepEqual([g.uvs[i * 2], g.uvs[i * 2 + 1]], [1, 0.5]);
  // Every triangle faces +Y.
  const p = g.positions;
  for (let t = 0; t < g.index.length; t += 3) {
    const [a, b, c] = [g.index[t], g.index[t + 1], g.index[t + 2]].map((k) => new THREE.Vector3(p[k * 3], 0, p[k * 3 + 2]));
    const normal = new THREE.Vector3().crossVectors(b.clone().sub(a), c.clone().sub(a));
    assert.ok(normal.y > 0, `triangle ${t / 3} faces down`);
  }
});

test('ribbon: a trail running east gets its left edge on the north side', () => {
  const points = [[100, 200, 1, 0.5], [102, 200, 1, 0.6], [104, 200, 1, 0.7]];
  const max = 8;
  const pos = new Float32Array(max * 6), str = new Float32Array(max * 2), along = new Float32Array(max * 2);
  const count = geometry.writeRibbon(points, 4, 0.5, 2, max, pos, str, along);
  assert.equal(count, 3);
  // First point: left vertex 2 units north (Z smaller), right vertex 2 units south.
  close(pos[0], coords.simToX(100)); close(pos[2], coords.simToZ(202));
  close(pos[3], coords.simToX(100)); close(pos[5], coords.simToZ(198));
  close(pos[1], 1 * 2 + 0.5, 1e-6, 'height × relief + lift');
  assert.deepEqual([...str.slice(0, 6)].map((v) => +v.toFixed(3)), [0.5, 0.5, 0.6, 0.6, 0.7, 0.7]);
  assert.deepEqual([...along.slice(0, 6)], [0, 0, 0.5, 0.5, 1, 1]);
  assert.equal(geometry.writeRibbon([[0, 0, 0, 1]], 4, 0, 1, max, pos, str, along), 0, 'one point is no ribbon');
  assert.equal(geometry.ribbonIndex(3).length, 12);
});

test('top-down camera: north at the top, east at the right', () => {
  const s = rig.createCameraState();
  s.targetX = 10; s.targetZ = -20; s.viewHeight = 100;
  const aspect = 2;
  const centre = rig.groundPointAt(s, aspect, 0, 0);
  close(centre.X, 10); close(centre.Z, -20);
  const top = rig.groundPointAt(s, aspect, 0, 1);
  close(top.Z, -20 - 50, 1e-6, 'the top edge is 50 units north (−Z)');
  const right = rig.groundPointAt(s, aspect, 1, 0);
  close(right.X, 10 + 100, 1e-6, 'the right edge is 100 units east');
});

test('zooming keeps the point under the cursor fixed, in both cameras', () => {
  for (const tilt of [0, 0.5, 1]) {
    const s = rig.createCameraState();
    s.tilt = tilt;
    const before = { ...rig.groundPointAt(s, 1.5, 0.4, -0.3) };
    rig.zoomAt(s, 1.5, 0.4, -0.3, 0.5);
    const after = rig.groundPointAt(s, 1.5, 0.4, -0.3);
    close(after.X, before.X, 1e-3, `tilt ${tilt} X`);
    close(after.Z, before.Z, 1e-3, `tilt ${tilt} Z`);
    assert.ok(s.viewHeight < rig.createCameraState().viewHeight);
  }
});

test('panning moves the map with the pointer', () => {
  for (const tilt of [0, 0.7]) {
    const s = rig.createCameraState();
    s.tilt = tilt;
    const grabbed = { ...rig.groundPointAt(s, 1, -0.2, 0.1) };
    rig.panBy(s, 1, -0.2, 0.1, 0.3, -0.2);
    const now = rig.groundPointAt(s, 1, 0.3, -0.2);
    close(now.X, grabbed.X, 1e-3, `tilt ${tilt} X`);
    close(now.Z, grabbed.Z, 1e-3, `tilt ${tilt} Z`);
  }
});

test('zoom is clamped', () => {
  const s = rig.createCameraState();
  for (let i = 0; i < 100; i++) rig.zoomAt(s, 1, 0, 0, 0.5);
  assert.equal(s.viewHeight, rig.MIN_VIEW_HEIGHT);
  for (let i = 0; i < 100; i++) rig.zoomAt(s, 1, 0, 0, 2);
  assert.equal(s.viewHeight, rig.MAX_VIEW_HEIGHT);
});

test('the camera maths agree with three.js raycasting through the real cameras', () => {
  const ortho = new THREE.OrthographicCamera();
  const persp = new THREE.PerspectiveCamera();
  const raycaster = new THREE.Raycaster();
  const plane = new THREE.Plane(new THREE.Vector3(0, 1, 0), 0);
  const hit = new THREE.Vector3();
  for (const tilt of [0, 0.3, 1]) {
    const s = rig.createCameraState();
    s.tilt = tilt; s.targetX = 40; s.targetZ = -15; s.viewHeight = 200;
    const aspect = 1.6;
    const camera = applyPose(rig.cameraPose(s, aspect), ortho, persp);
    assert.equal(camera.isPerspectiveCamera === true, tilt > 0);
    for (const [nx, ny] of [[0, 0], [0.9, 0.9], [-0.5, 0.2], [0.3, -0.8]]) {
      raycaster.setFromCamera(new THREE.Vector2(nx, ny), camera);
      assert.ok(raycaster.ray.intersectPlane(plane, hit));
      const mine = rig.groundPointAt(s, aspect, nx, ny);
      close(mine.X, hit.x, 1e-2, `tilt ${tilt} (${nx}, ${ny}) X`);
      close(mine.Z, hit.z, 1e-2, `tilt ${tilt} (${nx}, ${ny}) Z`);
    }
    if (tilt === 0) {
      // Looking straight down with north at the top of the screen.
      const dir = camera.getWorldDirection(new THREE.Vector3());
      close(dir.y, -1);
      const screenUp = new THREE.Vector3(0, 1, 0).transformDirection(camera.matrixWorld);
      close(screenUp.z, -1, 1e-6, 'screen up is −Z (north)');
    }
  }
});

test('level weights crossfade Ants → Signals → Symbols', () => {
  const a = { ...levelWeights(0) }, s = { ...levelWeights(1) }, y = { ...levelWeights(2) };
  assert.equal(a.ants, 1); assert.equal(a.ribbons, 0); assert.equal(a.symbols, 0);
  assert.ok(a.pheromone < s.pheromone, 'pheromone faint at Ants');
  assert.ok(s.ants < 0.3); assert.equal(s.ribbons, 1); assert.equal(s.symbols, 0);
  assert.ok(y.ribbons < 0.3); assert.equal(y.symbols, 1);
  const half = levelWeights(0.5);
  close(half.ants, (a.ants + s.ants) / 2);
  close(half.ribbons, 0.5);
  const out = levelWeights(1.5, {});
  close(out.symbols, 0.5);
  assert.deepEqual(levelWeights(-3), levelWeights(0), 'clamped below');
  assert.deepEqual(levelWeights(9), levelWeights(2), 'clamped above');
  assert.equal(levelName(0.4), 'Ants'); assert.equal(levelName(1.2), 'Signals'); assert.equal(levelName(1.9), 'Symbols');
});

test('level API: get, set (clamped), subscribe, unsubscribe', () => {
  const seen = [];
  const stop = level.onLevelChange((l) => seen.push(l));
  assert.deepEqual(seen, [level.getLevel()], 'called once straight away');
  level.setLevel(1.25);
  level.setLevel(1.25); // unchanged: no call
  level.setLevel(5);
  assert.equal(level.getLevel(), 2);
  stop();
  level.setLevel(0);
  assert.deepEqual(seen.slice(1), [1.25, 2]);
});

test('slot colours are shared and wrap', () => {
  assert.equal(SLOT_COLORS.length, 4);
  assert.equal(slotColor(0), SLOT_COLORS[0]);
  assert.equal(slotColor(5), SLOT_COLORS[1]);
  assert.equal(slotColor(-1), SLOT_COLORS[3]);
});

test('scene parts build and take messages without a GPU', async () => {
  const { createAnts, MAX_ANTS, antColorHex } = await import(new URL('render/ants.js', STATIC));
  const { createTerrain } = await import(new URL('render/terrain.js', STATIC));
  const { createRibbons } = await import(new URL('render/ribbons.js', STATIC));
  const { createMarkers } = await import(new URL('render/markers.js', STATIC));

  // 30,000 ants, as the fake colony sends them.
  const n = 30_000;
  const frame = {
    count: n,
    x: new Uint16Array(n).map((_, i) => (i * 7919) % 65536),
    y: new Uint16Array(n).map((_, i) => (i * 104729) % 65536),
    heading: new Uint8Array(n).map((_, i) => i % 256),
    flags: new Uint8Array(n).map((_, i) => i % 8),
  };
  const ants = createAnts();
  assert.equal(ants.mesh.instanceMatrix.count, MAX_ANTS);
  assert.equal(ants.mesh.frustumCulled, false);
  assert.equal(ants.mesh.instanceMatrix.usage, THREE.DynamicDrawUsage);
  const terrainFrame = { n: 2, maxHeight: 4, heights: new Float32Array([0, 1, 2, 4]), blocked: new Uint8Array(4) };
  const t0 = performance.now();
  for (let k = 0; k < 30; k++) ants.update(frame, terrainFrame, 1, 1.6);
  const msPerFrame = (performance.now() - t0) / 30;
  console.log(`ant buffer update: ${msPerFrame.toFixed(2)} ms per frame of ${n} ants (CPU only)`);
  assert.equal(ants.mesh.count, n);
  assert.deepEqual(ants.mesh.instanceMatrix.updateRanges, [{ start: 0, count: n * 16 }]);
  // Ant 2 has flags 2 (carrying food): it is orange.
  const orange = new THREE.Color(antColorHex(2));
  close(ants.mesh.instanceColor.array[2 * 3], orange.r);
  assert.equal(antColorHex(3), antColorHex(2), 'carrying food wins over returning');
  assert.notEqual(antColorHex(4), antColorHex(0), 'scouts look different');
  // Ant 0 is at the south-west corner, on the ground (height 0) plus its lift.
  const m = ants.mesh.instanceMatrix.array;
  close(m[12], -256); close(m[14], 256); close(m[13], 0.3);
  // More ants than the mesh holds are clipped, not an error.
  ants.update({ ...frame, count: MAX_ANTS + 5, x: new Uint16Array(MAX_ANTS + 5), y: new Uint16Array(MAX_ANTS + 5),
    heading: new Uint8Array(MAX_ANTS + 5), flags: new Uint8Array(MAX_ANTS + 5) }, null, 1, 1);
  assert.equal(ants.mesh.count, MAX_ANTS);

  const terrain = createTerrain();
  terrain.setTerrain({ n: 128, maxHeight: 4, heights: new Float32Array(128 * 128).fill(1), blocked: new Uint8Array(128 * 128) });
  assert.equal(terrain.mesh.geometry.attributes.position.count, 128 * 128);
  const food = new Uint8Array(128 * 128).fill(9);
  terrain.setPheromone({ n: 128, food, home: new Uint8Array(128 * 128) });
  assert.equal(terrain.uniforms.foodMap.value.image.data, food, 'the texture uses the received bytes directly');
  assert.equal(terrain.uniforms.foodMap.value.format, THREE.RedFormat);
  const food2 = new Uint8Array(128 * 128);
  const texture = terrain.uniforms.foodMap.value;
  terrain.setPheromone({ n: 128, food: food2, home: new Uint8Array(128 * 128) });
  assert.equal(terrain.uniforms.foodMap.value, texture, 'same size: the texture is reused');

  const ribbons = createRibbons();
  const voice = (slot) => ({ id: slot, slot, strength: 0.8, points: [[256, 256, 0, 1], [258, 257, 0, 1], [260, 259, 0, 1]] });
  ribbons.setVoices([voice(0), voice(2)], 3, 1);
  assert.equal(ribbons.group.children.length, 2);
  ribbons.setVoices([voice(1)], 3, 1);
  assert.equal(ribbons.group.children.filter((r) => r.visible).length, 1);
  ribbons.setOpacity(1);
  assert.equal(ribbons.group.children.filter((r) => r.visible).length, 1, 'a hidden ribbon stays hidden');

  const markers = createMarkers();
  markers.setState({
    nest: { x: 256, y: 256, hubRadius: 12 }, food: [{ id: 1, x: 400, y: 300, amount: 500 }],
    headings: new Array(16).fill(1 / 16), symbols: [{ direction: 0.8, width: 0.6, strength: 0.4 }], voices: [],
  }, () => 0);
});
