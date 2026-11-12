// The WebSocket connection to the colony. Decodes every message and hands it to onMessage;
// reconnects on its own if the server restarts.

import { decodeBinary, decodeText } from './protocol.js';

export function connect({ onMessage, onStatus = () => {} }) {
  const url = `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws`;
  let socket = null;
  let retryMs = 500;

  function open() {
    onStatus('connecting');
    socket = new WebSocket(url);
    socket.binaryType = 'arraybuffer';
    socket.onopen = () => {
      retryMs = 500;
      onStatus('connected');
    };
    socket.onmessage = (event) => {
      const message = typeof event.data === 'string' ? decodeText(event.data) : decodeBinary(event.data);
      if (message) onMessage(message);
    };
    socket.onclose = () => {
      onStatus('disconnected');
      setTimeout(open, retryMs);
      retryMs = Math.min(retryMs * 2, 5000);
    };
  }

  open();

  return {
    /** Sends a browser → server message (see Interfaces in ProjectPlan.md). Dropped while disconnected. */
    send(message) {
      if (socket && socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(message));
    },
  };
}
