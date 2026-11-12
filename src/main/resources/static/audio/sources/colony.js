// The colony path source: the server's voices, passed through unchanged. Everything the ants
// contributed (where the trails go, shared trunks, curves around terrain) is in these paths.

export default {
  id: 'colony',
  label: 'Colony',
  voices(state) {
    return state.voices ?? [];
  },
};
