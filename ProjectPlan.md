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
| Client rendering | JavaScript + Three.js r187 (WebGLRenderer) | Renders the colony in 3D on the GPU: instanced ants, pheromone as a texture, levels stacked in space |
| Client audio | Web Audio API + smplr | Turns the colony's voices into music in the browser; shares Three.js's audio context so each voice can be placed in 3D space at its trail |
| Hosting | Localhost (`java -jar aunthillary.jar`, then open `http://localhost:8080`) | Runs on any machine with Java; the i7-14700K server hosts the shared colony stretch goal |
| Packaging | Executable fat JAR (Ktor Gradle plugin) | The full app as one file, runnable anywhere with java -jar |

**Why Kotlin and Ktor.** The simulation is naturally object-oriented: ants, trails and colonies are entities with state and behavior. Kotlin's data classes and sealed classes suit the message types the server sends, and it builds on my Java experience. Ktor is JetBrains' lightweight Kotlin server framework. The server layer is small (a tick loop, WebSocket connections and static files), so a full enterprise framework like Spring Boot would add weight without adding anything this project uses. Ktor is built on coroutines, which fit the design directly: the tick loop is one coroutine, and each connected client gets its own.

**Why Three.js.** A Kotlin server process can simulate far more ants than a 2D canvas library like p5.js can draw, which tops out at roughly several thousand shapes per frame. Three.js renders on the GPU, so one instanced mesh can draw 100,000+ ants in a single draw call, and the pheromone grid can be uploaded as one texture. It also makes the levels of description spatial: the camera can tilt from a flat top-down view into a view where ants, signals and symbols sit on stacked planes. The cost is a steeper learning curve (scenes, cameras, buffers and some shader code), which the timeline accounts for. I will use the established WebGLRenderer, which runs in every current browser. The newer WebGPURenderer was considered and dropped: nothing in the core plan needs it, its shaders must be written in TSL instead of GLSL, and the three.js manual still calls it experimental. The custom shader work is small (a GLSL fragment that colors the pheromone texture).

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

- **Ant**: position, heading, state (Searching or Returning), an optional food load, and two traits set at birth: a preferred heading and a fidelity. Each tick it moves forward with small random turns, holding roughly its direction so searchers fan outward instead of jittering in place, and drifting gently toward its preferred heading. When it senses food pheromone in its three forward cones, it steers toward the strongest with a probability set by its fidelity; otherwise it keeps exploring. Food gives off a short-range scent, so a searcher that wanders close can home in on it. A searching ant lays home pheromone as it walks, in an amount that falls off with the time since it left the nest, so the home layer is strongest near the nest and a lost ant cannot lay a misleading trail; once it picks up food it becomes a returning ant, which steers by the home pheromone (with a weak pull toward the nest's direction as a backup, standing in for real ants' path integration) and lays food pheromone on its way back to the nest (falling off the same way with the time since it picked the food up). Each layer is therefore laid by the ants that are not following it.
- **PheromoneGrid**: a 2D float array, starting at 512 × 512 cells (about 1 MB per layer, each cell roughly one ant length), with separate layers for "toward food" and "toward home." It evaporates and diffuses slightly every tick. Ants move with continuous positions rather than hopping cell to cell, and sample the grid with bilinear interpolation, so steering stays smooth across cell boundaries. All rates (evaporation, diffusion, speed, sensing distance) are defined in world units rather than per cell, so the resolution can be tuned in week 11 by benchmarking 256², 512² and 1024² without changing how the colony behaves. It is the colony's shared memory: no ant holds it, all ants write to it.
- **Terrain**: a second 2D grid holds ground height (gentle generated hills) and blocked cells (rocks, twigs). Ants slow on steep slopes and cannot enter blocked cells, so trails curve through valleys and around obstacles instead of running straight. The simulation stays 2D; an ant's 3D position is just its grid coordinates plus the ground height beneath it.
- **Nest and food economy**: each food pile holds a finite amount and shrinks as ants carry it away. Returning ants add food to a store at the nest. Every ant also has to eat: its energy runs down as it walks, it refuels from the store whenever it is at the nest, and an ant whose energy runs out starves. The nest hatches new ants only from the food beyond what the colony is eating. A well-fed colony's ants live long lives, ending in old age (kept, so that the colony's membership keeps turning over); a hungry colony's ants starve early. Each pile can only give out so much food a second, the diminishing return that lets the population settle (without it, deliveries grow with the population and the colony grows or collapses without end); an ant that waits too long at a busy pile gives up and goes home, so a fed colony's ants die mostly of old age, not hunger. The population therefore settles wherever incoming food balances what the ants eat, and no rule sets its size: more food, or food closer to the nest, supports more ants, and when feeding stops the colony starves back down, to a floor of a few hundred ants so it never dies out. (A first version capped the birth rate instead, like a queen's laying limit; that worked, but fixed the population at the cap times the lifespan, so it was replaced.) Every trail threshold depends on how many ants there are, so the economy (metabolism, hatch cost, pile size) is tuned so that ordinary feeding gives roughly 5,000–30,000 ants; a hard cap of 100,000, which the renderer is sized for, exists only as a safety limit. Births lag behind food, so the population may swing between boom and bust; the store damps this, and some swing is welcome if it makes the music swell and thin. Constant turnover also means trails keep their identity while their membership changes completely, as the Anteater describes (p. 329).
- **Castes and headings**: fidelity varies across the colony. Low-fidelity ants act as scouts that keep exploring even when strong trails exist; high-fidelity ants act as foragers that work established trails. This keeps the colony from collapsing onto a single trail, and echoes the castes in the dialogue (pp. 322–324). Preferred headings are a soft bias, not a hard boundary: an ant can still follow a strong trail outside its direction. The nest assigns each newborn a heading drawn mostly from the directions foragers have brought food from, with a fixed share chosen at random so exploration never stops; and every ant that comes home empty-handed draws a fresh heading the same way when it eats (recruitment), so the distribution steers the whole working colony, not only its newborns. A searcher that finds nothing within a give-up time turns home empty-handed, laying no food pheromone, and its arrival counts against the direction it searched, so the colony can unlearn a direction once its food is gone. The distribution changes only with evidence, deliveries and empty-handed returns, not with time alone: a busy colony unlearns a dead direction quickly, while an idle one keeps its belief until something contradicts it. That distribution of headings is the colony's version of Hofstadter's caste distribution: a pattern no single ant holds, which shifts to reflect where food is in the world (pp. 324, 329).
- **Mound**: the nest raises the terrain around it into a cone that grows a little with every unit of food brought home, up to a cap. Ants climb it on the way home, and since several mappings read terrain height, melodies near the nest change as it grows. The mound is the colony's property, not the colony: in the dialogue, Aunt Hillary lives on an estate that "used to belong to someone else" (p. 336).
- **TrailDetector (the signal level)**: the pheromone grid is only a field of numbers; nothing in it is a "trail" yet. The detector finds trails in that field and turns them into objects the music can use. Every half second it:
    1. Sets a bar for strong scent. It reads only the food-pheromone layer, which ants lay while carrying food, so it marks routes to food rather than general wandering. A threshold decides which routes count (step 4); the search itself can cross weaker cells, at a higher cost. Faint, scattered pheromone never becomes a trail. This models the Anteater's point that activity either fizzles out or snowballs past a critical mass (p. 322).
    2. Sets aside the hub. Every route crowds together at the nest, so cells within a fixed radius of it are ignored, and detection starts at the hub's edge.
    3. Finds the cheapest routes. One shortest-path search runs outward from the hub's edge over the whole grid, where crossing a cell costs more the less food pheromone it holds (blocked cells cannot be crossed). Cells on last time's voices get a small discount, so a voice does not flicker between two nearly equal routes.
    4. Finds the voices. From each food pile, the search is traced back to the hub. That route becomes a voice only if the pheromone along it stays above the threshold from step 1 (short gaps allowed), so a pile no trail has reached yet has no voice. Because all routes come from one search they form a tree: voices heading the same way share their trunk and split at a junction, and spurs and loops cannot occur. A consequence: two trails that cross, or pass within a cell of each other, are joined there, and one voice follows the other trail home from that point. A voice's strength is the median pheromone strength along its route. If more than four piles have voices, the four strongest play, and a voice that already holds a slot keeps it until it fades.
    5. Names the voices. A voice is identified by the food pile it reaches, so it keeps its ID, color and instrument even as the ants on it change and its route shifts, as Hofstadter's signals do (p. 328). A pile that runs out stays behind as an invisible target until its voice fades, so a melody fades with its trail's pheromone instead of stopping the moment the food is gone.

  Each voice's path is smoothed into a curve, and that curve is what the client receives and what the melody walks along. The grid is the ant-level medium; the network of curves is the signal-level object. The detector takes a plain snapshot of the food layer (a float array and its size) rather than the live grid, so it can be developed and tested against synthetic grids and saved snapshots, and it writes debug images of each stage (threshold, cost field, routes, voices) so its behavior can be seen while tuning.
- **SymbolTracker (the symbol level)**: a symbol is a persistent peak in the heading distribution. Twice a second the tracker finds the peaks that rise above the level an evenly spread colony would have, with separate thresholds for appearing and disappearing so a peak does not flicker, and keeps each peak's identity for as long as it overlaps its previous position on the ring. Nothing in the code says what a peak means; "food to the north-east" is a reading of it, the way the Anteater reads Aunt Hillary. A symbol has the properties the dialogue asks for:
    - It is made of signals, not ants: every trail returning from that direction feeds the same peak, so several voices can compose one symbol (pp. 329–330).
    - It outlives its parts. When a food pile runs out and its trail evaporates, the peak lingers until empty-handed returns wear it down.
    - It is about the world, and can be wrong: a lingering peak is a belief in food that is no longer there, and the colony keeps acting on it.
    - It acts. The peak decides where newborns and recruited ants head, and because the distribution's shares add up to one, a growing symbol shrinks the others, so symbols compete without any rule saying so.

  The headless runner tests this with a lesion: let a colony build a peak, remove the food, then place new food in the same direction and measure how long the colony takes to bring it home, once with the distribution left alone and once with it flattened at the moment the food is removed (flattening it only when the new food appears changes less, because the distribution acts only through the ants that draw a heading after it changes), from the same seed, with the population held fixed so that both runs have the same number of ants. The peak must survive the wait, or the test measures only the ants that drew headings while it lingered; that is why the distribution changes only with evidence, not with time. No ant differs between the two runs; any difference in the result belongs to the symbol.
- **ColonyState**: what crosses out of the core is the full current state, not a stream of events: the voices with their curves, the symbols, food piles, the nest, and the heading distribution. The server sends it in full about twice a second, and the client works out what changed (a voice entering, a voice fading) by comparing it with the previous state. Any lost or skipped message is corrected by the next one.
- **Params**: every tunable number in the core (rates, thresholds, economy, caste shares) is registered in one place with a name, range and default, and can be changed while the colony runs. The tuning panel in the browser is built from this list, so adding a parameter in the code adds a slider automatically.

### 2. colony-server (Ktor)

- **Tick loop**: a single coroutine owns the colony and advances it at a fixed rate (about 30 ticks per second). The colony is updated on a single thread: at the target population that is under a million ant updates a second, which one core handles easily. All randomness comes from one seeded generator, and every tick advances simulated time by exactly 1/30 s (the speed control changes how often ticks run, never how long one is). Every user action and parameter change is logged with the tick it was applied on, so a run with the same seed and the same action log replays exactly, including a live session replayed headless. That is what makes headless comparisons and regression tests meaningful. The tick is profiled by stage (ants, evaporation and diffusion, trail detection); parallel updates, or any change to how often a stage runs, wait until profiling at the population cap shows they are needed, since running a stage less often would change how the colony behaves.
- **Trail detection timing**: the detector works on a snapshot of the food layer taken at fixed tick numbers (every 15 ticks). On the live server it runs on its own coroutine, so a slow search never stalls a tick, and its result is published when it finishes. In the headless runner it runs in step with the ticks, so fast-forwarded runs never outpace it. Nothing the detector produces feeds back into the colony, so either way the colony itself replays exactly. The tick loop can be paused, stepped one tick at a time, and run faster than real time, which helps tuning.
- **Action queue**: user actions and parameter changes arriving over WebSocket are queued and applied at the start of the next tick, so the colony is only ever mutated by the tick loop. This avoids race conditions between the browser and the simulation without locks.
- **Streamer**: the connected browser has its own coroutine, and the tick loop hands it messages through one latest-value slot per message type (ants, pheromone, terrain, state, params), so the tick loop never waits on the browser (for example while its tab is in the background). A newer message overwrites an unsent older one of the same type only. Because every message carries complete state rather than a change, an overwritten message costs nothing, and a terrain or parameter message, which is sent only when it changes, can never be pushed out by a burst of ant frames. On localhost there is no need to choose what to send, so the browser receives everything:
    - Ants: every ant's position, every tick, quantized to 16-bit integers in a binary message.
    - Pheromone: a downsampled map of both layers (128 × 128, one byte per cell) a few times a second for the ground texture.
    - Terrain: the height and blocked grids on connect, and again when the mound changes (at most once a second).
    - Colony state: the voices and their curves, the symbols, food, nest and heading distribution as JSON, about twice a second. These are small and drive the music.
- **Fake colony**: a stand-in that produces moving ants and slowly drifting voices through exactly the same messages, so the renderer and sound engine can be built and tested before the real simulation and trail detector exist.
- **Headless runner**: runs the colony without a browser, as fast as the CPU allows, and saves pheromone images, detector debug images and metrics (population, number of voices, voice lifetimes) at intervals. Tuning the low-level rules is mostly done by comparing these runs.

### 3. Browser client (JavaScript)

- **Renderer (Three.js)**: ants are one InstancedMesh sized for the maximum population, with its count lowered to the live population each frame, its instance buffer marked for dynamic updates, and frustum culling turned off because ants span the whole map; the terrain is a ground mesh built from the height grid, with the pheromone grid draped over it as a DataTexture colored by a small GLSL shader; trails are ribbon meshes, one color per voice; the heading distribution is a ring around the nest, and each symbol is a glowing lobe on it, with faint threads to the trails that feed it. An orthographic camera gives the default top-down view, and a perspective camera handles the tilted stacked-levels view. The renderer runs at the display's refresh rate while the server ticks at 30 Hz, and at first simply draws each ant at its latest position: at colony zoom an ant moves a pixel or two per tick, so the steps are not visible. Interpolating between ticks is added only if motion looks jerky when zoomed in; it needs stable ant IDs, which would be appended to the ant frame under a new format version.
- **Sound engine (Web Audio API + smplr)**: a custom lookahead scheduler (about 200 lines) wakes every 25 ms and books any notes due in the next 100 ms against the audio clock, the standard "two clocks" pattern, so timing stays sample-accurate even when the page or network stutters. smplr (version pinned, since its API is still settling) supplies sampled General MIDI instruments: an organ for the voices and a soft percussive sample for ant ticks, self-hosted rather than fetched from smplr's default online sample source. Everything shares the AudioContext of the Three.js AudioListener, which is resumed when the visitor clicks Join, because browsers block audio until a user gesture. Each voice gets its own instrument instance whose destination is a GainNode fed into a Three.js PositionalAudio object placed at its trail. The engine turns voices into music under fixed constraints:
    - Scale: pitches come only from a pentatonic scale, so no combination clashes.
    - Rhythm: notes are quantized to an 8th-note grid at about 100 BPM.
    - Voices: each strong trail becomes one voice, capped at four. Its melody is read along its curve from the nest end outward, one 8th note per fixed distance walked, looping back to the nest end when it reaches the food.
    - Mappings: how a point on a curve becomes a note is a swappable mapping, chosen from a menu while the colony plays, so different ways of expressing the colony can be compared on the same trails. A mapping only chooses a scale degree, loudness, length and timbre; the scale and beat grid are applied afterwards, the same way for every mapping. The first mappings to try:
        - Compass position (the original design): north–south position sets the scale degree, east–west position sets timbre (mellow organ stops in the west, brighter reed stops in the east), and terrain height sets loudness and note length. Its weakness is that a straight trail's melody is set almost entirely by its direction: a trail running east repeats one note.
        - Turning: the trail's heading at each point, measured relative to its heading where it leaves the hub, sets the scale degree (a trail that has turned left sits higher, one that has turned right sits lower, a straight run repeats), so the melody comes from how the trail bends around terrain and obstacles rather than from where it points. Using net turning rather than curvature keeps the mapping a pure function of the point, and keeps it steady when the curve is re-smoothed.
        - Distance and height: distance from the nest sets register and terrain height sets the scale degree, so a trail climbing over a hill sounds like one.

      Pitch depends on the colony, never on the viewer's camera, so the same trail always produces the same melody.
    - Shared openings: there is no imitation rule. Voices whose paths share a trunk play the same opening phrase and then diverge where their paths split, and they enter at different times because their trails formed at different times. Any subject-and-answer a listener hears comes from the colony's geometry and timing, not from the code.
    - Organ point: a low sustained note holds the key under everything (synthesized directly with a few Web Audio oscillators, since sampled notes decay and smplr's sample looping is still experimental), the device the Tortoise points out in the dialogue (pp. 334–335). Its loudness and richness follow the nest's food store, so the drone tracks the colony's health while the trail voices carry its activity.
    - Symbol tones: each symbol holds one sustained tone above the organ point, synthesized the same way. Its direction on the ring picks its scale degree and its strength sets its loudness. The tones are the colony's beliefs made audible: when a trail dies its melody stops, but its symbol's tone lingers and fades as the colony unlearns that direction.
    - Noise control: a toggle swaps the colony's trails for random wandering paths fed through exactly the same rules. The scale, beat grid and instruments only constrain what can be played; everything else should come from the colony. Whatever difference a listener hears between the colony and the control is what the ants contributed. For that comparison to be fair, the control must differ from the colony only in what the ants could have contributed: each random path stands in for one real voice, starts at the hub's edge like it, has the same length, bends as much on average, goes through the same smoothing, and enters and fades at the same moments. What remains different is where the paths go: shared trunks, routes toward food, curves around terrain. In code, the colony and the control are two interchangeable path sources feeding whichever mapping is selected. This toggle is for listening. Because its paths are independent, they can never share a trunk, so the measured comparison (see Timeline & Risks) uses a different baseline that goes through the detector like the colony does.
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
| 12 | u16 × *n* | x of every ant, each as `round(value / 512 × 65535)` |
| 12 + 2*n* | u16 × *n* | y of every ant, same scaling |
| 12 + 4*n* | u8 × *n* | heading of every ant, as `round(heading / 2π × 256) mod 256` |
| 12 + 5*n* | u8 × *n* | flags of every ant: bit 0 returning, bit 1 carrying food, bit 2 scout |

Each field is one contiguous block, so the browser reads it as a typed array without copying (the header is 12 bytes, so the u16 blocks are aligned). Ants appear in no guaranteed order from one frame to the next. At 30,000 ants this is about 5.4 MB/s, which is fine on localhost.

**Pheromone frame** (type 2, a few times a second): `u8 type = 2, u8 version, u16 n (= 128), u32 tick`, then `n × n` bytes of the food layer followed by `n × n` bytes of the home layer. Rows run south to north and each row runs west to east. Each byte is `round(255 × sqrt(min(value / pheromone.displayMax, 1)))`, where `pheromone.displayMax` is a registered parameter; the square root keeps faint trails visible beside the much stronger hub.

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
      "points": [[268.1, 259.0, 1.4, 0.91], [270.0, 260.2, 1.5, 0.88]] }
  ],
  "symbols": [
    { "id": 2, "direction": 0.79, "width": 0.61, "strength": 0.41,
      "bornTick": 9000, "voiceIds": [3] }
  ],
  "headings": [0.02, 0.10]
}
```

- `voices[].id` stays the same for as long as its food pile has a voice. `slot` (0–3) is fixed for the voice's lifetime and picks its color and instrument.
- `points` is the smoothed curve as `[x, y, height, strength]`, about every 2 world units, starting at the hub's edge and ending near the food. A point's `strength` is the food pheromone there as `min(value / pheromone.displayMax, 1)`.
- The voice's own `strength` (0–1) is the median of its points' strengths. A voice that is fading stays in the list with falling strength until it is gone.
- `symbols[].id` stays the same for as long as the tracker matches the peak. `direction` and `width` are in radians, using the heading convention above. `strength` runs from 0 to 1: how far the peak stands above an even spread. `voiceIds` lists the voices whose food pile lies within the peak's width as seen from the nest; it can be empty, which is a symbol whose trails are gone.
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
  - `x`, `y`, `height`, `heading` and `curvature` at that point, and `turning`: the heading there relative to the heading at the start of the path.
  - `strength`: the food pheromone at that point (0–1), so a mapping can let how busy the trail is, and not only its shape, affect the music (for example resting where the trail is thin).
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
| Symbols | Trails fade; a ring around the hill shows the colony's distribution of preferred headings, with each symbol a glowing lobe on it, threaded to the trails that feed it. An optional gloss reads each lobe aloud ("food: north-east"), marked as the observer's reading, not the colony's | The full fugue over the symbol tones, including the tones of symbols whose trails are gone |

The slider and the camera are separate. Scrolling zooms from the colony as a whole down to individual ants at any slider level, so a viewer can look closely at the ants that make up a trail while listening to the full fugue, or look at the whole colony while hearing only ant ticks. Moving the slider crossfades both the picture and the sound between levels rather than switching abruptly. A second control tilts the camera into a stacked view, where the three levels float on separate planes above one another: ants on the ground, trail ribbons above them, the symbol ring at the top, with faint vertical threads linking each symbol to the trails and ants that compose it. This makes the hierarchy literal, like the MU-picture's letters made of letters, and lets a viewer see in one glance that every level is the same colony described differently.

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

The comparison is fixed in advance, so that tuning cannot quietly decide it. The baseline is the same colony, with the same seed, food placements and action log, but with trail-following turned off: ants still wander, lay pheromone and carry food, but never steer by pheromone. Without following, ants find food only by stumbling on it, so a free-running baseline starves down to the population floor (about 300 ants against about 15,000); both runs therefore hold the population fixed at the same size, so that the only difference between them is following. Its pheromone goes through the same detector and the same mapping, so any difference is what following trails, the colony's only form of coordination, adds. Both runs are scored the same way on three numbers:

- the share of the time with at least one voice;
- the share of the time with at least two voices;
- over the moments with at least two voices, the average length of the opening phrase that pairs of voices share.

If the baseline never has two voices at once, the third number is reported as undefined, not as zero. The first two then carry the result: following is what makes trails exist at all. A blind test adds a listener's judgment: classmates hear unlabeled pairs of clips, taken from moments when both runs have at least two voices, and say which is the colony. If the baseline has too few such moments, the blind test pairs the colony with the random-path control instead, and the report says so.

| Risk | Likelihood | Fallback |
|---|---|---|
| Output sounds like noise rather than music | Medium | Pentatonic scale, beat grid and a four-voice cap make the worst case pleasant; test with fake data in week 10 |
| Colony's music sounds no more structured than the noise control | Medium–High | The measure is fixed before tuning starts (see above); tune thresholds, path sampling and note lengths; report the result honestly either way |
| Concurrency bugs between the tick loop and user actions | Medium | Only the tick loop mutates the colony; actions wait in a queue |
| The grader's machine has no Java 21 | Medium | The README states the requirement and links an installer; the demo video shows everything if the JAR cannot be run |
| A voice's route flickers between two nearly equal paths | Medium | Routes come from one shortest-path search, so spurs and loops cannot occur; last time's route gets a cost discount; debug images of every detector stage from the start; develop against synthetic and saved grids; smooth strength over time |
| The lesion test shows no difference, so the symbols do nothing measurable | Medium | Tune how long the heading distribution remembers and the share of newborns it steers; report the result honestly either way |
| Tuning the colony takes far longer than building it | High | Live tuning panel with presets, headless runner for fast comparisons, pause/step/fast-forward on the tick loop |
| Trait inheritance runs over time | High | Treat it as a stretch goal and cut it first |
| Three.js learning curve slows the visualization weeks | Medium | Build the flat top-down view first; the stacked-levels view is added only once it works |
| Hand-written scheduler drifts or stutters | Low | Follow the well-documented lookahead pattern; prototype it with fake data in week 10; Tone.js is the drop-in fallback |
| Ants all crowd onto one trail | Medium | Probabilistic following, scout ants and preferred headings keep a share of ants exploring; tune the scout share |
| Audio stalls when the tab is in the background | Low | Browsers throttle background timers; pause the scheduler while hidden and resync to the server's tick on return |
| The renderer and the sound engine wait on the simulation | Medium | Both are built against the fake colony, which speaks the same messages as the real one |

## Action Plan

The work is split into phases. Phase 0 sets up the shared ground the others build on, so it is done first, one step at a time. Phase 1 is four independent pieces that can be built in parallel, by separate Claude subagents each working in its own git worktree, against the interfaces above and the fake colony. Integration and tuning come after, done together with me, because judging whether the colony sounds like music takes a listener. Each agent explains what it built when it finishes, since I need to understand all of the code.

### Phase 0: shared ground (sequential)

1. **Layout.** Move the Kotlin code into the `net.aprlflwrs.aunthillary` package and the folders from the Code Plan, updating the module paths in `application.yaml` to match. Remove the generated sample routes, and serve the client at `/`.
2. **Protocol.** Kotlin encoders for every server message and a matching JavaScript decoder, with a test that encodes in Kotlin and checks the exact bytes against this document.
3. **Params registry.** One place in the core where parameters are declared, the `params` message built from it, and `setParam` applied through the action queue.
4. **Fake colony.** Ants wandering along a few drifting curves, voices that appear, change and fade, and symbols that rise and linger after their voices, sent through the real protocol.
5. **Client skeleton.** `index.html` with an import map, pinned copies of three.js, lil-gui and smplr in `vendor/`, the WebSocket connection, and a tuning panel built from the `params` message.
6. **CLAUDE.md.** Points every future session and agent at this plan, and lists the build commands and the rules each part keeps.

### Phase 1: four parallel pieces

| Piece | Builds | Works against | Done when |
|---|---|---|---|
| **A. Colony core** | Ant (including empty-handed returns and a switch that turns trail-following off for the baseline), PheromoneGrid, Terrain, nest and food economy, mound, castes and headings, symbol tracker, seeded single-threaded tick with fixed time steps, action log and replay, per-stage profiling, headless runner with the lesion test | Headless runner output | With two food piles and a fixed seed, tests on the headless metrics (not a judgment by eye) pass: food pheromone links the nest to both piles, the population settles in the target range, the trails fade after the food runs out, a symbol forms toward each pile and wears down after its food is gone, and two runs with the same seed and action log match exactly |
| **B. Trail detector** | Threshold, hub mask, shortest-path search, routes traced back from each food pile, voices named by food pile, slot assignment, smoothing, debug images | Synthetic grids (painted trails with noise, forks, spurs and gaps), then saved grids from A | On a synthetic fork that drifts over time, two voices keep their IDs and share their trunk, a pile with no trail to it gets no voice, and a fifth pile never takes a slot from a living voice |
| **C. Renderer** | Instanced ants, terrain with pheromone texture, voice ribbons, top-down camera with scroll zoom and drag pan, food placement by click, level slider, tilt control, tuning presets (save and reload named settings) | Fake colony | 30,000 fake ants run smoothly with the pheromone texture and ribbons, the slider crossfades the levels, and a saved preset reloads every server and client parameter |
| **D. Sound engine** | Lookahead scheduler, self-hosted smplr instruments, organ point, the mapping and path source interfaces, the three starting mappings, the noise path source, piano roll | Fake colony voices | Fake voices play in time with no drift over ten minutes, mappings and the noise control switch while playing, and the piano roll matches what is heard |

To keep the pieces from colliding: A and B share no files; B takes a plain float array rather than A's classes; C owns the slider and calls a `setLevel(level)` function that D provides for the sound; the tuning panel from Phase 0 is shared, and new parameters are added only through the registry.

### Phase 2: integration (with me)

Replace the fake colony with the real one, run the trail detector every half second (on its own coroutine on the server, in step in the headless runner), and listen to the colony for the first time.

### Phase 3: tuning (with me)

Iterate on parameters, presets and mappings, using the headless runner for low-level rules and the browser for the music. Compare the colony with the noise control for every mapping, and record the results for the report.

### Phase 4: the remaining features and the submission

The Symbols level in the browser (the ring and its lobes, and the symbol tones), the stacked view, highlight on sound, the flood and mound erosion, packaging the JAR, the README, the report and the demo video. Genomes and the shared colony come after these, if there is time.

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

- **Who holds the symbols.** The nest computes the heading distribution centrally, which is less ant-like than the rest of the design: no real nest keeps statistics. The genomes stretch goal would spread it across the ants, since each newborn would inherit from one forager. Unless that is built, the report names this as a limit.
- **How symbols trigger one another.** In the dialogue, symbols trigger one another. Here they only compete for newborns. Whether that is enough, or one symbol should be able to raise another, is left open.
- **Population target.** 5,000–30,000 was a starting guess. Under the hunger economy, ordinary feeding currently gives about 3,500 ants on one pile, 8,000 on two far piles and 10,000 on two close ones; whether to tune toward more is open.
- **Shared trunks.** Real trails split inside the nest's hub area, before the detector starts tracing, so voices rarely share an opening and the comparison's third number is 0 for typical layouts. Being tried: terrain near the nest (rocks or valleys) that funnels trails into shared corridors, so trunks have a natural cause. Shrinking the detector's hub area is the fallback.
- **Noise control details.** Whether matching average bending is enough, or the noise paths should also match how bending is spread along the path.
- **Page references.** The plan cites p. 328 in one place and p. 329 in another for a signal keeping its identity while its membership turns over; to be checked against the book.
