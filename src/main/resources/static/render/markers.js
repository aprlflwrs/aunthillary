// The nest, the food piles, and the heading ring, all from the state message.
//
// The heading ring is the Symbols level's placeholder until Phase 4 (which adds the glowing
// lobes and the threads to the trails): a circle around the nest with one spoke per heading
// bin, as long as that bin's share of recent newborns, and a thin arc for each symbol at its
// direction and width, further out the stronger it is. It is drawn the same way as the
// heading ring in the 2D debug view.

import * as THREE from 'three';
import { simToX, simToZ } from './coords.js';

const MAX_FOOD = 256;
const CIRCLE_SEGMENTS = 64;
const MAX_SYMBOLS = 16;
const ARC_SEGMENTS = 16;
const RING_VERTICES = CIRCLE_SEGMENTS * 2 + 64 * 2 + MAX_SYMBOLS * ARC_SEGMENTS * 2;
const SPOKE_SCALE = 200; // world units of spoke per unit of share, as in the debug view

function unitCircle(segments) {
  const pts = [];
  for (let i = 0; i < segments; i++) {
    const a = (i / segments) * 2 * Math.PI;
    pts.push(Math.cos(a), 0, -Math.sin(a));
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(pts, 3));
  return g;
}

export function createMarkers() {
  const group = new THREE.Group();

  // Nest: a circle at the hub's edge.
  const nest = new THREE.LineLoop(unitCircle(CIRCLE_SEGMENTS),
    new THREE.LineBasicMaterial({ color: 0xffffff, transparent: true, opacity: 0.55, depthWrite: false }));
  nest.renderOrder = 3;
  nest.visible = false;
  group.add(nest);

  // Food: one small flat diamond per pile, sized by how much is left.
  const diamond = new THREE.BufferGeometry();
  diamond.setAttribute('position', new THREE.Float32BufferAttribute([
    1, 0, 0, 0, 0, -1, -1, 0, 0,
    1, 0, 0, -1, 0, 0, 0, 0, 1,
  ], 3));
  const food = new THREE.InstancedMesh(diamond,
    new THREE.MeshBasicMaterial({ color: 0xffd43b, side: THREE.DoubleSide, depthWrite: false, transparent: true }), MAX_FOOD);
  food.frustumCulled = false;
  food.count = 0;
  food.renderOrder = 3;
  group.add(food);
  const m = new THREE.Matrix4();

  // Heading ring: one LineSegments buffer for the circle, the spokes and the symbol arcs.
  // Its group is lifted when the view tilts (the top plane of the stacked view).
  const ring = new THREE.Group();
  const ringPositions = new Float32Array(RING_VERTICES * 3);
  const ringGeometry = new THREE.BufferGeometry();
  ringGeometry.setAttribute('position', new THREE.BufferAttribute(ringPositions, 3).setUsage(THREE.DynamicDrawUsage));
  ringGeometry.setDrawRange(0, 0);
  const ringMaterial = new THREE.LineBasicMaterial({ color: 0xffffff, transparent: true, opacity: 0, depthWrite: false });
  const ringLines = new THREE.LineSegments(ringGeometry, ringMaterial);
  ringLines.frustumCulled = false;
  ringLines.renderOrder = 4;
  ring.add(ringLines);
  group.add(ring);
  let ringBaseY = 0; // the ground height at the nest
  let ringLift = 0;  // how far the stacked view lifts the ring above that

  /**
   * Updates everything from a state message. `groundY(x, y)` gives the displayed ground
   * height at a simulation point, so markers sit on the terrain.
   */
  function setState(state, groundY) {
    const { x, y, hubRadius } = state.nest;
    const nx = simToX(x), nz = simToZ(y);
    nest.position.set(nx, groundY(x, y) + 0.8, nz);
    nest.scale.setScalar(hubRadius);
    nest.visible = true;

    const piles = state.food.slice(0, MAX_FOOD);
    piles.forEach((f, i) => {
      const s = Math.min(9, 2.5 + Math.sqrt(Math.max(0, f.amount)) * 0.12);
      m.makeScale(s, 1, s).setPosition(simToX(f.x), groundY(f.x, f.y) + 0.9, simToZ(f.y));
      food.setMatrixAt(i, m);
    });
    food.count = piles.length;
    food.instanceMatrix.needsUpdate = true;

    // The ring is centred on the nest, in its own group so it can be lifted.
    ringBaseY = groundY(x, y) + 1;
    ring.position.set(nx, ringBaseY + ringLift, nz);
    let v = 0;
    const put = (r, a) => {
      ringPositions[v * 3] = r * Math.cos(a);
      ringPositions[v * 3 + 1] = 0;
      ringPositions[v * 3 + 2] = -r * Math.sin(a);
      v++;
    };
    const r0 = hubRadius * 2;
    for (let i = 0; i < CIRCLE_SEGMENTS; i++) {
      put(r0, (i / CIRCLE_SEGMENTS) * 2 * Math.PI);
      put(r0, ((i + 1) / CIRCLE_SEGMENTS) * 2 * Math.PI);
    }
    const bins = state.headings.slice(0, 64);
    bins.forEach((share, b) => {
      const a = (b / bins.length) * 2 * Math.PI; // bin 0 is centred on east, counter-clockwise
      put(r0, a);
      put(r0 + share * SPOKE_SCALE, a);
    });
    for (const sym of state.symbols.slice(0, MAX_SYMBOLS)) {
      const r = r0 + 8 + sym.strength * 60;
      for (let k = 0; k < ARC_SEGMENTS; k++) {
        put(r, sym.direction - sym.width / 2 + (k / ARC_SEGMENTS) * sym.width);
        put(r, sym.direction - sym.width / 2 + ((k + 1) / ARC_SEGMENTS) * sym.width);
      }
    }
    ringGeometry.setDrawRange(0, v);
    ringGeometry.attributes.position.needsUpdate = true;
  }

  function setRingOpacity(o) {
    ringMaterial.opacity = o;
    ringLines.visible = o > 0.001;
  }

  /** Lifts the ring above the ground, for the stacked view. */
  function setRingLift(lift) {
    ringLift = lift;
    ring.position.y = ringBaseY + ringLift;
  }

  return { group, setState, setRingOpacity, setRingLift };
}
