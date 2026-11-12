// Decoders for the server → browser messages. The layouts are the contract in the Interfaces
// section of ProjectPlan.md, and mirror server/Protocol.kt. src/test/js/protocol.test.mjs checks
// this file against bytes produced by the Kotlin encoder.

export const WORLD_SIZE = 512;

export const TYPE_ANTS = 1;
export const TYPE_PHEROMONE = 2;
export const TYPE_TERRAIN = 3;
export const VERSION = 1;

export const FLAG_RETURNING = 1;
export const FLAG_CARRYING_FOOD = 2;
export const FLAG_SCOUT = 4;

// Each block below is a typed-array view straight onto the received bytes: nothing is copied.
// Typed arrays use the machine's byte order, which is little-endian on every platform a browser
// runs on, matching the protocol.

/** Decodes a binary message (an ArrayBuffer). Returns null for an unknown type or version. */
export function decodeBinary(buffer) {
  const view = new DataView(buffer);
  const type = view.getUint8(0);
  const version = view.getUint8(1);
  if (version !== VERSION) {
    console.warn(`Unsupported format version ${version} for message type ${type}`);
    return null;
  }
  switch (type) {
    case TYPE_ANTS: return decodeAnts(buffer, view);
    case TYPE_PHEROMONE: return decodePheromone(buffer, view);
    case TYPE_TERRAIN: return decodeTerrain(buffer, view);
    default:
      console.warn(`Unknown binary message type ${type}`);
      return null;
  }
}

function decodeAnts(buffer, view) {
  const tick = view.getUint32(4, true);
  const count = view.getUint32(8, true);
  return {
    type: 'ants',
    tick,
    count,
    x: new Uint16Array(buffer, 12, count),             // dequantize with positionToWorld
    y: new Uint16Array(buffer, 12 + 2 * count, count),
    heading: new Uint8Array(buffer, 12 + 4 * count, count), // dequantize with headingToRadians
    flags: new Uint8Array(buffer, 12 + 5 * count, count),
  };
}

function decodePheromone(buffer, view) {
  const n = view.getUint16(2, true);
  const tick = view.getUint32(4, true);
  return {
    type: 'pheromone',
    tick,
    n,
    // Each byte is round(255 × sqrt(min(value / displayMax, 1))). Rows run south to north,
    // and each row runs west to east.
    food: new Uint8Array(buffer, 8, n * n),
    home: new Uint8Array(buffer, 8 + n * n, n * n),
  };
}

function decodeTerrain(buffer, view) {
  const n = view.getUint16(2, true);
  const maxHeight = view.getFloat32(4, true);
  const heights = new Float32Array(n * n);
  const raw = new Uint16Array(buffer, 8, n * n);
  for (let i = 0; i < raw.length; i++) heights[i] = (raw[i] / 65535) * maxHeight;
  return {
    type: 'terrain',
    n,
    maxHeight,
    heights, // world units, same row order as the pheromone frame
    blocked: new Uint8Array(buffer, 8 + 2 * n * n, n * n),
  };
}

/** Decodes a text message (state or params). */
export function decodeText(text) {
  return JSON.parse(text);
}

export function positionToWorld(q) {
  return (q / 65535) * WORLD_SIZE;
}

export function headingToRadians(q) {
  return (q / 256) * 2 * Math.PI;
}
