# Ant Fugue: An Emergent Colony That Composes

Nov 5, 2026 · @April Jarosz

## Summary

Ant Fugue is an ant colony that composes music as it runs. A single colony runs in a local server process, and a browser connects to it to watch the ants, hear the fugue, and change it by placing food. (A shared colony that the whole class opens at once is a stretch goal; see [Stretch: Shared Colony](#stretch-shared-colony).) No single ant knows anything or plays anything meaningful, yet as pheromone trails form, the colony's sound shifts from scattered noise into a three- or four-voice fugue. The project makes Hofstadter's argument from the "Ant Fugue" dialogue in Gödel, Escher, Bach something people can watch, hear and take part in: there is one Aunt Hillary, and meaning lives at a higher level of description than the parts that produce it.

## Philosophical Concept

The project explores emergence and levels of description, the central argument of the "Ant Fugue" dialogue. The dialogue opens as a dispute between holism (the whole is more than its parts) and reductionism (a whole is fully explained by its parts). Achilles answers "Mu": he unasks the question, because both views fit inside a larger picture of levels (p. 317).

Aunt Hillary is an ant colony who converses with the Anteater through the trails her ants form, yet "all the ants in Aunt Hillary are as dumb as can be" (p. 320). The Tortoise compares this to a brain built from unintelligent neurons (p. 320). The Anteater then describes three levels of organization:

- **Ants**: individuals that wander almost at random, yet produce stable overall trends through the regularity of statistics (p. 321). An activity like trail-building only sustains itself once a threshold number of ants joins in; below it, the effort fizzles out (p. 322).
- **Signals**: the lowest-level teams, which form, migrate through the colony and dissolve where they are needed (p. 325). A signal keeps its identity even when its membership turns over completely (p. 328).
- **Symbols**: higher-level teams made of teams, which the Anteater calls active subsystems that trigger one another (pp. 329–330). Only at this level does the colony's activity carry meaning, just as words carry meaning while their letters do not (pp. 330–332).

The dialogue is itself written as a four-voice fugue, and its central image ties the two subjects together. Signals can pass through one another, each treating the other as background, and Achilles pictures the result as an orderly criss-crossing pattern: "An ant fugue!" (p. 327). This gives the project its second theme, isomorphism: ants, signals and symbols correspond to notes, voices and the fugue as a whole. The program maps one structure onto the other directly, so the colony's levels become audible.

The project asks one question: at what point does the colony's output stop being noise and start being music, and where does that meaning live? No line of code says "play a fugue." The Anteater admits he can describe the same colony with or without meaning and purpose, depending on the level he chooses to view it from (p. 327). The program's level-of-description slider gives the user that same choice.

## Platform & Software Decision

The project is a client–server web application. One authoritative colony runs in a server process, and a browser on the same machine connects to it. Keeping the client and server on one machine removes every problem a real network hop brings (latency, jitter, dropped messages, bandwidth limits, synchronizing several listeners), while keeping the client–server split means a shared colony can be added later without changing the simulation.

| Component | Tool | Role |
|---|---|---|
| Simulation server | Kotlin on Ktor (JVM 21+) | Runs the colony: tick loop, pheromone grid, trail detection |
| Real-time transport | Ktor WebSockets (over localhost) | Pushes colony state to the browser and receives user actions and parameter changes |
| Client rendering | JavaScript + Three.js r187 (WebGPURenderer) | Renders the colony in 3D on the GPU: instanced ants, pheromone as a texture, levels stacked in space |
| Client audio | Web Audio API + smplr | Turns the colony's voices into music in the browser; shares Three.js's audio context so each voice can be placed in 3D space at its trail |
| Hosting | Localhost (`java -jar aunthillary.jar`, then open `http://localhost:8080`) | Runs on any machine with Java; the i7-14700K server hosts the shared colony stretch goal |
| Packaging | Executable fat JAR (Ktor Gradle plugin) | The full app as one file, runnable anywhere with java -jar |

**Why Kotlin and Ktor.** The simulation is naturally object-oriented: ants, trails and colonies are entities with state and behavior. Kotlin's data classes and sealed classes suit the message types the server sends, and it builds on my Java experience. Ktor is JetBrains' lightweight Kotlin server framework. The server layer is small (a tick loop, WebSocket connections and static files), so a full enterprise framework like Spring Boot would add weight without adding anything this project uses. Ktor is built on coroutines, which fit the design directly: the tick loop is one coroutine, and each connected client gets its own.

**Why Three.js.** A Kotlin server process can simulate far more ants than a 2D canvas library like p5.js can draw, which tops out at roughly several thousand shapes per frame. Three.js renders on the GPU, so one instanced mesh can draw 100,000+ ants in a single draw call, and the pheromone grid can be uploaded as one texture. It also makes the levels of description spatial: the camera can tilt from a flat top-down view into a view where ants, signals and symbols sit on stacked planes. The cost is a steeper learning curve (scenes, cameras, buffers and some shader code), which the timeline accounts for. I will use the newer WebGPURenderer, which runs on WebGPU where the browser supports it (Chrome, Edge, Safari 26+, and Firefox on Windows and Apple Silicon Macs) and falls back to WebGL 2 automatically everywhere else, so every visitor can still see the colony. Its cost is that custom shaders are written in TSL, three.js's node-based shading language, instead of GLSL, and the three.js manual still calls the renderer experimental. This project's custom shader work is small (coloring the pheromone texture, and optionally interpolating ant positions), and everything else uses built-in features that behave the same on both renderers. To contain the risk, the first day of week 13 is a spike: instanced ants and the pheromone texture rendering under WebGPURenderer. If that stalls after about a day, I switch to the established WebGLRenderer, which changes only the shader code.

**Why audio stays on the client.** The server never streams sound. It sends the current voices (trail curves), and the browser synthesizes the music itself, scheduled slightly ahead of time so a stalled frame or message does not cause stutter.

**Distribution.** The Ktor Gradle plugin builds a single executable JAR containing the server and the client files. The client, and the instrument samples it plays, are served from inside the JAR with Ktor's staticResources, so a colony needs no internet connection. Running the JAR locally is the primary way to use the project: anyone with Java can run a colony on their own machine with `java -jar aunthillary.jar`.

## Code Plan

The code is split into three parts that know as little about each other as possible: a pure simulation core, a server that runs it and streams it, and a browser client that draws and plays it. The core knows nothing about networking or sound. This separation mirrors the project's thesis: the music is not programmed into the ants, it is read off their behavior at a higher level.

User actions and parameter changes flow up to the tick loop; colony state flows down to the browser, which draws and plays it locally. The exact shapes of everything that crosses between the parts are pinned down in [Interfaces](#interfaces).

These parts are folders in a single Gradle project, not separate build modules:

```
src/main/kotlin/net/aprlflwrs/aunthillary/
  core/      ants, pheromone grid, terrain, nest, mound, params   (no Ktor imports)
  trails/    trail detector                                        (no Ktor imports)
  server/    tick loop, WebSocket, protocol encoding, fake colony
  Headless.kt  runs the colony without a browser and dumps images and metrics
src/main/resources/static/
  index.html   import map; no bundler, no npm build step
  net/         WebSocket connection and message decoding
  render/      Three.js scene
  audio/       scheduler, instruments, organ point, mappings/, path sources
  ui/          level slider, tuning panel, piano roll
  vendor/      pinned copies of three.js, lil-gui, smplr, and the instrument samples
```

### 1. colony-core (pure Kotlin, no framework)

- **Ant**: position, heading, state (Searching or Returning), an optional food load, and two traits set at birth: a preferred heading and a fidelity. Each tick it moves forward with small random turns, holding roughly its direction so searchers fan outward instead of jittering in place, and drifting gently toward its preferred heading. When it senses food pheromone in its three forward cones, it steers toward the strongest with a probability set by its fidelity; otherwise it keeps exploring. Food gives off a short-range scent, so a searcher that wanders close can home in on it. A searching ant lays home pheromone as it walks; once it picks up food it becomes a returning ant, which steers by the home pheromone (with a weak pull toward the nest's direction as a backup, standing in for real ants' path integration) and lays food pheromone on its way back to the nest. Each layer is therefore laid by the ants that are not following it.
- **PheromoneGrid**: a 2D float array, starting at 512 × 512 cells (about 1 MB per layer, each cell roughly one ant length), with separate layers for "toward food" and "toward home." It evaporates and diffuses slightly every tick. Ants move with continuous positions rather than hopping cell to cell, and sample the grid with bilinear interpolation, so steering stays smooth across cell boundaries. All rates (evaporation, diffusion, speed, sensing distance) are defined in world units rather than per cell, so the resolution can be tuned in week 11 by benchmarking 256², 512² and 1024² without changing how the colony behaves. It is the colony's shared memory: no ant holds it, all ants write to it.
- **Terrain**: a second 2D grid holds ground height (gentle generated hills) and blocked cells (rocks, twigs). Ants slow on steep slopes and cannot enter blocked cells, so trails curve through valleys and around obstacles instead of running straight. The simulation stays 2D; an ant's 3D position is just its grid coordinates plus the ground height beneath it.
- **Nest and food economy**: each food pile holds a finite amount and shrinks as ants carry it away. Returning ants add food to a store at the nest, and whenever the store holds enough, the nest spends it to hatch a new ant. Ants live a few minutes and then die; if the store runs empty, the colony stops growing and shrinks, down to a floor of a few hundred ants so it never dies out. The population therefore settles wherever incoming food balances deaths. Every trail threshold depends on how many ants there are, so the economy (hatch cost, lifespan, pile size) is tuned to keep a well-fed colony within a target range, starting at roughly 5,000–30,000 ants, with a hard cap of 100,000 that the renderer is sized for. Constant turnover also means trails keep their identity while their membership changes completely, as the Anteater describes (p. 329).
- **Castes and headings**: fidelity varies across the colony. Low-fidelity ants act as scouts that keep exploring even when strong trails exist; high-fidelity ants act as foragers that work established trails. This keeps the colony from collapsing onto a single trail, and echoes the castes in the dialogue (pp. 322–324). Preferred headings are a soft bias, not a hard boundary: an ant can still follow a strong trail outside its direction. The nest assigns each newborn a heading drawn mostly from the directions recent foragers returned from, with a fixed share chosen at random so exploration never stops. That distribution of headings is the colony's version of Hofstadter's caste distribution: a pattern no single ant holds, which shifts to reflect where food is in the world (pp. 324, 329).
- **Mound**: the nest raises the terrain around it into a cone that grows a little with every unit of food brought home, up to a cap. Ants climb it on the way home, and since several mappings read terrain height, melodies near the nest change as it grows. The mound is the colony's property, not the colony: in the dialogue, Aunt Hillary lives on an estate that "used to belong to someone else" (p. 336).
- **TrailDetector (the signal level)**: the pheromone grid is only a field of numbers; nothing in it is a "trail" yet. The detector finds trails in that field and turns them into objects the music can use. Every half second it:
    1. Keeps only strong scent. It reads only the food-pheromone layer, which ants lay while carrying food, so it marks routes to food rather than general wandering. Cells below a threshold are ignored, so faint, scattered pheromone never becomes a trail. This models the Anteater's point that activity either fizzles out or snowballs past a critical mass (p. 322).
    2. Sets aside the hub. Every route crowds together at the nest, so cells within a fixed radius of it are ignored, and detection starts at the hub's edge.
    3. Thins the strong cells into a network. The strong cells are thinned to one-cell-wide centerlines, which form a network of segments meeting at junctions. A trunk trail that forks becomes one segment splitting into two.
    4. Finds the voices. Each voice is a path through that network from the hub's edge to one food source. Voices heading the same way share their trunk segments and split at a junction, so branching trails are handled naturally.
    5. Matches paths to last time's voices. A path that overlaps an existing voice's path is the same voice, so it keeps its ID, color and instrument even as the ants on it change, as Hofstadter's signals do (p. 328).

  Each voice's path is smoothed into a curve, and that curve is what the client receives and what the melody walks along. The grid is the ant-level medium; the network of curves is the signal-level object. The detector takes a plain snapshot of the food layer (a float array and its size) rather than the live grid, so it can be developed and tested against synthetic grids and saved snapshots, and it writes debug images of each stage (threshold, centerlines, network, voices) so its behavior can be seen while tuning.
- **ColonyState**: what crosses out of the core is the full current state, not a stream of events: the voices with their curves, food piles, the nest, and the heading distribution. The server sends it in full about twice a second, and the client works out what changed (a voice entering, a voice fading) by comparing it with the previous state. Any lost or skipped message is corrected by the next one.
- **Params**: every tunable number in the core (rates, thresholds, economy, caste shares) is registered in one place with a name, range and default, and can be changed while the colony runs. The tuning panel in the browser is built from this list, so adding a parameter in the code adds a slider automatically.

### 2. colony-server (Ktor)

- **Tick loop**: a single coroutine owns the colony and advances it at a fixed rate (about 30 ticks per second). Each tick has two phases so that parallelism never causes a race on the pheromone grid. In the move phase, ants are updated in parallel across cores; each ant only reads the grid and writes its own state, and its pheromone deposits go into a buffer owned by its worker thread. In the merge phase, the buffers are added into the grid, then evaporation and diffusion run. The tick loop can be paused, stepped one tick at a time, and run faster than real time, which helps tuning.
- **Action queue**: user actions and parameter changes arriving over WebSocket are queued and applied at the start of the next tick, so the colony is only ever mutated by the tick loop. This avoids race conditions between the browser and the simulation without locks.
- **Streamer**: the connected browser has its own coroutine draining a small outgoing channel with the DROP_OLDEST overflow policy, so the tick loop never waits on the browser (for example while its tab is in the background). Because every message carries complete state rather than a change, a dropped message costs nothing: the next one replaces it. On localhost there is no need to choose what to send, so the browser receives everything:
    - Ants: every ant's position, every tick, quantized to 16-bit integers in a binary message.
    - Pheromone: a downsampled map of both layers (128 × 128, one byte per cell) a few times a second for the ground texture.
    - Terrain: the height and blocked grids on connect, and again when the mound changes (at most once a second).
    - Colony state: the voices and their curves, food, nest and heading distribution as JSON, about twice a second. These are small and drive the music.
- **Fake colony**: a stand-in that produces moving ants and slowly drifting voices through exactly the same messages, so the renderer and sound engine can be built and tested before the real simulation and trail detector exist.
- **Headless runner**: runs the colony without a browser, as fast as the CPU allows, and saves pheromone images, detector debug images and metrics (population, number of voices, voice lifetimes) at intervals. Tuning the low-level rules is mostly done by comparing these runs.

### 3. Browser client (JavaScript)

- **Renderer (Three.js)**: ants are one InstancedMesh sized for the maximum population, with its count lowered to the live population each frame, its instance buffer marked for dynamic updates, and frustum culling turned off because ants span the whole map; the terrain is a ground mesh built from the height grid, with the pheromone grid draped over it as a DataTexture colored by a small TSL shader; trails are ribbon meshes, one color per voice; symbol regions are translucent glowing planes. An orthographic camera gives the default top-down view, and a perspective camera handles the tilted stacked-levels view. The renderer runs at the display's refresh rate (60 Hz or more) while the server ticks at 30 Hz, so it draws each ant interpolated between its two most recent positions, trading about one tick (33 ms) of delay for smooth motion. If writing every ant's matrix each frame proves too slow, previous and current positions move into instance attributes and a TSL vertex node does the interpolation instead. As a stretch goal, a WebGPU compute pass could interpolate every ant on the GPU, taking that work off the CPU entirely at the population cap; it stays optional because compute features may not run on the WebGL 2 fallback.
- **Sound engine (Web Audio API + smplr)**: a custom lookahead scheduler (about 200 lines) wakes every 25 ms and books any notes due in the next 100 ms against the audio clock, the standard "two clocks" pattern, so timing stays sample-accurate even when the page or network stutters. smplr (version pinned, since its API is still settling) supplies sampled General MIDI instruments: an organ for the voices and a soft percussive sample for ant ticks, self-hosted rather than fetched from smplr's default online sample source. Everything shares the AudioContext of the Three.js AudioListener, which is resumed when the visitor clicks Join, because browsers block audio until a user gesture. Each voice gets its own instrument instance whose destination is a GainNode fed into a Three.js PositionalAudio object placed at its trail. The engine turns voices into music under fixed constraints:
    - Scale: pitches come only from a pentatonic scale, so no combination clashes.
    - Rhythm: notes are quantized to an 8th-note grid at about 100 BPM.
    - Voices: each strong trail becomes one voice, capped at four. Its melody is read along its curve from the nest end outward, one 8th note per fixed distance walked, looping back to the nest end when it reaches the food.
    - Mappings: how a point on a curve becomes a note is a swappable mapping, chosen from a menu while the colony plays, so different ways of expressing the colony can be compared on the same trails. A mapping only chooses a scale degree, loudness, length and timbre; the scale and beat grid are applied afterwards, the same way for every mapping. The first mappings to try:
        - Compass position (the original design): north–south position sets the scale degree, east–west position sets timbre (mellow organ stops in the west, brighter reed stops in the east), and terrain height sets loudness and note length. Its weakness is that a straight trail's melody is set almost entirely by its direction: a trail running east repeats one note.
        - Turning: the trail's curvature at each point sets the melodic step (left turns rise, right turns fall, straight runs repeat), so the melody comes from how the trail bends around terrain and obstacles rather than from where it points.
        - Distance and height: distance from the nest sets register and terrain height sets the scale degree, so a trail climbing over a hill sounds like one.

      Pitch depends on the colony, never on the viewer's camera, so the same trail always produces the same melody.
    - Shared openings: there is no imitation rule. Voices whose paths share a trunk play the same opening phrase and then diverge where their paths split, and they enter at different times because their trails formed at different times. Any subject-and-answer a listener hears comes from the colony's geometry and timing, not from the code.
    - Organ point: a low sustained note holds the key under everything (synthesized directly with a few Web Audio oscillators, since sampled notes decay and smplr's sample looping is still experimental), the device the Tortoise points out in the dialogue (pp. 334–335). Its loudness and richness follow the nest's food store, so the drone tracks the colony's health while the trail voices carry its activity.
    - Noise control: a toggle swaps the colony's trails for random wandering paths fed through exactly the same rules. The scale, beat grid and instruments only constrain what can be played; everything else should come from the colony. Whatever difference a listener hears between the colony and the control is what the ants contributed. For that comparison to be fair, the control must differ from the colony only in what the ants could have contributed: each random path stands in for one real voice, starts at the hub's edge like it, has the same length, bends as much on average, goes through the same smoothing, and enters and fades at the same moments. What remains different is where the paths go: shared trunks, routes toward food, curves around terrain. In code, the colony and the control are two interchangeable path sources feeding whichever mapping is selected.
- **Input**: clicking places food; scrolling zooms and dragging pans; the level slider sets what is drawn and heard (see the table below) and is independent of zoom; a separate control tilts into the stacked view.
- **Tuning panel**: a lil-gui panel holds every server parameter (built from the list the server sends) alongside the client's own (tempo, scale, mapping, volumes). Changes apply immediately to the running colony. Settings can be saved as named presets and reloaded, so a good-sounding configuration is never lost while experimenting.

### 4. Genomes (stretch goal)

In the core plan, the nest assigns each newborn's traits from colony-wide statistics. As a stretch goal, newborns would instead inherit preferred heading and fidelity from a specific successful forager, with small mutations, adding a light layer of self-replication that echoes the Typogenetics chapter.

## Interfaces

These are the agreements between the parts. Each part can be built and tested on its own as long as it keeps to them, and changing one of them means updating this section first. Where code and this section disagree on a parameter's value, the code wins; the shapes and conventions below are the contract.

### World and coordinates

- The world is a square **512 × 512 world units**. One world unit is roughly one ant length, and one pheromone cell at the starting 512² resolution.
- Simulation coordinates are `(x, y)`: **x increases to the east, y increases to the north**, with the origin at the south-west corner. "North–south position" in any mapping means `y`.
- Headings are in radians: **0 is east, counterclockwise is positive**, so π/2 is north.
- Height is in world units above the lowest possible ground.
- In Three.js, a simulation point `(x, y, height)` is placed at `X = x − 256`, `Y = height`, `Z = 256 − y`. The top-down camera looks down the −Y axis with its up vector pointing to −Z, so north is at the top of the screen.
- All rates are per second, not per tick, so changing the tick rate does not change the colony's behavior.
- Time on the server is a tick counter starting at 0, at about 30 ticks per second.

### Server → browser

Binary messages start with a one-byte type and a one-byte format version. Everything is little-endian.

**Ant frame** (type 1, every tick):

| Offset | Type | Field |
|---|---|---|
| 0 | u8 | type = 1 |
| 1 | u8 | version = 1 |
| 2 | u16 | reserved |
| 4 | u32 | tick |
| 8 | u32 | ant count *n* |
| 12 + 10·i | u32 | ant ID (unique, never reused, so the browser can match ants between frames to interpolate them) |
| 16 + 10·i | u16, u16 | x, y, each as `round(value / 512 × 65535)` |
| 20 + 10·i | u8 | heading, as `round(heading / 2π × 256) mod 256` |
| 21 + 10·i | u8 | flags: bit 0 returning, bit 1 carrying food, bit 2 scout |

At 30,000 ants this is about 9 MB/s, which is fine on localhost.

**Pheromone frame** (type 2, a few times a second): `u8 type = 2, u8 version, u16 n (= 128), u32 tick`, then `n × n` bytes of the food layer followed by `n × n` bytes of the home layer. Rows run south to north and each row runs west to east. Each byte is the cell's strength scaled to 0–255 and clamped.

**Terrain frame** (type 3, on connect and when the mound changes, at most once a second): `u8 type = 3, u8 version, u16 n, f32 maxHeight`, then `n × n` u16 heights (`height = value / 65535 × maxHeight`), then `n × n` bytes of blocked flags (0 or 1), in the same row order as the pheromone frame.

**Colony state** (JSON text, about twice a second):

```json
{
  "type": "state",
  "tick": 12345,
  "nest": { "x": 256.0, "y": 256.0, "hubRadius": 12.0, "store": 40.5,
            "population": 18234, "moundHeight": 3.2 },
  "food": [ { "id": 7, "x": 400.0, "y": 310.0, "amount": 820.0 } ],
  "voices": [
    { "id": 3, "slot": 0, "foodId": 7, "bornTick": 11000, "strength": 0.82,
      "points": [[268.1, 259.0, 1.4], [270.0, 260.2, 1.5]] }
  ],
  "headings": [0.02, 0.10]
}
```

- `voices[].id` stays the same for as long as the detector matches the trail. `slot` (0–3) is fixed for the voice's lifetime and picks its color and instrument.
- `points` is the smoothed curve as `[x, y, height]`, about every 2 world units, starting at the hub's edge and ending near the food.
- `strength` runs from 0 to 1. A voice that is fading stays in the list with falling strength until it is gone.
- `headings` has 16 bins, starting with the one centered on east and going counterclockwise. Each bin is the share of recent newborns given that heading.

**Parameter list** (JSON, on connect and after any change):

```json
{ "type": "params",
  "params": [ { "key": "pheromone.evaporation", "label": "Evaporation (per second)",
                "group": "Pheromone", "min": 0, "max": 1, "step": 0.001,
                "default": 0.05, "value": 0.05 } ] }
```

### Browser → server (JSON)

```json
{ "type": "placeFood", "x": 400.0, "y": 310.0 }
{ "type": "setParam", "key": "pheromone.evaporation", "value": 0.08 }
{ "type": "flood" }
{ "type": "control", "action": "pause" }
```

The `control` action is one of `pause`, `resume`, `step` or `reset`, or `speed` with a `value` (1 is real time). Presets are kept by the browser: loading a preset sends one `setParam` per server parameter and sets the client's own parameters directly.

### Mappings and path sources (browser)

A **path source** turns colony state into a list of voices with the same shape as `state.voices`. There are two: `colony`, which passes the server's voices through, and `noise`, which replaces each voice with a matched random path (see Noise control), seeded by the voice's ID so it doesn't change from one state message to the next.

A **mapping** is a module in `audio/mappings/` that turns a point on a voice's path into a note:

```js
export default {
  id: 'turning',
  label: 'Turning',
  // Called once per 8th note for each voice. Must be pure: same input, same output.
  noteAt(p) {
    return { degree: 2, velocity: 0.7, length: 1, timbre: 0.5 }; // or null for a rest
  },
};
```

- `p` contains:
  - `voice` and `step`: the 8th note's index since the voice entered, wrapping at the end of its path.
  - `s`: the distance walked along the path.
  - `x`, `y`, `height`, `heading` and `curvature` at that point.
  - `distanceFromNest`.
  - `colony`: the nest's store and population.
  - `params`: the client parameters.
- `degree` is a whole number of steps up the current scale (0 is the tonic; values below 0 or past the top of the scale move into other octaves). `length` is in 8th notes. `velocity` and `timbre` run from 0 to 1.
- The sound engine applies the scale, the beat grid and the voice's instrument afterwards. A mapping never chooses pitches or times directly, so every mapping and both path sources are held to the same constraints.
- The engine walks a fixed distance along the path per 8th note (a client parameter), so a longer trail makes a longer phrase.

## Visualization Plan

The viewer chooses their own level of description with a slider. The same moment in the same colony can be seen as ants or as symbols, the way the characters in the dialogue read different levels of the same MU-picture (pp. 316–317).

| Slider level | What the screen shows | What the user hears most clearly |
|---|---|---|
| Ants | Every ant drawn individually; pheromone faint | Soft ticks from nearby ant activity |
| Signals | Ants fade; trails drawn as colored ribbons, one color per voice | Each trail's melody line |
| Symbols | Trails abstracted into glowing regions labeled by meaning ("food: north-east"); a ring around the hill shows the colony's distribution of preferred headings | The full fugue, voices entering and answering |

The slider and the camera are separate. Scrolling zooms from the colony as a whole down to individual ants at any slider level, so a viewer can look closely at the ants that make up a trail while listening to the full fugue, or look at the whole colony while hearing only ant ticks. Moving the slider crossfades both the picture and the sound between levels rather than switching abruptly. A second control tilts the camera into a stacked view, where the three levels float on separate planes above one another: ants on the ground, trail ribbons above them, symbol regions at the top, with faint vertical threads linking each symbol to the trails and ants that compose it. This makes the hierarchy literal, like the MU-picture's letters made of letters, and lets a viewer see in one glance that every level is the same colony described differently.

Several displays bridge the gap between mechanism and meaning:

- **Piano roll strip.** Along the bottom, a scrolling roll shows each voice's notes in its trail's color. When two voices share a trunk, their matching opening phrases are visible side by side.
- **Highlight on sound.** When a voice plays a note, the trail cell it came from pulses on screen, tying every sound to a place in the colony.

The intended experience has a clear arc. At startup the ants wander randomly and the listener hears only scattered ticks. As food is placed and trails strengthen, voices enter one by one. Each pile of food becomes new ants, so a well-fed colony grows and its music thickens. When feeding stops, food runs out, trails evaporate, the population shrinks, and the fugue dissolves back into noise.

A flood control, inspired by the fate of the colony Fermant (pp. 337–338), scatters every ant and wipes the pheromone grid, and erodes the mound to about half its height without destroying it. Over the next minute the same ants regroup into a new organization, and a different fugue emerges from the same parts, rebuilding on the old colony's damaged estate just as Aunt Hillary inherited Fermant's: in the Anteater's words, a different kind of sum rather than more than the sum (p. 338). The flood is a single button.

## Final Artifact Plan

The end user runs `java -jar aunthillary.jar`, which starts a colony at localhost, and opens it in a browser. They can:

- Place food by clicking, which feeds the colony: each pile is finite, and ants turn it into new ants, new trails and new voices.
- Move the level-of-description slider between Ants, Signals and Symbols, and zoom and tilt the camera independently of it.
- Mute individual voices to isolate one trail's melody.
- Switch between mappings to hear the same colony expressed in different ways.
- Switch on the noise control to hear the same rules played from random paths, and compare it with the colony.
- Trigger a flood to dissolve the colony and hear a new one form from the same ants.
- Open the tuning panel to change the low-level rules (number of ants, evaporation rate, trail thresholds, tempo, scale and more) while the colony runs, and hear how they change the high-level music.

The submission package will include the source code on GitHub, the executable JAR, a README with run instructions and an architecture overview, the philosophical report, and the 5–7 minute demo video. If the shared colony stretch goal is finished, it will also include the public link, and the demo video will show a live session with classmates connected.

## Timeline & Risks

The schedule follows the course phases. The simulation core and the WebSocket server both land in the logic-engine weeks, so the link between colony and browser works before any sound or visual polish depends on it.

At the end of week 13 I compare the colony against the noise control. If the colony's music is not clearly more structured, week 14 goes to tuning the mapping and trail detection, never to adding rules that compose on the colony's behalf.

| Risk | Likelihood | Fallback |
|---|---|---|
| Output sounds like noise rather than music | Medium | Pentatonic scale, beat grid and a four-voice cap make the worst case pleasant; test with fake data in week 10 |
| Colony's music sounds no more structured than the noise control | Medium | Tune thresholds, path sampling and note lengths; report the result honestly either way |
| Concurrency bugs between the tick loop and user actions | Medium | Only the tick loop mutates the colony; actions wait in a queue |
| Parallel ants race on the pheromone grid | Medium | Two-phase tick: ants only read the grid while moving, and deposits are merged in a separate step |
| Trail detection unstable between frames, or detecting spurs and loops as voices | Medium–High | Debug images of every detector stage from the start; develop against synthetic and saved grids; match trails by overlapping cells; smooth strength over time |
| Tuning the colony takes far longer than building it | High | Live tuning panel with presets, headless runner for fast comparisons, pause/step/fast-forward on the tick loop |
| Trait inheritance runs over time | High | Treat it as a stretch goal and cut it first |
| Three.js learning curve slows the visualization weeks | Medium | Build the flat top-down view first; the stacked-levels view is added only once it works |
| Hand-written scheduler drifts or stutters | Low | Follow the well-documented lookahead pattern; prototype it with fake data in week 10; Tone.js is the drop-in fallback |
| Ants all crowd onto one trail | Medium | Probabilistic following, scout ants and preferred headings keep a share of ants exploring; tune the scout share |
| Audio stalls when the tab is in the background | Low | Browsers throttle background timers; pause the scheduler while hidden and resync to the server's tick on return |
| The renderer and the sound engine wait on the simulation | Medium | Both are built against the fake colony, which speaks the same messages as the real one |
| WebGPURenderer or TSL proves too immature or hard to debug | Medium | One-day spike at the start of week 13; if it stalls, switch to WebGLRenderer, which changes only the shader code |

## Action Plan

The work is split into phases. Phase 0 sets up the shared ground the others build on, so it is done first, one step at a time. Phase 1 is four independent pieces that can be built in parallel, by separate Claude subagents each working in its own git worktree, against the interfaces above and the fake colony. Integration and tuning come after, done together with me, because judging whether the colony sounds like music takes a listener. Each agent explains what it built when it finishes, since I need to understand all of the code.

### Phase 0: shared ground (sequential)

1. **Layout.** Move the Kotlin code into the `net.aprlflwrs.aunthillary` package and the folders from the Code Plan, updating the module paths in `application.yaml` to match. Remove the generated sample routes, and serve the client at `/`.
2. **Protocol.** Kotlin encoders for every server message and a matching JavaScript decoder, with a test that encodes in Kotlin and checks the exact bytes against this document.
3. **Params registry.** One place in the core where parameters are declared, the `params` message built from it, and `setParam` applied through the action queue.
4. **Fake colony.** Ants wandering along a few drifting curves, and voices that appear, change and fade, sent through the real protocol.
5. **Client skeleton.** `index.html` with an import map, pinned copies of three.js, lil-gui and smplr in `vendor/`, the WebSocket connection, and a tuning panel built from the `params` message.
6. **CLAUDE.md.** Points every future session and agent at this plan, and lists the build commands and the rules each part keeps.

### Phase 1: four parallel pieces

| Piece | Builds | Works against | Done when |
|---|---|---|---|
| **A. Colony core** | Ant, PheromoneGrid, Terrain, nest and food economy, mound, castes and headings, two-phase tick, headless runner | Headless runner output | With two food piles, headless runs show trails forming to both, the population settling in the target range, and the trails fading after the food runs out |
| **B. Trail detector** | Threshold, hub mask, thinning, network, paths to food, matching with stable IDs, smoothing, debug images | Synthetic grids (painted trails with noise, forks, spurs and gaps), then saved grids from A | On a synthetic fork that drifts over time, two voices keep their IDs and share their trunk, and spurs never become voices |
| **C. Renderer** | WebGPURenderer spike first, then instanced ants with interpolation, terrain with pheromone texture, voice ribbons, top-down camera with scroll zoom and drag pan, food placement by click, level slider, tilt control | Fake colony | 30,000 fake ants run smoothly with the pheromone texture and ribbons, and the slider crossfades the levels |
| **D. Sound engine** | Lookahead scheduler, self-hosted smplr instruments, organ point, the mapping and path source interfaces, the three starting mappings, the noise path source, piano roll | Fake colony voices | Fake voices play in time with no drift over ten minutes, mappings and the noise control switch while playing, and the piano roll matches what is heard |

To keep the pieces from colliding: A and B share no files; B takes a plain float array rather than A's classes; C owns the slider and calls a `setLevel(level)` function that D provides for the sound; the tuning panel from Phase 0 is shared, and new parameters are added only through the registry.

### Phase 2: integration (with me)

Replace the fake colony with the real one, run the trail detector in the tick loop every half second, and listen to the colony for the first time.

### Phase 3: tuning (with me)

Iterate on parameters, presets and mappings, using the headless runner for low-level rules and the browser for the music. Compare the colony with the noise control for every mapping, and record the results for the report.

### Phase 4: the remaining features and the submission

The Symbols level and heading ring, the stacked view, highlight on sound, the flood and mound erosion, packaging the JAR, the README, the report and the demo video. Genomes and the shared colony come after these, if there is time.

## Stretch: Shared Colony

The original plan put the colony on my server so that everyone in the class could open it at once. That remains the strongest version of the concept, and is the main stretch goal once the local version works:

**Why a shared server.** A single shared colony is the strongest version of the concept: there is literally one Aunt Hillary, and every visitor's actions change the same colony and the same music. The server's CPU also supports a far larger colony than a laptop could run, which gives trails and voices more room to emerge.

Streaming signals rather than ants is both an engineering necessity and a statement of the project's thesis: the level that carries meaning is also the level worth sending.

Adding it means hosting on my own server (Intel i7-14700K) behind an HTTPS reverse proxy, and solving the problems that the network hop brings:

- **Bandwidth.** Sending every ant to every browser no longer works. Ants go only to zoomed-in clients and only for the area on their screen; everyone receives the colony state and the downsampled pheromone map; zoomed-out clients receive a low-rate summary of colony regions. Ant frames still need IDs for interpolation, so they should be capped or sent at a lower rate.
- **Reliability.** Ant and pheromone frames can stay lossy. The colony state already carries everything in full, so it needs nothing extra.
- **Synchronization.** Each browser composes the music itself, so listeners in the same room would hear the same fugue at slightly different offsets. The server would broadcast a beat clock, each browser would estimate its clock offset, and notes would be scheduled on shared beat boundaries. With a class of five this matters less, and may not be needed.
- **People.** Each connected person appears as a small colored marker, and food they place carries their color, so everyone can see who started which trail, and therefore which voice. Each client may place a limited amount of food per minute, so one person cannot dominate the shared colony. The flood requires a majority vote, counted only among recently active visitors so that idle tabs cannot block it. Parameters and controls such as pause are only available in a local colony.
- **Exposure.** A public WebSocket needs message size limits, input validation and a cap on connections.

## Open Decisions

These are proposals to settle during the work, not settled design:

- **How symbols act.** In the dialogue, symbols trigger one another. Here a symbol is a region of sustained food gathering, and it already acts downward: it shapes the heading distribution, and so where newborn ants go. A next step would be symbols affecting each other, for example a region whose food is running low raising the scouting share for its direction.
- **Population target.** 5,000–30,000 is a starting guess, to be settled with the headless runner.
- **Noise control details.** Whether matching average bending is enough, or the noise paths should also match how bending is spread along the path.
- **Page references.** The plan cites p. 328 in one place and p. 329 in another for a signal keeping its identity while its membership turns over; to be checked against the book.
