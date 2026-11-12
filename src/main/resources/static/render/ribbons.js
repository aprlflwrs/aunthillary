// The voices, drawn as flat coloured ribbons along their trail curves, one colour per slot
// (ui/slotColors.js, the same colours the piano roll uses).
//
// A small pool of ribbon meshes is kept, each with buffers big enough for MAX_POINTS points,
// and a state message (about twice a second) rewrites the ones in use and hides the rest.
// Within a ribbon, each vertex carries its point's pheromone strength, so a trail looks
// thinner where fewer ants walk it; the voice's own strength scales the whole ribbon, so a
// fading voice fades out.

import * as THREE from 'three';
import { slotColor } from '../ui/slotColors.js';
import { writeRibbon, ribbonIndex } from './geometry.js';

export const MAX_POINTS = 1024; // points come every ~2 world units, so ~2,000 units of trail

const vertexShader = /* glsl */ `
  attribute float strength;
  attribute float along;
  varying float vStrength;
  varying float vAlong;
  void main() {
    vStrength = strength;
    vAlong = along;
    gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
  }
`;

// Alpha: the level slider's ribbon opacity × the voice's strength × the point's strength,
// with a short fade-in at the hub end so ribbons don't start with a hard edge on the nest.
const fragmentShader = /* glsl */ `
  uniform vec3 color;
  uniform float opacity;
  uniform float voiceStrength;
  varying float vStrength;
  varying float vAlong;
  void main() {
    float a = opacity * (0.3 + 0.7 * voiceStrength) * (0.35 + 0.65 * vStrength);
    a *= smoothstep(0.0, 0.04, vAlong);
    gl_FragColor = vec4(color, a);
    #include <tonemapping_fragment>
    #include <colorspace_fragment>
  }
`;

function createRibbon(index) {
  const geometry = new THREE.BufferGeometry();
  const position = new THREE.BufferAttribute(new Float32Array(MAX_POINTS * 2 * 3), 3).setUsage(THREE.DynamicDrawUsage);
  const strength = new THREE.BufferAttribute(new Float32Array(MAX_POINTS * 2), 1).setUsage(THREE.DynamicDrawUsage);
  const along = new THREE.BufferAttribute(new Float32Array(MAX_POINTS * 2), 1).setUsage(THREE.DynamicDrawUsage);
  geometry.setAttribute('position', position);
  geometry.setAttribute('strength', strength);
  geometry.setAttribute('along', along);
  geometry.setIndex(index);
  geometry.setDrawRange(0, 0);
  const material = new THREE.ShaderMaterial({
    uniforms: {
      color: { value: new THREE.Color() },
      opacity: { value: 1 },
      voiceStrength: { value: 1 },
    },
    vertexShader,
    fragmentShader,
    transparent: true,
    depthWrite: false,
    side: THREE.DoubleSide,
  });
  const mesh = new THREE.Mesh(geometry, material);
  mesh.frustumCulled = false; // its bounding sphere would go stale as the trail moves
  mesh.renderOrder = 2;
  mesh.visible = false;
  return mesh;
}

export function createRibbons() {
  const group = new THREE.Group();
  const index = new THREE.BufferAttribute(ribbonIndex(MAX_POINTS), 1);
  const pool = [];
  let opacity = 1;

  /** Rewrites the ribbons from a state message's voices. */
  function setVoices(voices, width, relief) {
    while (pool.length < voices.length) {
      const r = createRibbon(index);
      pool.push(r);
      group.add(r);
    }
    for (let i = 0; i < pool.length; i++) {
      const mesh = pool[i];
      const voice = voices[i];
      if (!voice) { mesh.userData.active = false; mesh.visible = false; continue; }
      const g = mesh.geometry;
      const count = writeRibbon(voice.points, width, 0.6, relief, MAX_POINTS,
        g.attributes.position.array, g.attributes.strength.array, g.attributes.along.array);
      g.setDrawRange(0, Math.max(0, count - 1) * 6);
      g.attributes.position.needsUpdate = true;
      g.attributes.strength.needsUpdate = true;
      g.attributes.along.needsUpdate = true;
      const u = mesh.material.uniforms;
      u.color.value.set(slotColor(voice.slot));
      u.voiceStrength.value = voice.strength;
      u.opacity.value = opacity;
      mesh.userData.active = count >= 2;
      mesh.visible = mesh.userData.active && opacity > 0.001;
    }
  }

  function setOpacity(o) {
    opacity = o;
    for (const mesh of pool) {
      mesh.material.uniforms.opacity.value = o;
      mesh.visible = !!mesh.userData.active && o > 0.001;
    }
  }

  return { group, setVoices, setOpacity };
}
