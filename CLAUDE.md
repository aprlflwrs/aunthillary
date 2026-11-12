# Ant Fugue (aunthillary)

**ProjectPlan.md is the source of truth** for design, interfaces and the action plan. Read it before starting work. If what you are doing would change an interface (the Interfaces section), update the plan first and say so. The original proposal is kept at the git tag `proposal` (`git show proposal:ProjectPlan.md`); don't edit or rely on it.

## Commands

- `./gradlew test`: run tests
- `./gradlew run`: start the server at http://localhost:8080
- `./gradlew buildFatJar`: build `build/libs/aunthillary.jar`

## Rules

- `core/` and `trails/` are pure Kotlin: no Ktor or server imports.
- The browser client is plain ES modules served from `src/main/resources/static/`, with no bundler and no npm build step. Third-party libraries are pinned copies in `static/vendor/`, loaded through the import map in `index.html`.
- Every tunable number goes through the params registry, not a hard-coded constant.
- Mappings are pure functions and only choose scale degrees, never pitches or times (see the Interfaces section of the plan). A mapping must not depend on which voice it is playing (its `id` or `slot`), only on the path and the colony; otherwise the code, not the colony, would be composing.
- The project owner must understand all the code. Keep it readable, comment the reasoning behind non-obvious choices, and when you finish a piece of work, explain what you built and how it fits together.
