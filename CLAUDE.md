# Ant Fugue (aunthillary)

**ProjectPlan.md is the source of truth** for design, interfaces and the action plan. Read it before starting work. If what you are doing would change an interface (the Interfaces section), update the plan first and say so. The original proposal is kept at the git tag `proposal` (`git show proposal:ProjectPlan.md`); don't edit or rely on it.

## Commands

- `./gradlew check`: Kotlin tests, then the JavaScript tests under Node (skipped if Node is missing)
- `./gradlew run`: start the server at http://localhost:8080
- `./gradlew buildFatJar`: build `build/libs/aunthillary.jar` (run it with `java -jar build/libs/aunthillary.jar`)

## Where things are

- `src/main/kotlin/net/aprlflwrs/aunthillary/core/`: the colony. `Colony.kt` is the interface the server drives (the fake colony implements it now, the real one in Phase 1); `Params.kt` is the params registry; `World.kt` holds the world size and tick length.
- `.../trails/`: the trail detector (Phase 1).
- `.../server/`: `Server.kt` (Ktor module, named in `application.yaml`), `TickLoop.kt` (owns the colony; the only code that touches it), `Outbox.kt` (one latest-value slot per message type), `Protocol.kt` (every message encoder), `FakeColony.kt`.
- `src/main/resources/static/`: the browser client. `net/protocol.js` decodes what `Protocol.kt` encodes; `ui/tuning.js` builds the panel from the params message; `debug/view2d.js` is a throwaway 2D view until the Three.js renderer exists; `vendor/` holds pinned libraries (see `vendor/VERSIONS.txt`).
- Tests: `src/test/kotlin/` checks every message byte for byte against the plan and writes the bytes to `build/protocol-fixtures/`; `src/test/js/` decodes those same bytes with the browser decoder.

## Rules

- `core/` and `trails/` are pure Kotlin: no Ktor or server imports.
- Only the tick loop touches the colony. Browser messages go through `TickLoop.actions`.
- Every tick advances simulated time by exactly `World.DT`; the speed control changes how often ticks run, never how long one is. All randomness comes from the colony's seeded generator.
- The browser client is plain ES modules served from `src/main/resources/static/`, with no bundler and no npm build step. Third-party libraries are pinned copies in `static/vendor/`, loaded through the import map in `index.html`.
- Every tunable number goes through the params registry, not a hard-coded constant.
- A change to a message layout changes `Protocol.kt`, `net/protocol.js`, both tests and the plan's Interfaces section together.
- Mappings are pure functions and only choose scale degrees, never pitches or times (see the Interfaces section of the plan). A mapping must not depend on which voice it is playing (its `id` or `slot`), only on the path and the colony; otherwise the code, not the colony, would be composing.
- The project owner must understand all the code. Keep it readable, comment the reasoning behind non-obvious choices, and when you finish a piece of work, explain what you built and how it fits together.
