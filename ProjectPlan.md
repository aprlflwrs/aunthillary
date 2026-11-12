# Ant Fugue: An Emergent Colony That Composes

Nov 5, 2026 · @April Jarosz

## Summary

Ant Fugue is a multiplayer ant colony that composes music as it runs. A single colony lives on a server, and everyone in the class can open it in a browser at the same time: they all watch the same ants, hear the same fugue, and change it together by placing food. No single ant knows anything or plays anything meaningful, yet as pheromone trails form, the colony's sound shifts from scattered noise into a three- or four-voice fugue. The project makes Hofstadter's argument from the "Ant Fugue" dialogue in Gödel, Escher, Bach something people can watch, hear and take part in: there is one Aunt Hillary, and meaning lives at a higher level of description than the parts that produce it.

## Philosophical Concept

The project explores emergence and levels of description, the central argument of the "Ant Fugue" dialogue. The dialogue opens as a dispute between holism (the whole is more than its parts) and reductionism (a whole is fully explained by its parts). Achilles answers "Mu": he unasks the question, because both views fit inside a larger picture of levels (p. 317).

Aunt Hillary is an ant colony who converses with the Anteater through the trails her ants form, yet "all the ants in Aunt Hillary are as dumb as can be" (p. 320). The Tortoise compares this to a brain built from unintelligent neurons (p. 320). The Anteater then describes three levels of organization:

- **Ants**: individuals that wander almost at random, yet produce stable overall trends through the regularity of statistics (p. 321). An activity like trail-building only sustains itself once a threshold number of ants joins in; below it, the effort fizzles out (p. 322).
- **Signals**: the lowest-level teams, which form, migrate through the colony and dissolve where they are needed (p. 325). A signal keeps its identity even when its membership turns over completely (p. 328).
- **Symbols**: higher-level teams made of teams, which the Anteater calls active subsystems that trigger one another (pp. 329–330). Only at this level does the colony's activity carry meaning, just as words carry meaning while their letters do not (pp. 330–332).

The dialogue is itself written as a four-voice fugue, and its central image ties the two subjects together. Signals can pass through one another, each treating the other as background, and Achilles pictures the result as an orderly criss-crossing pattern: "An ant fugue!" (p. 327). This gives the project its second theme, isomorphism: ants, signals and symbols correspond to notes, voices and the fugue as a whole. The program maps one structure onto the other directly, so the colony's levels become audible.

The project asks one question: at what point does the colony's output stop being noise and start being music, and where does that meaning live? No line of code says "play a fugue." The Anteater admits he can describe the same colony with or without meaning and purpose, depending on the level he chooses to view it from (p. 327). The program's level-of-description slider gives the user that same choice.

## Platform & Software Decision

The project is a client–server web application. One authoritative colony runs on my own server, and any number of browsers connect to it at once.

| Component | Tool | Role |
|---|---|---|
| Simulation server | Kotlin on Ktor (JVM 21+) | Runs the one shared colony: tick loop, pheromone grid, trail detection |
| Real-time transport | Ktor WebSockets | Pushes colony updates to every client and receives user actions |
| Client rendering | JavaScript + Three.js r187 (WebGPURenderer) | Renders the colony in 3D on the GPU: instanced ants, pheromone as a texture, levels stacked in space |
| Client audio | Web Audio API + smplr | Turns trail events into music locally in each browser; shares Three.js's audio context so each voice can be placed in 3D space at its trail |
| Hosting | My own server (Intel i7-14700K) behind an HTTPS reverse proxy | A public URL the whole class can open |
| Packaging | Executable fat JAR (Ktor Gradle plugin) | The full app as one file, runnable anywhere with java -jar |

**Why a shared server.** A single shared colony is the strongest version of the concept: there is literally one Aunt Hillary, and every visitor's actions change the same colony and the same music. The server's CPU also supports a far larger colony than a laptop could run, which gives trails and voices more room to emerge.

**Why Kotlin and Ktor.** The simulation is naturally object-oriented: ants, trails and colonies are entities with state and behavior. Kotlin's data classes and sealed classes suit the event types the server broadcasts, and it builds on my Java experience. Ktor is JetBrains' lightweight Kotlin server framework. The server layer is small (a tick loop, WebSocket connections and static files), so a full enterprise framework like Spring Boot would add weight without adding anything this project uses. Ktor is built on coroutines, which fit the design directly: the tick loop is one coroutine, and each connected client gets its own.

**Why Three.js.** A shared server can simulate far more ants than a 2D canvas library like p5.js can draw, which tops out at roughly several thousand shapes per frame. Three.js renders on the GPU, so one instanced mesh can draw 100,000+ ants in a single draw call, and the pheromone grid can be uploaded as one texture. It also makes the levels of description spatial: the camera can tilt from a flat top-down view into a view where ants, signals and symbols sit on stacked planes. The cost is a steeper learning curve (scenes, cameras, buffers and some shader code), which the timeline accounts for. I will use the newer WebGPURenderer, which runs on WebGPU where the browser supports it (Chrome, Edge, Safari 26+, and Firefox on Windows and Apple Silicon Macs) and falls back to WebGL 2 automatically everywhere else, so every visitor can still see the colony. Its cost is that custom shaders are written in TSL, three.js's node-based shading language, instead of GLSL, and the three.js manual still calls the renderer experimental. This project's custom shader work is small (coloring the pheromone texture, and optionally interpolating ant positions), and everything else uses built-in features that behave the same on both renderers. To contain the risk, the first day of week 13 is a spike: instanced ants and the pheromone texture rendering under WebGPURenderer. If that stalls after about a day, I switch to the established WebGLRenderer, which changes only the shader code.

**Why audio stays on the client.** The server never streams sound. It sends compact trail events, and each browser synthesizes the music itself, scheduled slightly ahead of time so network jitter does not cause stutter.

**Distribution.** The Ktor Gradle plugin builds a single executable JAR containing the server and the client files. The client, and the instrument samples it plays, are served from inside the JAR with Ktor's staticResources, so a local colony needs no internet connection. Anyone with Java can run a private colony on their own machine, which also serves as the fallback for grading if my server is unreachable.

## Code Plan

The code is split into three parts that know as little about each other as possible: a pure simulation core, a server that shares it, and a browser client that draws and plays it. The core knows nothing about networking or sound. This separation mirrors the project's thesis: the music is not programmed into the ants, it is read off their behavior at a higher level.

User actions flow up to the tick loop; trail events flow down to every browser, which draws and plays them locally.

### 1. colony-core (pure Kotlin, no framework)

- **Ant**: position, heading, state (Searching or Returning), an optional food load, and two traits set at birth: a preferred heading and a fidelity. Each tick it moves forward with small random turns, holding roughly its direction so searchers fan outward instead of jittering in place, and drifting gently toward its preferred heading. When it senses food pheromone in its three forward cones, it steers toward the strongest with a probability set by its fidelity; otherwise it keeps exploring. Food gives off a short-range scent, so a searcher that wanders close can home in on it. A returning ant lays food pheromone on its way back to the nest.
- **PheromoneGrid**: a 2D float array, starting at 512 × 512 cells (about 1 MB, each cell roughly one ant length), with separate layers for "toward food" and "toward home." It evaporates and diffuses slightly every tick. Ants move with continuous positions rather than hopping cell to cell, and sample the grid with bilinear interpolation, so steering stays smooth across cell boundaries. All rates (evaporation, diffusion, speed, sensing distance) are defined in world units rather than per cell, so the resolution can be tuned in week 11 by benchmarking 256², 512² and 1024² without changing how the colony behaves. It is the colony's shared memory: no ant holds it, all ants write to it.
- **Terrain**: a second 2D grid holds ground height (gentle generated hills) and blocked cells (rocks, twigs). Ants slow on steep slopes and cannot enter blocked cells, so trails curve through valleys and around obstacles instead of running straight. The simulation stays 2D; an ant's 3D position is just its grid coordinates plus the ground height beneath it.
- **Nest and food economy**: each food pile holds a finite amount and shrinks as ants carry it away. Returning ants add food to a store at the nest, and whenever the store holds enough, the nest spends it to hatch a new ant. Ants live a few minutes and then die; if the store runs empty, the colony stops growing and shrinks, down to a floor of a few hundred ants so it never dies out. The population therefore settles wherever incoming food balances deaths. Constant turnover also means trails keep their identity while their membership changes completely, as the Anteater describes (p. 329).
- **Castes and headings**: fidelity varies across the colony. Low-fidelity ants act as scouts that keep exploring even when strong trails exist; high-fidelity ants act as foragers that work established trails. This keeps the colony from collapsing onto a single trail, and echoes the castes in the dialogue (pp. 322–324). Preferred headings are a soft bias, not a hard boundary: an ant can still follow a strong trail outside its direction. The nest assigns each newborn a heading drawn mostly from the directions recent foragers returned from, with a fixed share chosen at random so exploration never stops. That distribution of headings is the colony's version of Hofstadter's caste distribution: a pattern no single ant holds, which shifts to reflect where food is in the world (pp. 324, 329).
- **Mound**: the nest raises the terrain around it into a cone that grows a little with every unit of food brought home, up to a cap. Ants climb it on the way home, and since terrain height shapes loudness and note length, melodies near the nest change as it grows. The mound is the colony's property, not the colony: in the dialogue, Aunt Hillary lives on an estate that "used to belong to someone else" (p. 336).
- **TrailDetector (the signal level)**: the pheromone grid is only a field of numbers; nothing in it is a "trail" yet. The detector finds trails in that field and turns them into objects the music can use. Every half second it:
    1. Keeps only strong scent. It reads only the food-pheromone layer, which ants lay while carrying food, so it marks routes to food rather than general wandering. Cells below a threshold are ignored, so faint, scattered pheromone never becomes a trail. This models the Anteater's point that activity either fizzles out or snowballs past a critical mass (p. 322).
    2. Sets aside the hub. Every route crowds together at the nest, so cells within a fixed radius of it are ignored, and detection starts at the hub's edge.
    3. Thins the strong cells into a network. The strong cells are thinned to one-cell-wide centerlines, which form a network of segments meeting at junctions. A trunk trail that forks becomes one segment splitting into two.
    4. Finds the voices. Each voice is a path through that network from the hub's edge to one food source. Voices heading the same way share their trunk segments and split at a junction, so branching trails are handled naturally.
    5. Matches paths to last time's voices. A path that overlaps an existing voice's path is the same voice, so it keeps its ID, color and instrument even as the ants on it change, as Hofstadter's signals do (p. 328).

  Each voice's path is smoothed into a curve, and that curve is what clients receive and what the melody walks along. The grid is the ant-level medium; the network of curves is the signal-level object.
- **ColonyEvent**: a sealed class of everything that crosses out of the core: TrailFormed, TrailUpdated, TrailFaded, FoodPlaced, Flood.

### 2. colony-server (Ktor)

- **Tick loop**: a single coroutine owns the colony and advances it at a fixed rate (about 30 ticks per second). Ant updates within a tick run in parallel across cores.
- **Action queue**: user actions arriving over WebSocket are queued and applied at the start of the next tick, so the colony is only ever mutated by the tick loop. This avoids race conditions without locks.
- **Broadcaster**: each client has its own coroutine draining a small outgoing channel, created with a capacity of about 8 and the DROP_OLDEST overflow policy, so the tick loop never waits on a slow connection. A new client receives a full snapshot, then deltas; if a client falls behind, its stale frames are dropped so it never slows down anyone else. Updates are sent at the level each client needs:
    - Signals: trail events and trail splines go to every client, along with a downsampled pheromone map (128 × 128, one byte per cell) a few times a second for the ground texture. They are small and drive the music.
    - Ants: individual positions, quantized to 16-bit integers in a binary message, go only to clients zoomed in, and only for the area on their screen.
    - Symbols: a low-rate summary of colony regions (such as where food is being gathered) for the zoomed-out view.
- **Rate limiting**: each client may place a limited amount of food per minute, so one person cannot dominate the shared colony.

Streaming signals rather than ants is both an engineering necessity and a statement of the project's thesis: the level that carries meaning is also the level worth sending.

### 3. Browser client (JavaScript)

- **Renderer (Three.js)**: ants are one InstancedMesh sized for the maximum population, with its count lowered to the live population each frame, its instance buffer marked for dynamic updates, and frustum culling turned off because ants span the whole map; the terrain is a ground mesh built from the height grid, with the pheromone grid draped over it as a DataTexture colored by a small TSL shader; trails are ribbon meshes, one color per voice; symbol regions are translucent glowing planes. An orthographic camera gives the default top-down view, and a perspective camera handles the tilted stacked-levels view. The renderer runs at the display's refresh rate (60 Hz or more) while the server ticks at 30 Hz, so it draws each ant interpolated between its two most recent positions, trading about one tick (33 ms) of delay for smooth motion. If writing every ant's matrix each frame proves too slow, previous and current positions move into instance attributes and a TSL vertex node does the interpolation instead. As a stretch goal, a WebGPU compute pass could interpolate every ant on the GPU, letting a zoomed-in client draw the whole colony; it stays optional because compute features may not run on the WebGL 2 fallback.
- **Sound engine (Web Audio API + smplr)**: a custom lookahead scheduler (about 200 lines) wakes every 25 ms and books any notes due in the next 100 ms against the audio clock, the standard "two clocks" pattern, so timing stays sample-accurate even when the page or network stutters. smplr (version pinned, since its API is still settling) supplies sampled General MIDI instruments: an organ for the voices and a soft percussive sample for ant ticks, self-hosted rather than fetched from smplr's default online sample source. Everything shares the AudioContext of the Three.js AudioListener, which is resumed when the visitor clicks Join, because browsers block audio until a user gesture. Each voice gets its own instrument instance whose destination is a GainNode fed into a Three.js PositionalAudio object placed at its trail. The engine turns trail events into music under fixed constraints:
    - Scale: pitches come only from a pentatonic scale, so no combination clashes.
    - Rhythm: notes are quantized to an 8th-note grid at about 100 BPM.
    - Voices: each strong trail becomes one voice, capped at four. Its melody is read along its spline from the nest end outward: north–south position sets the scale degree, east–west position sets timbre (mellow organ stops in the west, brighter reed stops in the east), and terrain height sets loudness and note length. Pitch depends on the colony, never on the viewer's camera, so the same trail produces the same melody in every browser even though each client composes it independently.
    - Shared openings: there is no imitation rule. Voices whose paths share a trunk play the same opening phrase and then diverge where their paths split, and they enter at different times because their trails formed at different times. Any subject-and-answer a listener hears comes from the colony's geometry and timing, not from the code.
    - Organ point: a low sustained note holds the key under everything (synthesized directly with a few Web Audio oscillators, since sampled notes decay and smplr's sample looping is still experimental), the device the Tortoise points out in the dialogue (pp. 334–335). Its loudness and richness follow the nest's food store, so the drone tracks the colony's health while the trail voices carry its activity.
    - Noise control: a toggle swaps the colony's trails for random wandering paths fed through exactly the same rules. The scale, beat grid and instruments only constrain what can be played; everything else should come from the colony. Whatever difference a listener hears between the colony and the control is what the ants contributed.
- **Input**: clicking places food; the slider changes the client's subscription level.

### 4. Genomes (stretch goal)

In the core plan, the nest assigns each newborn's traits from colony-wide statistics. As a stretch goal, newborns would instead inherit preferred heading and fidelity from a specific successful forager, with small mutations, adding a light layer of self-replication that echoes the Typogenetics chapter.

## Visualization Plan

Every visitor sees and hears the same colony, but each chooses their own level of description with a slider. Two people looking at the same moment can see it as ants or as symbols, the way the characters in the dialogue read different levels of the same MU-picture (pp. 316–317).

| Slider level | What the screen shows | What the user hears most clearly |
|---|---|---|
| Ants | Every ant drawn individually; pheromone faint | Soft ticks from nearby ant activity |
| Signals | Ants fade; trails drawn as colored ribbons, one color per voice | Each trail's melody line |
| Symbols | Trails abstracted into glowing regions labeled by meaning ("food: north-east"); a ring around the hill shows the colony's distribution of preferred headings | The full fugue, voices entering and answering |

Because the client is 3D, the slider moves a camera rather than switching modes: zooming in descends toward individual ants, zooming out rises to the colony as a whole. A second control tilts the camera into a stacked view, where the three levels float on separate planes above one another: ants on the ground, trail ribbons above them, symbol regions at the top, with faint vertical threads linking each symbol to the trails and ants that compose it. This makes the hierarchy literal, like the MU-picture's letters made of letters, and lets a viewer see in one glance that every level is the same colony described differently.

Several displays bridge the gap between mechanism and meaning, and between people:

- **Piano roll strip.** Along the bottom, a scrolling roll shows each voice's notes in its trail's color. When two voices share a trunk, their matching opening phrases are visible side by side.
- **Highlight on sound.** When a voice plays a note, the trail cell it came from pulses on screen, tying every sound to a place in the colony.
- **Other visitors.** Each connected person appears as a small colored marker, and food they place carries their color. Everyone can see who started which trail, and therefore which voice.

The intended experience has a clear arc. At startup the ants wander randomly and the room hears only scattered ticks. As classmates place food and trails strengthen, voices enter one by one. Each pile of food becomes new ants, so a well-fed colony grows and its music thickens. When people stop feeding it, food runs out, trails evaporate, the population shrinks, and the fugue dissolves back into noise.

A flood control, inspired by the fate of the colony Fermant (pp. 337–338), scatters every ant and wipes the pheromone grid, and erodes the mound to about half its height without destroying it. Over the next minute the same ants regroup into a new organization, and a different fugue emerges from the same parts, rebuilding on the old colony's damaged estate just as Aunt Hillary inherited Fermant's: in the Anteater's words, a different kind of sum rather than more than the sum (p. 338). Because the flood affects everyone, it requires a majority of connected visitors to vote for it.

## Final Artifact Plan

The end user opens a link and joins the colony already running on my server, alongside everyone else who is connected. They can:

- Place food by clicking, which feeds the colony: each pile is finite, and ants turn it into new ants, new trails and new voices for everyone.
- Move the level-of-description slider between Ants, Signals and Symbols, independently of other visitors.
- Mute individual voices on their own client to isolate one trail's melody.
- Switch on the noise control to hear the same rules played from random paths, and compare it with the colony.
- Vote for a flood to dissolve the colony and hear a new one form from the same ants.
- See other visitors and which trails they started.

The same JAR also runs offline: java -jar ant-fugue.jar starts a private colony at localhost, with extra controls (number of ants, evaporation rate, tempo, scale) for experimenting with how low-level rules change the high-level music.

The submission package will include the public link, the source code on GitHub, the executable JAR, a README with run instructions and an architecture overview, the philosophical report, and the 5–7 minute demo video. If possible, the demo video will show a live session with classmates connected.

## Timeline & Risks

The schedule follows the course phases. The simulation core and the WebSocket server both land in the logic-engine weeks, so multiplayer works before any sound or visual polish depends on it.

At the end of week 13 I compare the colony against the noise control. If the colony's music is not clearly more structured, week 14 goes to tuning the mapping and trail detection, never to adding rules that compose on the colony's behalf.

| Risk | Likelihood | Fallback |
|---|---|---|
| Output sounds like noise rather than music | Medium | Pentatonic scale, beat grid and a four-voice cap make the worst case pleasant; test with fake data in week 10 |
| Colony's music sounds no more structured than the noise control | Medium | Tune thresholds, path sampling and note lengths; report the result honestly either way |
| Concurrency bugs between the tick loop and user actions | Medium | Only the tick loop mutates the colony; actions wait in a queue |
| Bandwidth with many clients | Low | Stream trail events to everyone; send ant positions only to zoomed-in clients, quantized and binary |
| Server down or unreachable during grading | Low | The same JAR runs a local colony; the demo video shows the live session |
| One user floods the shared colony with food | Medium | Per-client rate limit on food; flood requires a majority vote |
| Trail detection unstable between frames | Low | Match trails by overlapping cells; smooth strength over time |
| Trait inheritance runs over time | High | Treat it as a stretch goal and cut it first |
| Three.js learning curve slows the visualization weeks | Medium | Build the flat top-down view first; the stacked-levels view is added only once it works |
| Hand-written scheduler drifts or stutters | Low | Follow the well-documented lookahead pattern; prototype it with fake data in week 10; Tone.js is the drop-in fallback |
| Ants all crowd onto one trail | Medium | Probabilistic following, scout ants and preferred headings keep a share of ants exploring; tune the scout share |
| Audio stalls when the tab is in the background | Low | Browsers throttle background timers; pause the scheduler while hidden and resync to the server's tick on return |
| WebGPURenderer or TSL proves too immature or hard to debug | Medium | One-day spike at the start of week 13; if it stalls, switch to WebGLRenderer, which changes only the shader code |
