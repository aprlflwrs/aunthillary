// The ground: a mesh built from the terrain frame's height grid, with the pheromone frame
// draped over it as two textures (food and home) and coloured by a small GLSL shader.
//
// Until the first terrain frame arrives the ground is a flat 2 × 2 grid, so the pheromone
// still shows. A terrain frame with the same grid size only rewrites the heights; a different
// size rebuilds the geometry.
//
// Pheromone: each layer is a one-channel (RedFormat) DataTexture, n × n bytes. A new frame
// just points the texture at the bytes that arrived (the decoder's typed-array views onto the
// WebSocket message), so nothing is copied on the CPU; three.js uploads them on the next
// render. The bytes are already square-root encoded by the server (faint trails stay visible
// beside the strong hub), so the shader uses them as they are.

import * as THREE from 'three';
import { buildGround, setGroundHeights } from './geometry.js';

const vertexShader = /* glsl */ `
  attribute float blocked;
  varying vec2 vUv;
  varying float vBlocked;
  varying float vHeight;
  varying vec3 vNormal;
  void main() {
    vUv = uv;
    vBlocked = blocked;
    vHeight = position.y;
    vNormal = normal;
    gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
  }
`;

// The ground colour runs from groundLow to groundHigh with height, shaded a little by a fixed
// light so hills read even from straight above, and darkened where the ground is blocked.
// The two pheromone layers are then added on top, each scaled by its gain and by the level
// slider's pheromone opacity: home (blue) first, food (green) over it, since food trails are
// the ones that become voices.
const fragmentShader = /* glsl */ `
  uniform sampler2D foodMap;
  uniform sampler2D homeMap;
  uniform float pheromoneOpacity;
  uniform float foodGain;
  uniform float homeGain;
  uniform float maxHeight;
  uniform vec3 groundLow;
  uniform vec3 groundHigh;
  uniform vec3 blockedColor;
  uniform vec3 foodColor;
  uniform vec3 homeColor;
  uniform vec3 lightDir;
  varying vec2 vUv;
  varying float vBlocked;
  varying float vHeight;
  varying vec3 vNormal;
  void main() {
    float t = clamp(vHeight / max(maxHeight, 0.001), 0.0, 1.0);
    vec3 color = mix(groundLow, groundHigh, t);
    color *= 0.7 + 0.3 * max(dot(normalize(vNormal), lightDir), 0.0);
    color = mix(color, blockedColor, clamp(vBlocked, 0.0, 1.0));

    float food = texture2D(foodMap, vUv).r;
    float home = texture2D(homeMap, vUv).r;
    color += homeColor * home * homeGain * pheromoneOpacity;
    color += foodColor * food * foodGain * pheromoneOpacity;

    gl_FragColor = vec4(color, 1.0);
    #include <tonemapping_fragment>
    #include <colorspace_fragment>
  }
`;

function emptyTexture() {
  return pheromoneTexture(new Uint8Array(1), 1);
}

function pheromoneTexture(data, n) {
  const texture = new THREE.DataTexture(data, n, n, THREE.RedFormat, THREE.UnsignedByteType);
  texture.magFilter = THREE.LinearFilter;
  texture.minFilter = THREE.LinearFilter;
  texture.generateMipmaps = false;
  texture.unpackAlignment = 1; // rows are n bytes, which need not be a multiple of 4
  texture.needsUpdate = true;
  return texture;
}

export function createTerrain() {
  const uniforms = {
    foodMap: { value: emptyTexture() },
    homeMap: { value: emptyTexture() },
    pheromoneOpacity: { value: 0.35 },
    foodGain: { value: 1.0 },
    homeGain: { value: 0.6 },
    maxHeight: { value: 1 },
    groundLow: { value: new THREE.Color('#15140f') },
    groundHigh: { value: new THREE.Color('#3a3326') },
    blockedColor: { value: new THREE.Color('#050505') },
    foodColor: { value: new THREE.Color('#4cff7a') },
    homeColor: { value: new THREE.Color('#3d7bff') },
    lightDir: { value: new THREE.Vector3(-0.5, 0.8, -0.3).normalize() },
  };
  const material = new THREE.ShaderMaterial({ uniforms, vertexShader, fragmentShader });
  const mesh = new THREE.Mesh(new THREE.BufferGeometry(), material);
  mesh.renderOrder = 0;

  let n = 0;
  let terrain = null; // the latest terrain frame, kept so the relief can be changed later
  let relief = 1;

  function rebuild() {
    const size = terrain ? terrain.n : 2;
    const heights = terrain ? terrain.heights : null;
    const blocked = terrain ? terrain.blocked : null;
    if (size !== n) {
      const g = buildGround(size, heights, blocked, relief);
      const geometry = new THREE.BufferGeometry();
      geometry.setAttribute('position', new THREE.BufferAttribute(g.positions, 3));
      geometry.setAttribute('uv', new THREE.BufferAttribute(g.uvs, 2));
      geometry.setAttribute('blocked', new THREE.BufferAttribute(g.blocked, 1));
      geometry.setIndex(new THREE.BufferAttribute(g.index, 1));
      mesh.geometry.dispose();
      mesh.geometry = geometry;
      n = size;
    } else {
      const geometry = mesh.geometry;
      setGroundHeights(geometry.attributes.position.array, n, heights, relief);
      geometry.attributes.position.needsUpdate = true;
      const b = geometry.attributes.blocked.array;
      for (let i = 0; i < b.length; i++) b[i] = blocked ? blocked[i] : 0;
      geometry.attributes.blocked.needsUpdate = true;
    }
    mesh.geometry.computeVertexNormals();
    mesh.geometry.computeBoundingSphere();
    uniforms.maxHeight.value = Math.max(0.001, (terrain ? terrain.maxHeight : 1) * relief);
  }
  rebuild();

  function setLayer(uniform, data, size) {
    const texture = uniform.value;
    if (texture.image.width === size && texture.image.height === size) {
      texture.image.data = data;
      texture.needsUpdate = true;
    } else {
      texture.dispose();
      uniform.value = pheromoneTexture(data, size);
    }
  }

  return {
    mesh,
    uniforms,
    /** A new terrain frame: heights (world units) and blocked flags, rows south to north. */
    setTerrain(message) { terrain = message; rebuild(); },
    /** A new pheromone frame: food and home layers, n × n bytes each, rows south to north. */
    setPheromone(message) {
      setLayer(uniforms.foodMap, message.food, message.n);
      setLayer(uniforms.homeMap, message.home, message.n);
    },
    /** Vertical exaggeration of the terrain (1 = true heights). */
    setRelief(r) { if (r !== relief) { relief = r; rebuild(); } },
    setPheromoneOpacity(o) { uniforms.pheromoneOpacity.value = o; },
  };
}
