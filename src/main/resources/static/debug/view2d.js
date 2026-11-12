// A plain 2D canvas view of everything the server sends, for checking the data while the real
// Three.js renderer (render/) is being built. It is a debugging aid, not part of the artifact.
//
// Draws: the food pheromone (green) and home pheromone (blue) as the background, ants as dots
// (orange when carrying food), each voice's curve in its slot's color, and the heading ring.

import { WORLD_SIZE, FLAG_CARRYING_FOOD } from '../net/protocol.js';
import { SLOT_COLORS } from '../ui/slotColors.js';

export function createDebugView(canvas) {
  const ctx = canvas.getContext('2d');
  let ants = null;
  let pheromone = null;
  let pheromoneBitmap = null;
  let state = null;

  function resize() {
    const size = Math.min(window.innerWidth, window.innerHeight);
    canvas.width = canvas.height = size * devicePixelRatio;
    canvas.style.width = canvas.style.height = `${size}px`;
  }
  window.addEventListener('resize', resize);
  resize();

  // World (x east, y north) → canvas pixels (y down).
  const sx = (x) => (x / WORLD_SIZE) * canvas.width;
  const sy = (y) => canvas.height - (y / WORLD_SIZE) * canvas.height;

  function buildPheromoneImage() {
    const { n, food, home } = pheromone;
    const image = new ImageData(n, n);
    for (let row = 0; row < n; row++) {
      // ImageData rows run top to bottom; pheromone rows run south to north.
      const out = (n - 1 - row) * n;
      for (let col = 0; col < n; col++) {
        const i = row * n + col;
        const o = (out + col) * 4;
        image.data[o] = 0;
        image.data[o + 1] = food[i];
        image.data[o + 2] = home[i] * 0.6;
        image.data[o + 3] = 255;
      }
    }
    createImageBitmap(image).then((bitmap) => { pheromoneBitmap = bitmap; });
  }

  function draw() {
    const w = canvas.width;
    ctx.fillStyle = '#000';
    ctx.fillRect(0, 0, w, w);

    if (pheromoneBitmap) ctx.drawImage(pheromoneBitmap, 0, 0, w, w);

    if (ants) {
      const r = Math.max(1, devicePixelRatio);
      for (let i = 0; i < ants.count; i++) {
        ctx.fillStyle = ants.flags[i] & FLAG_CARRYING_FOOD ? '#ffa94d' : '#ddd';
        ctx.fillRect(sx((ants.x[i] / 65535) * WORLD_SIZE), sy((ants.y[i] / 65535) * WORLD_SIZE), r, r);
      }
    }

    if (state) {
      ctx.lineWidth = 2 * devicePixelRatio;
      for (const voice of state.voices) {
        ctx.strokeStyle = SLOT_COLORS[voice.slot];
        ctx.globalAlpha = 0.3 + 0.7 * voice.strength;
        ctx.beginPath();
        voice.points.forEach(([x, y], i) => (i ? ctx.lineTo(sx(x), sy(y)) : ctx.moveTo(sx(x), sy(y))));
        ctx.stroke();
      }
      ctx.globalAlpha = 1;
      // Heading ring: each bin's share drawn as a spoke around the nest.
      const { x, y, hubRadius } = state.nest;
      ctx.strokeStyle = '#fff';
      ctx.beginPath();
      state.headings.forEach((share, b) => {
        const a = (b / state.headings.length) * 2 * Math.PI;
        const r0 = hubRadius * 2;
        const r1 = r0 + share * 200;
        ctx.moveTo(sx(x + r0 * Math.cos(a)), sy(y + r0 * Math.sin(a)));
        ctx.lineTo(sx(x + r1 * Math.cos(a)), sy(y + r1 * Math.sin(a)));
      });
      ctx.stroke();
      ctx.fillStyle = '#ff0';
      for (const f of state.food) ctx.fillRect(sx(f.x) - 3, sy(f.y) - 3, 6, 6);
    }
    requestAnimationFrame(draw);
  }
  requestAnimationFrame(draw);

  return {
    setAnts(message) { ants = message; },
    setPheromone(message) { pheromone = message; buildPheromoneImage(); },
    setState(message) { state = message; },
    /** Converts a click on the canvas to world coordinates. */
    toWorld(event) {
      const rect = canvas.getBoundingClientRect();
      return {
        x: ((event.clientX - rect.left) / rect.width) * WORLD_SIZE,
        y: (1 - (event.clientY - rect.top) / rect.height) * WORLD_SIZE,
      };
    },
  };
}
