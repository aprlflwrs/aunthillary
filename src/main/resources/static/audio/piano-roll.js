// The piano roll strip: a canvas along the bottom of the page that scrolls with the audio
// clock, drawing every note the engine has booked in its voice's slot colour. Muted voices'
// notes are drawn as outlines (booked but not heard); a note that is sounding right now is
// drawn brighter. The layout itself is in roll-model.js.

import { pitchRange, rollRects } from './roll-model.js';

/**
 * Starts drawing into `canvas`. `now()` is the time on the audio clock that is being heard,
 * `notes` the engine's note log, `seconds()` how much history to show. Returns { stop }.
 */
export function createPianoRoll(canvas, { now, notes, seconds }) {
  const ctx = canvas.getContext('2d');
  let frame = 0;
  // The pitch range eases toward the visible notes' range instead of jumping.
  let low = 54, high = 78;

  function resize() {
    const r = canvas.getBoundingClientRect();
    const w = Math.max(1, Math.round(r.width * devicePixelRatio));
    const h = Math.max(1, Math.round(r.height * devicePixelRatio));
    if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; }
  }

  function draw() {
    frame = requestAnimationFrame(draw);
    resize();
    const t = now();
    const span = seconds();
    const target = pitchRange(notes, t, span);
    low += (target.low - low) * 0.05;
    high += (target.high - high) * 0.05;
    const { width, height } = canvas;
    const { nowX, rects } = rollRects(notes, t, { width, height, seconds: span, low: Math.round(low), high: Math.round(high) });

    ctx.clearRect(0, 0, width, height);
    ctx.fillStyle = '#0009';
    ctx.fillRect(0, 0, width, height);
    // A faint line at every C, for a sense of register.
    ctx.fillStyle = '#ffffff14';
    const rowH = height / (Math.round(high) - Math.round(low) + 1);
    for (let m = Math.ceil(low / 12) * 12; m <= high; m += 12) ctx.fillRect(0, (Math.round(high) - m) * rowH + rowH - 1, width, 1);

    ctx.lineWidth = Math.max(1, devicePixelRatio);
    for (const r of rects) {
      if (r.muted) {
        ctx.strokeStyle = r.color;
        ctx.globalAlpha = 0.6;
        ctx.strokeRect(r.x + 0.5, r.y + 0.5, r.w - 1, r.h - 1);
      } else {
        ctx.fillStyle = r.color;
        ctx.globalAlpha = r.sounding ? 1 : 0.55 + 0.4 * r.record.velocity;
        ctx.fillRect(r.x, r.y, r.w, r.h);
        if (r.sounding) {
          ctx.fillStyle = '#fff';
          ctx.globalAlpha = 0.35;
          ctx.fillRect(r.x, r.y, r.w, r.h);
        }
      }
    }
    ctx.globalAlpha = 1;
    ctx.fillStyle = '#fff8';
    ctx.fillRect(Math.round(nowX), 0, Math.max(1, devicePixelRatio), height);
  }

  draw();
  return { stop: () => cancelAnimationFrame(frame) };
}
