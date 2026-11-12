// Spatial audio: each voice is heard from its trail. A Three.js AudioListener follows the active
// camera, and each voice slot has a PositionalAudio that sits where that voice's latest note
// came from, so in the top-down view a trail to the east is heard on the right.
//
// The sound engine plays through the listener's AudioContext (pass `listener.context` and
// `listener.getInput()` to createSoundEngine). The ant ticks, the organ point and the symbol
// tones stay unpositioned; only the four voices are placed.

import * as THREE from 'three';
import { simToX, simToZ } from './coords.js';
import { FOV_DEGREES, ORTHO_DISTANCE } from './cameraRig.js';

const SLOTS = 4;

export function createSpatialAudio(view) {
  const listener = new THREE.AudioListener();
  view.scene.add(listener);

  const sources = Array.from({ length: SLOTS }, () => {
    const audio = new THREE.PositionalAudio(listener);
    // Gentle distance falloff: across the whole world a voice gets quieter but never vanishes,
    // and nearer voices stand out a little as the camera zooms in.
    audio.setDistanceModel('inverse');
    audio.setRefDistance(150);
    audio.setRolloffFactor(0.6);
    view.scene.add(audio);
    return audio;
  });

  let engine = null;

  const dir = new THREE.Vector3();
  const target = new THREE.Vector3();

  view.onFrame((camera) => {
    // The listener faces the way the camera looks. The perspective camera already stands at a
    // distance that matches the zoom. The orthographic one always sits ORTHO_DISTANCE above its
    // target whatever the zoom, so for it the listener stands where a perspective camera showing
    // the same view would be: zooming in brings you closer to the trails, and you hear it.
    listener.quaternion.copy(camera.quaternion);
    if (camera.isOrthographicCamera) {
      camera.getWorldDirection(dir);
      target.copy(camera.position).addScaledVector(dir, ORTHO_DISTANCE);
      const halfH = (camera.top - camera.bottom) / 2;
      listener.position.copy(target).addScaledVector(dir, -halfH / Math.tan((FOV_DEGREES / 2) * Math.PI / 180));
    } else {
      listener.position.copy(camera.position);
    }
    if (!engine) return;
    for (let slot = 0; slot < SLOTS; slot++) {
      const p = engine.voicePosition(slot);
      if (!p) continue;
      sources[slot].position.set(simToX(p.x), p.height * view.params.relief, simToZ(p.y));
    }
  });

  return {
    listener,
    /** Routes each voice's output through its PositionalAudio. */
    attach(soundEngine) {
      engine = soundEngine;
      sources.forEach((audio, slot) => audio.setNodeSource(engine.detachVoiceOutput(slot)));
    },
  };
}
