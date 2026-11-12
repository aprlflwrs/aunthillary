// The path sources, by id. A path source turns a colony state into voices of the same shape as
// state.voices (see "Mappings and path sources" in the plan). The engine can switch between
// them while playing: voices keep their IDs, so switching changes the paths, not the voices.

import colony from './colony.js';
import noise from './noise.js';

export const SOURCES = { [colony.id]: colony, [noise.id]: noise };
